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
package io.ecarf.core.cloud.impl.google;

import static io.ecarf.core.cloud.impl.google.GoogleMetaData.*;
import static java.net.HttpURLConnection.HTTP_CONFLICT;
import io.ecarf.core.cloud.CloudService;
import io.ecarf.core.cloud.VMConfig;
import io.ecarf.core.cloud.VMMetaData;
import io.ecarf.core.cloud.impl.google.storage.DownloadProgressListener;
import io.ecarf.core.cloud.impl.google.storage.UploadProgressListener;
import io.ecarf.core.gzip.GzipProcessor;
import io.ecarf.core.gzip.GzipProcessorCallback;
import io.ecarf.core.triple.TripleUtils;
import io.ecarf.core.utils.Callback;
import io.ecarf.core.utils.Constants;
import io.ecarf.core.utils.Utils;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.time.DateUtils;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.compute.Compute;
import com.google.api.services.compute.Compute.Instances.Insert;
import com.google.api.services.compute.model.AccessConfig;
import com.google.api.services.compute.model.AttachedDisk;
import com.google.api.services.compute.model.AttachedDiskInitializeParams;
import com.google.api.services.compute.model.Instance;
import com.google.api.services.compute.model.Metadata;
import com.google.api.services.compute.model.Metadata.Items;
import com.google.api.services.compute.model.NetworkInterface;
import com.google.api.services.compute.model.Operation;
import com.google.api.services.compute.model.Scheduling;
import com.google.api.services.compute.model.ServiceAccount;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.Bucket;
import com.google.common.collect.Lists;

/**
 * @author Omer Dawelbeit (omerio)
 *
 */
public class GoogleCloudService implements CloudService {
	
	private final static Logger log = Logger.getLogger(GoogleCloudService.class.getName()); 

	/** Global instance of the JSON factory. */
	private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
	
	/** Global instance of the HTTP transport. */
	private HttpTransport httpTransport;
	
	private Storage storage;
	
	private Compute compute;
	
	// service account access token retrieved from the metadata server
	private String accessToken;
	
	// the expiry time of the token
	private Date tokenExpire;
	
	private String projectId;
	
	private String zone;
	
	private String instanceId;
	
	private String serviceAccount;
	
	private List<String> scopes;
	
	/**
	 * Perform initialization before
	 * this cloud service is used
	 * @throws IOException 
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public VMMetaData inti() throws IOException {
		Map<String, Object> attributes;
		this.projectId = getMetaData(PROJECT_ID_PATH);
		Map<String, Object> metaData = Utils.jsonToMap(getMetaData(INSTANCE_ALL_PATH));
		attributes = (Map<String, Object>) metaData.get(ATTRIBUTES);
		
		// strangely zone looks like this: "projects/315344313954/zones/us-central1-a"
		this.zone = (String) metaData.get(ZONE);
		this.zone = StringUtils.substringAfterLast(this.zone, "/");
		
		// the name isn't returned!, but the hostname looks like this:
		// "ecarf-evm-1.c.ecarf-1000.internal"
		this.instanceId = (String) metaData.get(HOSTNAME);
		this.instanceId = StringUtils.substringBefore(this.instanceId, ".");
		
		// get the default service account
		Map<String, Object> serviceAccountConfig = ((Map) ((Map) metaData.get(SERVICE_ACCOUNTS)).get(DEFAULT));
		this.serviceAccount = (String) serviceAccountConfig.get(EMAIL);
		this.scopes = (List) serviceAccountConfig.get(SCOPES);
		
		this.authorise();
		this.getHttpTransport();
		this.getCompute();
		this.getStorage();
		log.info("Successfully initialized Google Cloud Service: " + this);
		return new VMMetaData(attributes);
		
	}
	
	
	/**
	 * Call the metadata server, this returns details for the current instance not for
	 * different instances. In order to retrieve the meta data of different instances
	 * we just use the compute api, see getInstance
	 * @param path
	 * @return
	 * @throws IOException
	 */
	private String getMetaData(String path) throws IOException {
		log.fine("Retrieving metadata from server, path: " + path);
		URL metadata = new URL(METADATA_SERVER_URL + path);
		HttpURLConnection con = (HttpURLConnection) metadata.openConnection();
 
		// optional default is GET
		//con.setRequestMethod("GET");
 
		//add request header
		con.setRequestProperty("X-Google-Metadata-Request", "true");
 
		int responseCode = con.getResponseCode();
		
		StringBuilder response = new StringBuilder();
		
		if(responseCode == 200) {
			try(BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
				String inputLine;
				while ((inputLine = in.readLine()) != null) {
					response.append(inputLine);
				}
			}
		} else {
			String msg = "Metadata server responded with status code: " + responseCode;
			log.severe(msg);
			throw new IOException(msg);
		}
		log.fine("Successfully retrieved metadata from server");
		
		return response.toString();
	}

	/**
	 * Retrieves a service account access token from the metadata server, the response has the format
	 * {
		  "access_token":"ya29.AHES6ZRN3-HlhAPya30GnW_bHSb_QtAS08i85nHq39HE3C2LTrCARA",
		  "expires_in":3599,
		  "token_type":"Bearer"
		}
	 * @throws IOException 
	 * @see https://developers.google.com/compute/docs/authentication
	 */
	private void authorise() throws IOException {
		
		log.fine("Refreshing OAuth token from metadata server");
		Map<String, Object> token = Utils.jsonToMap(getMetaData(TOKEN_PATH));
		this.accessToken = (String) token.get(ACCESS_TOKEN);
		
		Integer expiresIn = (Integer) token.get(EXPIRES_IN);
		this.tokenExpire = DateUtils.addSeconds(new Date(), expiresIn);
		
		log.fine("Successfully refreshed OAuth token from metadata server");
	
	}
	
	/**
	 * Create a new instance of the HTTP transport
	 * @return
	 * @throws GeneralSecurityException
	 * @throws IOException
	 */
	private HttpTransport getHttpTransport() throws IOException {
		if(httpTransport == null) {
			try {
				httpTransport = GoogleNetHttpTransport.newTrustedTransport();
			} catch (GeneralSecurityException e) {
				log.log(Level.SEVERE, "failed to create transport", e);
				throw new IOException(e);
			}
		}
		
		return httpTransport;
	}
	
	/**
	 * Get the token and also check if it's expired then request a new one
	 * @return
	 * @throws IOException
	 */
	private String getOAuthToken() throws IOException {
	
		if((this.tokenExpire == null) || (new Date()).after(this.tokenExpire)) {
			this.authorise();
		}
		return this.accessToken;
	}
	
	/**
	 * Create a compute API client instance
	 * @return
	 * @throws IOException 
	 * @throws GeneralSecurityException 
	 */
	private Compute getCompute() throws IOException {
		if(this.compute == null) {
			this.compute = new Compute.Builder(getHttpTransport(), JSON_FACTORY, null)
				.setApplicationName(Constants.APP_NAME).build();
		}
		return this.compute;
	}
	
	/**
	 * Create a storage API client instance
	 * @return
	 * @throws IOException 
	 * @throws GeneralSecurityException 
	 */
	private Storage getStorage() throws IOException {
		if(this.storage == null) {
			this.storage = new  Storage.Builder(getHttpTransport(), JSON_FACTORY, new HttpRequestInitializer() {
				public void initialize(HttpRequest request) {
					request.setUnsuccessfulResponseHandler(new RedirectHandler());
				}
			})
			.setApplicationName(Constants.APP_NAME).build();
		}
		return this.storage;
	}
	
	//------------------------------------------------- Storage -------------------------------
	/**
	 * Create a bucket on the mass cloud storage
	 * @param bucket
	 * @throws IOException 
	 */
	@Override
	public void createCloudStorageBucket(String bucket, String location) throws IOException {

		Storage.Buckets.Insert insertBucket = this.getStorage().buckets()
				.insert(this.projectId, new Bucket().setName(bucket).setLocation(location)
				// .setDefaultObjectAcl(ImmutableList.of(
				// new ObjectAccessControl().setEntity("allAuthenticatedUsers").setRole("READER")))
				).setOauthToken(this.getOAuthToken());
		try {
			@SuppressWarnings("unused")
			Bucket createdBucket = insertBucket.execute();
		} catch (GoogleJsonResponseException e) {
			GoogleJsonError error = e.getDetails();
			if (error.getCode() == HTTP_CONFLICT
					&& error.getMessage().contains("You already own this bucket.")) {
				log.info(bucket + " already exists!");
			} else {
				throw e;
			}
		}
	}
	
	/**
	 * Upload the provided file into cloud storage
	 * @param filename
	 * @param bucket
	 * @throws IOException 
	 */
	@Override
	public void uploadFileToCloudStorage(String filename, String bucket, Callback callback) throws IOException {
		
		FileInputStream fileStream = new FileInputStream(filename);
		
		InputStreamContent mediaContent = new InputStreamContent(Constants.BINARY_CONTENT_TYPE, fileStream);
		
		// Not strictly necessary, but allows optimization in the cloud.
		mediaContent.setLength(fileStream.available());

		Storage.Objects.Insert insertObject =
				getStorage().objects().insert(bucket, null, mediaContent).setOauthToken(this.getOAuthToken());

		insertObject.getMediaHttpUploader().setProgressListener(
				new UploadProgressListener(callback)).setDisableGZipContent(true);
		// For small files, you may wish to call setDirectUploadEnabled(true), to
		// reduce the number of HTTP requests made to the server.
		if (mediaContent.getLength() > 0 && mediaContent.getLength() <=  2 * FileUtils.ONE_MB /* 2MB */) {
			insertObject.getMediaHttpUploader().setDirectUploadEnabled(true);
		}
		
		insertObject.execute();
	}
	
	/**
	 * Download an object from cloud storage to a file
	 * @param object
	 * @param outFile
	 * @param bucket
	 * @param callback
	 * @throws IOException
	 */
	@Override
	public void downloadObjectFromCloudStorage(String object, String outFile, 
			String bucket, Callback callback) throws IOException {
		
		log.info("Downloading cloud storage file " + object + ", to: " + outFile);
		
		FileOutputStream out = new FileOutputStream(outFile);
	
		Storage.Objects.Get getObject =
				getStorage().objects().get(bucket, object).setOauthToken(this.getOAuthToken());
		
		getObject.getMediaHttpDownloader().setDirectDownloadEnabled(true)
			.setProgressListener(new DownloadProgressListener(callback));
		
		getObject.executeMediaAndDownloadTo(out);
		
	}
	
	
	/**
	 * Convert the provided file to a format that can be imported to the Cloud Database
	 * 
	 * @param filename
	 * @return
	 * @throws IOException 
	 */
	@Override
	public String prepareForCloudDatabaseImport(String filename) throws IOException {
		/*String outFilename = new StringBuilder(FileUtils.TEMP_FOLDER)
			.append(File.separator).append("out_").append(filename).toString();*/
		GzipProcessor processor = new GzipProcessor(filename);
		
		String outFilename = processor.process(new GzipProcessorCallback() {

			@Override
			public String process(String line) {
				String[] terms = TripleUtils.parseTriple(line);
				String outLine = null;
				if(terms != null) {
					for(int i = 0; i < terms.length; i++) {
						// bigquery requires data to be properly escaped
						terms[i] = StringEscapeUtils.escapeCsv(terms[i]);
					}
					outLine = StringUtils.join(terms, ',');
				}
				return outLine;
			}
			
		});
		
		return outFilename;
	}
	
	//------------------------------------------------- Compute -------------------------------
	
	/**
	 * 
	 * @param instanceId
	 * @param zoneId
	 * @return
	 * @throws IOException
	 */
	private Instance getInstance(String instanceId, String zoneId) throws IOException {
		return this.getCompute().instances().get(this.projectId, 
				zoneId != null ? zoneId : this.zone, instanceId)
				.setOauthToken(this.getOAuthToken())
				.execute();
	}
	
	
	/**
	 * Get the meta data of the current instance, this will simply call the metadata server
	 * @return
	 * @throws IOException 
	 */
	@Override
	public VMMetaData getEcarfMetaData() throws IOException {
		String metaData = this.getMetaData(ATTRIBUTES_PATH);
		
		Map<String, Object> attributes = Utils.jsonToMap(metaData);
		
		return new VMMetaData(attributes);
		
	}
	
	/**
	 * Get the meta data for the provided instance id
	 * @param instanceId
	 * @param zoneId
	 * @return
	 * @throws IOException 
	 */
	@Override
	@SuppressWarnings("unchecked")
	public VMMetaData getEcarfMetaData(String instanceId, String zoneId) throws IOException {
		Instance instance = this.getInstance(instanceId, zoneId);
		Map<String, Object> attributes = (Map<String, Object>) instance.getMetadata().get(ATTRIBUTES);
		
		return new VMMetaData(attributes);
	}
	
	/**
	 * Update the meta data of the current instance
	 * @param key
	 * @param value
	 * @throws IOException
	 */
	@Override
	public void updateInstanceMetadata(String key, String value) throws IOException {
		Map<String, String> items = new HashMap<>();
		items.put(key, value);
		this.updateInstanceMetadata(items, this.zone, this.instanceId);
	}
	
	/**
	 *TODO change to VMMetaData
	 * Update the meta data of the current instance
	 * @param key
	 * @param value
	 * @throws IOException
	 */
	@Override
	public void updateInstanceMetadata(Map<String, String> items) throws IOException {
		this.updateInstanceMetadata(items, this.zone, this.instanceId);
	}
	
	/**
	 * TODO change to VMMetaData
	 * Update the meta data of the the provided instance
	 * @param key
	 * @param value
	 * @throws IOException
	 */
	@Override
	public void updateInstanceMetadata(Map<String, String> items, 
			String zone, String instanceId) throws IOException {
		Metadata metadata = new Metadata();
		
		for(Entry<String, String> entry: items.entrySet()) {
			metadata.set(entry.getKey(), entry.getValue());
		}
		
		this.getCompute().instances().setMetadata(projectId, zone, instanceId, metadata)
			.setOauthToken(this.getOAuthToken()).execute();
	}
	
	/**
	 * Create VM instances, optionally block until all are created. If any fails then the returned flag is false
	 * 
	 *  body = {
    'name': NEW_INSTANCE_NAME,
    'machineType': <fully-qualified-machine-type-url>,
    'networkInterfaces': [{
      'accessConfigs': [{
        'type': 'ONE_TO_ONE_NAT',
        'name': 'External NAT'
       }],
      'network': <fully-qualified-network-url>
    }],
    'disk': [{
       'autoDelete': 'true',
       'boot': 'true',
       'type': 'PERSISTENT',
       'initializeParams': {
          'diskName': 'my-root-disk',
          'sourceImage': '<fully-qualified-image-url>',
       }
     }]
  }
	 * @param config
	 * @param block
	 * @throws IOException
	 */
	@Override
	public boolean startInstance(List<VMConfig> configs, boolean block) throws IOException {

		for(VMConfig config: configs) {
			log.info("Creating VM for config: " + config);
			String zoneId = config.getZoneId();
			zoneId = zoneId != null ? zoneId : this.zone;
			Instance content = new Instance();

			// Instance config
			content.setMachineType(RESOURCE_BASE_URL + 
					this.projectId + ZONES + zoneId + MACHINE_TYPES+ config.getVmType());
			content.setName(config.getInstanceId());
			//content.setZone(zoneId);
			content.setMetadata(this.getMetaData(config.getMetaData()));

			// service account
			ServiceAccount sa = new ServiceAccount();
			sa.setEmail(this.serviceAccount).setScopes(this.scopes);
			content.setServiceAccounts(Lists.newArrayList(sa));

			// network
			NetworkInterface inf = new NetworkInterface(); 
			inf.setNetwork(RESOURCE_BASE_URL + 
					this.projectId + NETWORK + config.getNetworkId());
			AccessConfig accessConf = new AccessConfig();
			accessConf.setType(ONE_TO_ONE_NAT).setName(EXT_NAT);
			inf.setAccessConfigs(Lists.newArrayList(accessConf));
			content.setNetworkInterfaces(Lists.newArrayList(inf));

			// scheduling
			Scheduling scheduling = new Scheduling();
			scheduling.setAutomaticRestart(false);
			scheduling.setOnHostMaintenance(MIGRATE);
			content.setScheduling(scheduling);

			// Disk
			AttachedDisk disk = new AttachedDisk();

			AttachedDiskInitializeParams params = new AttachedDiskInitializeParams();
			params.setDiskName(config.getInstanceId());
			params.setSourceImage(RESOURCE_BASE_URL + config.getImageId());

			disk.setAutoDelete(true).setBoot(true)
			.setDeviceName(config.getInstanceId())
			.setType(PERSISTENT)
			.setInitializeParams(params);

			content.setDisks(Lists.newArrayList(disk));

			Insert insert = this.getCompute().instances()
					.insert(this.projectId, zoneId, content)
					.setOauthToken(this.getOAuthToken());

			Operation operation = insert.execute();
			log.info("Successuflly initiated operation: " + operation);
		}
		
		boolean success = true;

		// we have to block until all instances are provisioned
		if(block) {
			for(VMConfig config: configs) {
				String status = InstanceStatus.PROVISIONING.toString();
				do {
					try {
						// sleep for 10 seconds before checking the vm status
						TimeUnit.SECONDS.sleep(10);
					} catch (InterruptedException e) {
						log.log(Level.WARNING, "thread interrupted!", e);
					}
					String zoneId = config.getZoneId();
					zoneId = zoneId != null ? zoneId : this.zone;
					// check the instance status
					Instance instance = this.getInstance(config.getInstanceId(), zoneId);
					status = instance.getStatus();
					log.info(config.getInstanceId() + ", current status is: " + status);

				} while (InstanceStatus.IN_PROGRESS.contains(status));

				if(InstanceStatus.TERMINATED.equals(status)) {
					success = false;
				}
			}
		}
		
		return success;
	}
	
	/**
	 * Delete the VMs provided in this config
	 * @param configs
	 * @throws IOException 
	 */
	@Override
	public void shutdownInstance(List<VMConfig> configs) throws IOException {
		for(VMConfig config: configs) {
			log.info("Deleting VM: " + config);
			String zoneId = config.getZoneId();
			zoneId = zoneId != null ? zoneId : this.zone;
			this.getCompute().instances().delete(this.projectId, zoneId, config.getInstanceId())
				.setOauthToken(this.getOAuthToken()).execute();
		}
	}
	
	/**
	 * Create an API Metadata
	 * @param vmMetaData
	 * @return
	 */
	private Metadata getMetaData(VMMetaData vmMetaData) {
		Metadata metadata = new Metadata();
		
		Items item;
		List<Items> items = new ArrayList<>();
		for(Entry<String, Object> entry: vmMetaData.getAttributes().entrySet()) {
			item = new Items();
			item.setKey(entry.getKey()).setValue((String) (entry.getValue()));
			items.add(item);
		}
		metadata.setItems(items);
		
		return metadata;
	}


	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return new ToStringBuilder(this).
				append("projectId", this.projectId).
				append("instanceId", this.instanceId).
				append("zone", this.zone).
				append("token", this.accessToken).
				append("tokenExpire", this.tokenExpire).
				toString();
	}


	/**
	 * @param accessToken the accessToken to set
	 */
	protected void setAccessToken(String accessToken) {
		this.accessToken = accessToken;
	}


	/**
	 * @param projectId the projectId to set
	 */
	protected void setProjectId(String projectId) {
		this.projectId = projectId;
	}


	/**
	 * @param tokenExpire the tokenExpire to set
	 */
	protected void setTokenExpire(Date tokenExpire) {
		this.tokenExpire = tokenExpire;
	}


	/**
	 * @param zone the zone to set
	 */
	protected void setZone(String zone) {
		this.zone = zone;
	}


	/**
	 * @param serviceAccount the serviceAccount to set
	 */
	protected void setServiceAccount(String serviceAccount) {
		this.serviceAccount = serviceAccount;
	}


	/**
	 * @param scopes the scopes to set
	 */
	protected void setScopes(List<String> scopes) {
		this.scopes = scopes;
	}

	
}
