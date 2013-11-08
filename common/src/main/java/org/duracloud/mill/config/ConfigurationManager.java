/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 *     http://duracloud.org/license/
 */
package org.duracloud.mill.config;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.duracloud.mill.util.SystemPropertyLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Daniel Bernstein
 *	       Date: Oct 25, 2013
 */
public class ConfigurationManager {
    
    private static Logger log = LoggerFactory
            .getLogger(ConfigurationManager.class);

    public static final String DUPLICATION_QUEUE_KEY = "duplicationQueue";
    public static final String CREDENTIALS_FILE_PATH_KEY = "credentialsFilePath";
    public static final String DURACLOUD_MILL_CONFIG_FILE_KEY = "duracloud.mill.configFile";
    private Set<String> requiredProperties = new HashSet<>();
    
    public String getCredentialsFilePath() {
        return System.getProperty(CREDENTIALS_FILE_PATH_KEY);
    }

    public String getDuplicationQueueName() {
        return System.getProperty(DUPLICATION_QUEUE_KEY);
    }
    
    protected void addRequiredProperties(){
        addRequiredProperty(DUPLICATION_QUEUE_KEY);
    }

    /**
     * @param duplicationQueueKey
     */
    protected void addRequiredProperty(String duplicationQueueKey) {
        this.requiredProperties.add(DUPLICATION_QUEUE_KEY);
    }

    public void init() {
        String defaultConfigFile = System.getProperty("user.home")
                + File.separator + "duracloud.mill.properties";
        String configPath = System.getProperty(
                DURACLOUD_MILL_CONFIG_FILE_KEY, defaultConfigFile);

        if(new File(configPath).exists()){
            try {
                SystemPropertyLoader.load(configPath);
            } catch (IOException e) {
                log.warn(e.getMessage(),e);
                throw new RuntimeException(e);
            }
        }else{
            log.warn("config file not found - ignorning: {}", configPath);
        }
        
        addRequiredProperties();
        verifyRequiredProperties();

    }

    /**
     * 
     */
    protected void verifyRequiredProperties() {
        boolean allgood = true;
        
        for(String prop : this.requiredProperties){
            if(System.getProperty(prop) == null){
                allgood = false;
                log.error("{} is a required system property and it is missing");
            }
        }
        
        if(!allgood){
            throw new RuntimeException("required system properties are missing.");
        }
    }
}
