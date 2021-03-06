package io.ecarf.core.cloud.task.processor.analyze;

import io.cloudex.framework.cloud.api.CloudService;
import io.ecarf.core.cloud.impl.google.EcarfGoogleCloudService;
import io.ecarf.core.compress.NxGzipProcessor;
import io.ecarf.core.compress.callback.ExtractTermsTreeCallback;
import io.ecarf.core.term.TermCounter;
import io.ecarf.core.term.TermRoot;
import io.ecarf.core.utils.FilenameUtils;
import io.ecarf.core.utils.Utils;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Callable;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.base.Stopwatch;

/**
 * 1- Download a gziped N-Triple file from cloud storage
 * 2- Process the file in memory, extract and blank nodes and build the resources tree and count the literals
 * 3- Serialize the blank nodes and resource tree and upload to cloud storage
 * 
 * @author Omer Dawelbeit (omerio)
 *
 */
public class ExtractCountTermsTreeSubTask implements Callable<TermCounter> {

    private final static Log log = LogFactory.getLog(ExtractCountTermsTreeSubTask.class);

    private String file;
    private EcarfGoogleCloudService cloud;
    private TermCounter counter;
    private String bucket;
    private String sourceBucket;

    public ExtractCountTermsTreeSubTask(String file, String bucket, String sourceBucket, TermCounter counter, CloudService cloud) {
        super();
        this.file = file;
        this.cloud = (EcarfGoogleCloudService) cloud;
        this.counter = counter;
        this.bucket = bucket;
        this.sourceBucket = sourceBucket;

    }

    @Override
    public TermCounter call() throws IOException {
        
        String localFile = FilenameUtils.getLocalFilePath(file);

        log.info("START: Downloading file: " + file + ", memory usage: " + Utils.getMemoryUsageInGB() + "GB");
        Stopwatch stopwatch = Stopwatch.createStarted();

        try {

            this.cloud.downloadObjectFromCloudStorage(file, localFile, this.sourceBucket);

            // all downloaded, carryon now, process the files

            log.info("Processing file: " + localFile + ", memory usage: " + Utils.getMemoryUsageInGB() + "GB" + ", timer: " + stopwatch);

            NxGzipProcessor processor = new NxGzipProcessor(localFile);
            ExtractTermsTreeCallback callback = new ExtractTermsTreeCallback();
            callback.setCounter(counter);
            processor.read(callback);
            
            Set<String> blankNodes = callback.getBlankNodes();
            TermRoot resources = callback.getRoot();

            // once the processing is done then delete the local file
            //FileUtils.deleteFile(localFile);

            log.info("TIMER# Finished processing file: " + localFile + ", memory usage: " + Utils.getMemoryUsageInGB() + "GB" + ", timer: " + stopwatch);
            log.info("Number of resource URIs: " + callback.getResourceCount());
            log.info("Number of top level domains: " + resources.size());
            log.info("Number of blank nodes: " + callback.getBlankNodes().size());
            log.info("Number of literals: " + callback.getLiteralCount());
            
            callback = null;
            processor = null;
            
            // upload the terms tree
            String termsFile = FilenameUtils.getLocalSerializedGZipedFilePath(file, false);
            this.serializeAndUploadObject(termsFile, resources, stopwatch);
            
            // upload the blank nodes
            if(!blankNodes.isEmpty()) {
                String blankNodesFile = FilenameUtils.getLocalSerializedGZipedBNFilePath(file, false);
                this.serializeAndUploadObject(blankNodesFile, blankNodes, stopwatch);
            }
            
            blankNodes = null;
            resources = null;

        } catch(Exception e) {
            // because this sub task is run in an executor the exception will be stored and thrown in the
            // future, but we want to know about it now, so log it
            log.error("Failed to download or process file: " + file, e);

            if(e instanceof IOException) {
                throw e;
            } else {
                throw new IOException(e);
            }
            
        } catch(Error e) {
            
            log.fatal("JVM Died: ", e);
            throw e;
        }

        return counter;
    }
    
    /**
     * Serialize the provided object and upload the file to cloud storage
     * @param file
     * @param object
     * @param stopwatch
     * @throws IOException
     */
    private void serializeAndUploadObject(String file, Object object, Stopwatch stopwatch) throws IOException {
        Utils.objectToFile(file, object, true, false);
        
        log.info("Serialized file: " + file + ", memory usage: " + Utils.getMemoryUsageInGB() + "GB" + ", timer: " + stopwatch);

        this.cloud.uploadFileToCloudStorage(file, bucket);

        log.info("Uploaded file: " + file + ", memory usage: " + Utils.getMemoryUsageInGB() + "GB" + ", timer: " + stopwatch);
    }

}