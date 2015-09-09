package io.ecarf.core.cloud.task.processor.analyze;

import io.cloudex.framework.cloud.api.CloudService;
import io.ecarf.core.cloud.impl.google.EcarfGoogleCloudService;
import io.ecarf.core.compress.NTripleGzipProcessor;
import io.ecarf.core.compress.callback.ExtractTermsCallback;
import io.ecarf.core.term.TermCounter;
import io.ecarf.core.utils.Utils;

import java.io.IOException;
import java.util.concurrent.Callable;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.base.Stopwatch;

/**
 * 
 * @author Omer Dawelbeit (omerio)
 *
 */
public class ExtractAndCountTermsSubTask implements Callable<TermCounter> {
	
	private final static Log log = LogFactory.getLog(ExtractAndCountTermsSubTask.class);

	private String file;
	private EcarfGoogleCloudService cloud;
	private TermCounter counter;
	private String bucket;

	public ExtractAndCountTermsSubTask(String file, String bucket, TermCounter counter, CloudService cloud) {
		super();
		this.file = file;
		this.cloud = (EcarfGoogleCloudService) cloud;
		this.counter = counter;
		this.bucket = bucket;

	}

	@Override
	public TermCounter call() throws IOException {

		String localFile = Utils.TEMP_FOLDER + file;

		log.info("START: Downloading file: " + file);
		Stopwatch stopwatch = Stopwatch.createStarted();

		this.cloud.downloadObjectFromCloudStorage(file, localFile, bucket);

		// all downloaded, carryon now, process the files

		log.info("Processing file: " + localFile);
		//String outFile = this.cloud.prepareForBigQueryImport(localFile, counter);
		
        NTripleGzipProcessor processor = new NTripleGzipProcessor(localFile);

        ExtractTermsCallback callback = new ExtractTermsCallback();

        callback.setCounter(counter);

        processor.read(callback);
        
        //Set<String> resources = callback.getResources();
        counter.getAllTerms().addAll(callback.getResources());
        counter.getAllTerms().addAll(callback.getBlankNodes());

		// once the processing is done then delete the local file
		//FileUtils.deleteFile(localFile);

		log.info("TIMER# Finished processing file: " + localFile + ", in: " + stopwatch);
		log.info("Number of unique URIs: " + callback.getResources().size());
        log.info("Number of blank nodes: " + callback.getBlankNodes().size());
        log.info("Number of literals: " + callback.getLiteralCount());

		return counter;
	}

}