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
package io.ecarf.core.cloud.task.processor.reason.phase2;

import io.cloudex.cloud.impl.google.GoogleCloudService;
import io.cloudex.framework.cloud.api.ApiUtils;
import io.cloudex.framework.cloud.entities.QueryStats;
import io.cloudex.framework.cloud.entities.StorageObject;
import io.cloudex.framework.task.CommonTask;
import io.cloudex.framework.utils.ObjectUtils;
import io.ecarf.core.reason.rulebased.GenericRule;
import io.ecarf.core.reason.rulebased.Rule;
import io.ecarf.core.reason.rulebased.query.QueryGenerator;
import io.ecarf.core.term.TermUtils;
import io.ecarf.core.triple.NTriple;
import io.ecarf.core.triple.SchemaURIType;
import io.ecarf.core.triple.Triple;
import io.ecarf.core.triple.TripleUtils;
import io.ecarf.core.utils.Config;
import io.ecarf.core.utils.Constants;
import io.ecarf.core.utils.TableUtils;
import io.ecarf.core.utils.Utils;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.compressors.gzip.GzipUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

/**
 * Reason task that saves all the inferred triples in each round in a single file then uploads it to Cloud storage then Big data. 
 * Hybrid big data streaming for inferred triples of 100,000 or smaller
 * @author Omer Dawelbeit (omerio)
 *
 */
public class DoReasonTask6 extends CommonTask {
	
	private final static Log log = LogFactory.getLog(DoReasonTask6.class);
	
	//private static final int MAX_CACHE = 40000000;
	
	private int duplicates;
	
	private BigInteger totalRows = BigInteger.valueOf(0l);
	
	private Long totalBytes = 0L;
	
	private Map<String, Set<Triple>> schemaTerms;
	
	//private ExecutorService executor;
	
	private String table;
	
	private String schemaFile;
	
	private String terms;
	
	private String termsFile;
	
	private String bucket;

	/* (non-Javadoc)
	 * @see io.ecarf.core.cloud.task.Task#run()
	 */
	@Override
	public void run() throws IOException {
	    
	    GoogleCloudService cloud = (GoogleCloudService) this.getCloudService();

		//String table = metadata.getValue(EcarfMetaData.ECARF_TABLE);
		//Set<String> terms = metadata.getTerms();
		//String schemaFile = metadata.getValue(EcarfMetaData.ECARF_SCHEMA);
		//String bucket = metadata.getBucket();
		Stopwatch stopwatch1 = Stopwatch.createUnstarted();
		Stopwatch stopwatch2 = Stopwatch.createUnstarted();
		Set<String> termsSet;
		
		if(terms == null) {
			// too large, probably saved as a file
			//String termsFile = metadata.getValue(EcarfMetaData.ECARF_TERMS_FILE);
			log.info("Using json file for terms: " + termsFile);
			Validate.notNull(termsFile);
			
			String localTermsFile = Utils.TEMP_FOLDER + termsFile;
			cloud.downloadObjectFromCloudStorage(termsFile, localTermsFile, bucket);

			// convert from JSON
			termsSet = io.cloudex.framework.utils.FileUtils.jsonFileToSet(localTermsFile);
			
		} else {
		    termsSet = ObjectUtils.csvToSet(terms);
		}
		
		String localSchemaFile = Utils.TEMP_FOLDER + schemaFile;
		// download the file from the cloud storage
		cloud.downloadObjectFromCloudStorage(schemaFile, localSchemaFile, bucket);

		// uncompress if compressed
		if(GzipUtils.isCompressedFilename(schemaFile)) {
			localSchemaFile = GzipUtils.getUncompressedFilename(localSchemaFile);
		}

		Map<String, Set<Triple>> allSchemaTriples = 
				TripleUtils.getRelevantSchemaNTriples(localSchemaFile, TermUtils.RDFS_TBOX);

		// get all the triples we care about
		schemaTerms = new HashMap<>();

		for(String term: termsSet) {
			if(allSchemaTriples.containsKey(term)) {
				schemaTerms.put(term, allSchemaTriples.get(term));
			}
		}
		
		String decoratedTable = table;
		int emptyRetries = 0;
		int totalInferredTriples = 0;
		int maxRetries = Config.getIntegerProperty(Constants.REASON_RETRY_KEY, 6);
		String instanceId = cloud.getInstanceId();
		
		QueryGenerator<String> generator = new QueryGenerator<String>(schemaTerms, null);
		
		// timestamp loop
		do {

			Set<String> productiveTerms = new HashSet<>();
			int interimInferredTriples = 0;

			// First of all run all the queries asynchronously and remember the jobId and filename for each term

			List<QueryResult> queryResults = new ArrayList<QueryResult>();
			generator.setDecoratedTable(decoratedTable);
			
			List<String> queries = generator.getQueries();
			log.debug("Generated Queries: " + queries);
			String queryResultFilePrefix = Utils.TEMP_FOLDER + instanceId + '_' + System.currentTimeMillis() + "_QueryResults_";
			int fileCount = 0;
			for(String query: queries) {
				String jobId = cloud.startBigDataQuery(query);
				queryResults.add(QueryResult.create().setFilename(queryResultFilePrefix + fileCount).setJobId(jobId));
				fileCount++;
			}
			
			// invoke all the queries in parallel
			//this.invokeAll(queryTasks);

			long start = System.currentTimeMillis();
			
			String inferredTriplesFile =  Utils.TEMP_FOLDER + instanceId + '_' + start + Constants.DOT_INF;
			
			// save all the query results in files in parallel
			//this.invokeAll(saveTasks);
			
			for(QueryResult queryResult: queryResults) {
				try {
					// block and wait for each job to complete then save results to a file
					QueryStats stats = cloud.saveBigQueryResultsToFile(queryResult.getJobId(), queryResult.getFilename());
					queryResult.setStats(stats);

				} catch(IOException ioe) {
					// transient backend errors
					log.warn("failed to save query results to file, jobId: " + queryResult.getJobId(), ioe);
					//TODO should throw an exception
				}
			}
			
			try(PrintWriter writer = 
					new PrintWriter(new GZIPOutputStream(new FileOutputStream(inferredTriplesFile), Constants.GZIP_BUF_SIZE))) {

				// now loop through the queries
				//for(Entry<Term, Set<Triple>> entry: schemaTerms.entrySet()) {
				for(QueryResult queryResult: queryResults) {

					//Term term = entry.getKey();
					QueryStats stats = queryResult.getStats();
					
					BigInteger rows = stats.getTotalRows();//term.getRows();
					
					this.totalBytes = this.totalBytes + stats.getTotalProcessedBytes();//term.getBytes();
					
					// only process if triples are found matching this term
					if(!BigInteger.ZERO.equals(rows)) {
						
						stopwatch1.start();
						
						int inferredTriplesCount = this.inferAndSaveTriplesToFile(queryResult, productiveTerms, decoratedTable, writer);

						interimInferredTriples += inferredTriplesCount;
						
						this.totalRows = this.totalRows.add(rows);
						
						stopwatch1.stop();

					} else {
						log.info("Skipping query as no data is found");
					}
				}
			}
			
			totalInferredTriples += interimInferredTriples;

			if(interimInferredTriples > 0) {
				
				// stream smaller numbers of inferred triples
				// try uploading from cloud storage
				int streamingThreshold = Config.getIntegerProperty("ecarf.io.reasoning.streaming.threshold", 100000);
				
				log.info("Inserting " + interimInferredTriples + 
						", inferred triples into Big Data table for " + productiveTerms.size() + " productive terms. Filename: " + inferredTriplesFile);
				
				if(interimInferredTriples <= streamingThreshold) {
					// stream the data
					
					Set<Triple> inferredTriples = TripleUtils.loadCompressedCSVTriples(inferredTriplesFile, false);
					log.info("Total triples to stream into Big Data: " + inferredTriples.size());
					cloud.streamObjectsIntoBigData(inferredTriples, TableUtils.getBigQueryTripleTable(table));
					
					log.info("All inferred triples are streamed into Big Data table");
					
				} else {
					
					// load the data through cloud storage
					// upload the file to cloud storage
					log.info("Uploading inferred triples file into cloud storage: " + inferredTriplesFile);
					StorageObject file = cloud.uploadFileToCloudStorage(inferredTriplesFile, bucket);
					log.info("File " + file + ", uploaded successfully. Now loading it into big data.");
					
					String jobId = cloud.loadCloudStorageFilesIntoBigData(Lists.newArrayList(file.getUri()), 
					        TableUtils.getBigQueryTripleTable(table), false);
					log.info("All inferred triples are loaded into Big Data table through cloud storage, completed jobId: " + jobId);
					
				}
				
				// reset empty retries
				emptyRetries = 0;
				
				stopwatch2.reset();

			} else {
				log.info("No new inferred triples");
				// increment empty retries
				emptyRetries++;
				
				if(!stopwatch2.isRunning()) {
					stopwatch2.start();
				}
			}

			log.info("Total inferred triples so far = " + totalInferredTriples + ", current retry count: " + emptyRetries);
			
			if(emptyRetries < maxRetries) {
				ApiUtils.block(Config.getIntegerProperty(Constants.REASON_SLEEP_KEY, 20));

				// FIXME move into the particular cloud implementation service
				long elapsed = System.currentTimeMillis() - start;
				decoratedTable =  "[" + table + "@-" + elapsed + "-]";

				log.info("Using table decorator: " + decoratedTable + ". Empty retries count: " + emptyRetries);
			}

		} while(emptyRetries < maxRetries); // end timestamp loop
		
		//executor.shutdown();
		log.info("Finished reasoning, total inferred triples = " + totalInferredTriples);
		log.info("Number of avoided duplicate terms = " + this.duplicates);
		log.info("Total rows retrieved from big data = " + this.totalRows);
		log.info("Total processed GBytes = " + ((double) this.totalBytes / FileUtils.ONE_GB));
		log.info("Total process reasoning time (serialization in inf file) = " + stopwatch1);
		log.info("Total time spent in empty inference cycles = " + stopwatch2);
	}
	

	/**
	 * 
	 * @param term
	 * @param select
	 * @param schemaTriples
	 * @param rows
	 * @param table
	 * @param writer
	 * @return
	 * @throws IOException
	 */
	protected int inferAndSaveTriplesToFile(QueryResult queryResult, Set<String> productiveTerms, String table, PrintWriter writer) throws IOException {

		//Term term, List<String> select, Set<Triple> schemaTriples
		log.info("********************** Starting Inference Round **********************");
		
		int inferredTriples = 0;
		//int failedTriples = 0;

		// loop through the instance triples probably stored in a file and generate all the triples matching the schema triples set
		try (BufferedReader r = new BufferedReader(new FileReader(queryResult.getFilename()), Constants.GZIP_BUF_SIZE)) {

			Iterable<CSVRecord> records = CSVFormat.DEFAULT.parse(r);

			// records will contain lots of duplicates
			//Set<String> inferredAlready = new HashSet<String>();

			try {
				
				String term;
				
				for (CSVRecord record : records) {

					//String values = ((select.size() == 1) ? record.get(0): StringUtils.join(record.values(), ','));

					//if(!inferredAlready.contains(values)) {
					//inferredAlready.add(values);

					NTriple instanceTriple = (NTriple) NTriple.fromCSV(record.values());//new NTriple();
					
					/*instanceTriple.setSubject(record.get(0));
					instanceTriple.setPredicate(record.get(1));
					instanceTriple.setObject(record.get(2));*/
					
					// TODO review for OWL ruleset
					if(SchemaURIType.RDF_TYPE.getUri().equals(instanceTriple.getPredicate())) {
						term = instanceTriple.getObject(); // object
					} else {
						term = instanceTriple.getPredicate(); // predicate
					}

					Set<Triple> schemaTriples = schemaTerms.get(term);

					if((schemaTriples != null) && !schemaTriples.isEmpty()) {
						productiveTerms.add(term);
						
						for(Triple schemaTriple: schemaTriples) {
							Rule rule = GenericRule.getRule(schemaTriple);
							Triple inferredTriple = rule.head(schemaTriple, instanceTriple);
							
							if(inferredTriple != null) {
							    writer.println(inferredTriple.toCsv());
							    inferredTriples++;
							}
						}
					}

					// this is just to avoid any memory issues
					//if(inferredAlready.size() > MAX_CACHE) {
					//	inferredAlready.clear();
					//	log.info("Cleared cache of inferred terms");
					//}
					//} else {
					//this.duplicates++;
					//}

				}
			} catch(Exception e) {
				log.error("Failed to parse selected terms", e);
				throw new IOException(e);
				//failedTriples++;
			}
		}

		log.info("Total Rows: " + queryResult.getStats().getTotalRows() + 
				", Total Processed Bytes: " + queryResult.getStats().getTotalProcessedGBytes() + " GB" + 
				", Inferred: " + inferredTriples);

		log.info("********************** Completed Inference Round **********************");
		
		return inferredTriples;
	}


    /**
     * @return the table
     */
    public String getTable() {
        return table;
    }


    /**
     * @param table the table to set
     */
    public void setTable(String table) {
        this.table = table;
    }


    /**
     * @return the schemaFile
     */
    public String getSchemaFile() {
        return schemaFile;
    }


    /**
     * @param schemaFile the schemaFile to set
     */
    public void setSchemaFile(String schemaFile) {
        this.schemaFile = schemaFile;
    }


    /**
     * @return the terms
     */
    public String getTerms() {
        return terms;
    }


    /**
     * @param terms the terms to set
     */
    public void setTerms(String terms) {
        this.terms = terms;
    }


    /**
     * @return the termsFile
     */
    public String getTermsFile() {
        return termsFile;
    }


    /**
     * @param termsFile the termsFile to set
     */
    public void setTermsFile(String termsFile) {
        this.termsFile = termsFile;
    }


    /**
     * @return the bucket
     */
    public String getBucket() {
        return bucket;
    }


    /**
     * @param bucket the bucket to set
     */
    public void setBucket(String bucket) {
        this.bucket = bucket;
    }


    /**
     * @param schemaTerms the schemaTerms to set
     */
    protected void setSchemaTerms(Map<String, Set<Triple>> schemaTerms) {
        this.schemaTerms = schemaTerms;
    }
	
}
