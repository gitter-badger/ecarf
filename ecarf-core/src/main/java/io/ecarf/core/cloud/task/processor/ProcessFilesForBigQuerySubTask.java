package io.ecarf.core.cloud.task.processor;

import io.cloudex.framework.cloud.api.CloudService;
import io.cloudex.framework.utils.FileUtils;
import io.ecarf.core.cloud.impl.google.EcarfGoogleCloudService;
import io.ecarf.core.term.TermCounter;
import io.ecarf.core.utils.Utils;

import java.io.IOException;
import java.util.concurrent.Callable;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * 
 * @author Omer Dawelbeit (omerio)
 *
 */
public class ProcessFilesForBigQuerySubTask implements Callable<TermCounter> {
	
	private final static Log log = LogFactory.getLog(ProcessFilesForBigQuerySubTask.class);

	private String file;
	private EcarfGoogleCloudService cloud;
	private TermCounter counter;
	private String bucket;
	private String sourceBucket;
	private boolean countOnly;

	public ProcessFilesForBigQuerySubTask(String file, String bucket, String sourceBucket, TermCounter counter, boolean countOnly, CloudService cloud) {
		super();
		this.file = file;
		this.cloud = (EcarfGoogleCloudService) cloud;
		this.counter = counter;
		this.bucket = bucket;
		this.sourceBucket = sourceBucket;
	}

	@Override
	public TermCounter call() throws IOException {
	    
	    log.info("Processing file for BigQuery import: " + file);

		String localFile = Utils.TEMP_FOLDER + file;

		//log.info("Downloading file: " + file);

		this.cloud.downloadObjectFromCloudStorage(file, localFile, sourceBucket);

		// all downloaded, carryon now, process the files
		log.info("Processing file: " + localFile + ", countOnly = " + countOnly);
		
		String outFile = this.cloud.prepareForBigQueryImport(localFile, counter, countOnly);

		// once the processing is done then delete the local file
		FileUtils.deleteFile(localFile);

		// if we are not just counting then upload the output files
		if(!countOnly) {
		    // now upload the files again
		    log.info("Uploading file: " + outFile);
		    this.cloud.uploadFileToCloudStorage(outFile, bucket);

		    // now delete all the locally processed files
		    FileUtils.deleteFile(outFile);
		}

		return counter;
	}


}