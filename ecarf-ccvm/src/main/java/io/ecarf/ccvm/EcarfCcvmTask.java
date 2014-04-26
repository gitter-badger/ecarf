/**
 * The contents of this file may be used under the terms of the Apache License, Version 2.0
 * in which case, the provisions of the Apache License Version 2.0 are applicable instead of those above.
 *
 * Copyright 2014, Ecarf.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.ecarf.ccvm;

import static io.ecarf.core.utils.Constants.AMAZON;
import static io.ecarf.core.utils.Constants.GOOGLE;
import static io.ecarf.core.utils.Constants.ZONE_KEY;
import io.ecarf.core.cloud.CloudService;
import io.ecarf.core.cloud.VMMetaData;
import io.ecarf.core.cloud.impl.google.GoogleCloudService;
import io.ecarf.core.cloud.task.Input;
import io.ecarf.core.cloud.task.Results;
import io.ecarf.core.cloud.task.Task;
import io.ecarf.core.cloud.task.impl.DistributeLoadTask;
import io.ecarf.core.cloud.task.impl.DistributeReasonTask;
import io.ecarf.core.cloud.task.impl.DoLoadTask;
import io.ecarf.core.cloud.task.impl.PartitionLoadTask;
import io.ecarf.core.cloud.task.impl.PartitionReasonTask;
import io.ecarf.core.cloud.task.impl.SchemaTermCountTask;
import io.ecarf.core.partition.Item;
import io.ecarf.core.utils.Config;
import io.ecarf.core.utils.Constants;
import io.ecarf.core.utils.TestUtils;
import io.ecarf.core.utils.Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * @author Omer Dawelbeit (omerio)
 *
 */
public class EcarfCcvmTask {
	
	private final static Logger log = Logger.getLogger(EcarfCcvmTask.class.getName()); 
	
	private CloudService service;
	
	private VMMetaData metadata;
	
	
	public void run() throws IOException {
		
		String bucket = "swetodblp";
		String schema = "opus_august2007_closure.nt";
		String table = "ontologies.swetodblp";
		Task task;
		Results results;
		Input input;
		List<String> nodes = null;
		
		// 1- load the schema and do a count of the relevant terms
		metadata = new VMMetaData();
		metadata.addValue(VMMetaData.ECARF_BUCKET, bucket);
		metadata.addValue(VMMetaData.ECARF_SCHEMA, schema);
		task = new SchemaTermCountTask(metadata, service);
		task.run();
		
		// 2- partition the instance files into bins
		input = (new Input()).setBucket(bucket).setWeightPerNode(FileUtils.ONE_MB * 80)
				.setNewBinPercentage(0.0);
		
		task = new PartitionLoadTask(null, service);
		task.setInput(input);
		task.run();
		
		results = task.getResults();
		
		List<String> nodeFiles = results.getBinItems();
		
		for(String files: nodeFiles) {
			log.info("Partitioned files: " + files + "\n");
		}
		
		// 3- Distribute the load task between the various nodes
		input = (new Input()).setBucket(bucket).setItems(nodeFiles)
				.setImageId(Config.getProperty(Constants.IMAGE_ID_KEY))
				.setNetworkId(Config.getProperty(Constants.NETWORK_ID_KEY))
				.setVmType(Config.getProperty(Constants.VM_TYPE_KEY))
				.setStartupScript(Config.getProperty(Constants.STARTUP_SCRIPT_KEY))
				.setSchemaTermsFile(Constants.SCHEMA_TERMS_JSON);;
		task = new DistributeLoadTask(null, service);
		task.setInput(input);
		task.run();
		
		results = task.getResults();
		
		nodes = results.getNodes(); //Lists.newArrayList("ecarf-evm-1398457340229", "ecarf-evm-1398457340230");
		
		log.info("Active nodes: " + nodes);
		
		List<Item> items = results.getItems(); //this.getMockResults(bucket).getItems();
		
		log.info("Term stats for reasoning task split: " + items);
		
		// 4- get the schema terms stats which was generated by each node
		// TODO for smaller term occurrences such as less than 5million we might want to set a maximum 
		// rather than the largest term occurrences
		
		input = (new Input()).setWeightedItems(items)
				.setNewBinPercentage(Config.getDoubleProperty(Constants.TERM_NEW_BIN_KEY, 0.1))
				// 5 million triples per node for swetodblp
				.setWeightPerNode(5_000_000L);
		task = new PartitionReasonTask(null, service);
		task.setInput(input);
		task.run(); 
		
		results = task.getResults();
		
		List<List<Item>> bins = results.getBins();
		
		log.info("Total number of required evms is: " + bins.size());
		
		for(List<Item> bin: bins) {
			log.info("Set: " + bin + ", Sum: " + Utils.sum(bin) + "\n");
			for(Item item: bin) {
				System.out.println(item.getKey() + "," + item.getWeight());
			}
		}
		
		List<String> terms = results.getBinItems();
		
		// 5- Load the generated files into Big Data table
		input = (new Input()).setBucket(bucket).setTable(table);	
		task = new DoLoadTask(null, service);
		task.setInput(input);
		task.run();
		
		// 6- distribute the reasoning between the nodes
		input = (new Input()).setItems(terms)
				.setBucket(bucket).setTable(table)
				.setSchemaFile(schema)
				.setNodes(nodes)
				.setImageId(Config.getProperty(Constants.IMAGE_ID_KEY))
				.setNetworkId(Config.getProperty(Constants.NETWORK_ID_KEY))
				.setVmType(Config.getProperty(Constants.VM_TYPE_KEY))
				.setStartupScript(Config.getProperty(Constants.STARTUP_SCRIPT_KEY))
				.setZoneId(Config.getProperty(ZONE_KEY))
				;
		
		task = new DistributeReasonTask(null, service);
		task.setInput(input);
		task.run();
		
		// All nodes, which we can shut down
		List<String> allNodes = task.getResults().getNodes();
		log.info("All active nodes: " + allNodes);
		
		// TODO shutdown active nodes
		
	}
	
	/**
	 * TODO remove
	 * @param bucket
	 * @return
	 * @throws IOException 
	 */
	/*private Results getMockResults(String bucket) throws IOException {
		Results results = new Results();
		Map<String, Long> allTermStats = new HashMap<String, Long>();
		
		Set<String> files = Sets.newHashSet("ecarf-evm-1398457340229.json", "ecarf-evm-1398457340230.json");
		
		for(String file: files) {	
			
			String localStatsFile = Utils.TEMP_FOLDER + file;
			this.service.downloadObjectFromCloudStorage(file, localStatsFile, bucket);

			// convert from JSON
			Map<String, Long> termStats = Utils.jsonFileToMap(localStatsFile);

			for(Entry<String, Long> term: termStats.entrySet()) {
				String key = term.getKey();
				Long value = term.getValue();

				if(allTermStats.containsKey(key)) {
					value = allTermStats.get(key) + value;
				} 

				allTermStats.put(key, value);
			}
			
			log.info("Evms analysed: " + allTermStats.size() + ", terms");

		}

		if(!allTermStats.isEmpty()) {

			List<Item> items = new ArrayList<>();
			for(Entry<String, Long> item: allTermStats.entrySet()) {
				Item anItem = (new Item()).setKey(item.getKey()).setWeight(item.getValue());
				items.add(anItem);
			}

			results.setItems(items);

			log.info("Successfully created term stats: " + items);
			
		}
		
		return results;
	}*/
	
	
	/**
	 * @param service the service to set
	 */
	public void setService(CloudService service) {
		this.service = service;
		
	}
	
	/**
	 * @param metadata the metadata to set
	 */
	public void setMetadata(VMMetaData metadata) {
		this.metadata = metadata;
	}

	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {
		String platform = GOOGLE;
		if(args.length > 0) {
			platform = args[0];
		}
		
		EcarfCcvmTask task = new EcarfCcvmTask();
		VMMetaData metadata = null;

		switch(platform) {
		case GOOGLE:
			GoogleCloudService service = new GoogleCloudService();
			//try {
			//metadata = service.inti();
			TestUtils.prepare(service);
			task.setService(service);
			task.setMetadata(metadata);

			/*} catch(IOException e) {
				log.log(Level.SEVERE, "Failed to start evm program", e);
				throw e;
			}*/
			break;

		case AMAZON:
			break; 

		default:
			System.out.println("usage EcarfEvmTask <platform>");
			System.exit(1);
		}

		task.run();
	}

}
