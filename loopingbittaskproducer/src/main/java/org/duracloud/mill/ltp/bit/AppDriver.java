/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 *     http://duracloud.org/license/
 */
package org.duracloud.mill.ltp.bit;

import java.io.File;

import org.apache.commons.cli.CommandLine;
import org.duracloud.common.error.DuraCloudRuntimeException;
import org.duracloud.common.queue.TaskQueue;
import org.duracloud.common.queue.aws.SQSTaskQueue;
import org.duracloud.mill.common.storageprovider.StorageProviderFactory;
import org.duracloud.mill.credentials.CredentialsRepo;
import org.duracloud.mill.credentials.file.ConfigFileCredentialRepo;
import org.duracloud.mill.credentials.impl.CredentialsRepoLocator;
import org.duracloud.mill.ltp.Frequency;
import org.duracloud.mill.ltp.LoopingTaskProducerDriverSupport;
import org.duracloud.mill.ltp.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A main class responsible for parsing command line arguments and launching the
 * Looping Task Producer.
 * 
 * @author Daniel Bernstein Date: Nov 4, 2013
 */
public class AppDriver extends LoopingTaskProducerDriverSupport {
    private static Logger log = LoggerFactory.getLogger(AppDriver.class);

    /**
     * 
     */
    public AppDriver() {
        super(new LoopingBitIntegrityTaskProducerCommandLineOptions());
    }

    public static void main(String[] args) {
        new AppDriver().execute(args);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.duracloud.mill.ltp.DriverSupport#executeImpl(org.apache.commons.cli
     * .CommandLine)
     */
    @Override
    protected void executeImpl(CommandLine cmd) {
        super.executeImpl(cmd);

        processTaskQueueNameOption(cmd);
        processInclusionListOption(cmd);
        processExclusionListOption(cmd);

        try {

            LoopingBitIntegrityTaskProducer producer = buildTaskProducer(cmd,
                                                                        maxTaskQueueSize, 
                                                                        stateFilePath, 
                                                                        frequency);
            producer.run();

        } catch (Exception ex) {
            ex.printStackTrace();
            log.error(ex.getMessage(), ex);
            System.exit(1);
        }

        log.info("looping task producer completed successfully.");
        System.exit(0);

    }
    
    /**
     * @param cmd
     */
    private void processExclusionListOption(CommandLine cmd) {
        String exclusionList = cmd
                .getOptionValue(LoopingBitIntegrityTaskProducerCommandLineOptions.EXCLUSION_LIST_OPTION);
        if (exclusionList != null) {
            File list = new File(exclusionList);
            if(!list.exists()){
                throw new DuraCloudRuntimeException("exclusion list not found: " + list);
            }
            
            
            setSystemProperty(
                    LoopingBitTaskProducerConfigurationManager.EXCLUSION_LIST,
                    exclusionList);
        }
    }
    
    
    /**
     * @param cmd
     */
    private void processInclusionListOption(CommandLine cmd) {
        String inclusionList = cmd
                .getOptionValue(LoopingBitIntegrityTaskProducerCommandLineOptions.INCLUSION_LIST_OPTION);
        if (inclusionList != null) {
            File list = new File(inclusionList);
            if(!list.exists()){
                throw new DuraCloudRuntimeException("inclusionlist not found: " + list);
            }
            
            
            setSystemProperty(
                    LoopingBitTaskProducerConfigurationManager.INCLUSION_LIST,
                    inclusionList);
        }
    }


    /**
     * @param cmd
     * @param maxTaskQueueSize
     * @param stateFilePath
     * @param frequency
     * @return
     */
    private LoopingBitIntegrityTaskProducer buildTaskProducer(CommandLine cmd,
            int maxTaskQueueSize,
            String stateFilePath,
            Frequency frequency) {

        LoopingBitTaskProducerConfigurationManager config = new LoopingBitTaskProducerConfigurationManager();
        config.init();

        CredentialsRepo credentialsRepo;

        if (config.getCredentialsFilePath() != null) {
            credentialsRepo = new ConfigFileCredentialRepo(
                    config.getCredentialsFilePath());
        } else {
            credentialsRepo = CredentialsRepoLocator.get();
        }

        StorageProviderFactory storageProviderFactory = new StorageProviderFactory();


        TaskQueue taskQueue = new SQSTaskQueue(
                config.getOutputQueue());


        StateManager<BitIntegrityMorsel> stateManager = new StateManager<BitIntegrityMorsel>(
                stateFilePath, BitIntegrityMorsel.class);

        LoopingBitIntegrityTaskProducer producer = new LoopingBitIntegrityTaskProducer(credentialsRepo,
                                                                                       storageProviderFactory,
                                                                                       taskQueue,
                                                                                       stateManager,
                                                                                       maxTaskQueueSize,
                                                                                       frequency,
                                                                                       config.getPathFilterManager());
        return producer;
    }
    
 

}
