/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 *     http://duracloud.org/license/
 */
package org.duracloud.mill.ltp;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;

import org.duracloud.mill.common.storageprovider.StorageProviderFactory;
import org.duracloud.mill.credentials.CredentialsRepo;
import org.duracloud.mill.credentials.CredentialsRepoException;
import org.duracloud.mill.credentials.StorageProviderCredentials;
import org.duracloud.mill.domain.DuplicationTask;
import org.duracloud.mill.domain.Task;
import org.duracloud.mill.dup.DuplicationPolicy;
import org.duracloud.mill.dup.DuplicationPolicyManager;
import org.duracloud.mill.dup.DuplicationStorePolicy;
import org.duracloud.mill.queue.TaskQueue;
import org.duracloud.storage.error.NotFoundException;
import org.duracloud.storage.provider.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for filling the duplication <code>TaskQueue</code>
 * by looping through all duplication policies for all accounts and spaces and
 * blindly creates duplication tasks. It will create tasks for all items in the
 * the source destination as well as all items in the destination provider but
 * not in the source provider. A notable feature of this task producer is that
 * it attempts to respect a designated maximum task queue size. Once the limit
 * has been reached, the producer will stop. On subsequent runs, the producer
 * will pick up where it left off, starting with the next account,space,set of
 * content items, and duplication store policy. If all content items are visited 
 * within a single run before the task queue limit has been reached, the producer
 * will exit.
 * 
 * @author Daniel Bernstein 
 *         Date: Nov 5, 2013
 */
public class LoopingTaskProducer implements Runnable {
    private static Logger log = LoggerFactory.getLogger(LoopingTaskProducer.class);
    private DuplicationPolicyManager policyManager;
    private TaskQueue taskQueue;
    private CredentialsRepo credentialsRepo;
    private Cache cache;
    private StateManager stateManager;
    private int maxTaskQueueSize;
    private Set<Task> queuedTasks = new HashSet<>();
    private StorageProviderFactory storageProviderFactory;
    private List<Morsel> morselsToReload = new LinkedList<>();
    
    public LoopingTaskProducer(CredentialsRepo credentialsRepo,
                               StorageProviderFactory storageProviderFactory,
                               DuplicationPolicyManager policyManager, 
                               TaskQueue taskQueue,
                               Cache cache, 
                               StateManager state,
                               int maxTaskQueueSize) {
        
        this.credentialsRepo = credentialsRepo;
        this.storageProviderFactory = storageProviderFactory;
        this.policyManager = policyManager;
        this.taskQueue = taskQueue;
        this.cache = cache;
        this.stateManager = state;
        this.credentialsRepo = credentialsRepo;
        this.maxTaskQueueSize = maxTaskQueueSize;
    }
    
    public void run(){
        log.info("Starting run...");
        MorselQueue morselQueue = loadMorselQueue();
        
        while(this.taskQueue.size() < maxTaskQueueSize){
            if(morselQueue.isEmpty()){
                morselQueue = reloadMorselQueue();
                if(morselQueue.isEmpty()){
                    break;
                }
            }
            
            nibble(morselQueue.poll());
            persistMorsels(morselQueue, morselsToReload);
        }
    }

    /**
     * @return
     */
    private MorselQueue reloadMorselQueue() {
        List<Morsel> morsels = morselsToReload;
        morselsToReload = new LinkedList<>();
        MorselQueue queue = new MorselQueue();
        queue.addAll(morsels);
        return queue;
    }

    /**
     * load the morsels from the persistent state and then add all other morsels based on
     * on duplication policy manager
     * 
     * @return
     */
    private MorselQueue loadMorselQueue() {
        MorselQueue morselQueue = new MorselQueue();
        
        //load morsels from state;
        Set<Morsel> morsels = new HashSet<>(this.stateManager.getMorsels());

        //generate set of morsels based on duplication policy
        for(String account : this.policyManager.getDuplicationAccounts()){
            DuplicationPolicy policy = this.policyManager.getDuplicationPolicy(account);
            for(String spaceId : policy.getSpaces()){
                Set<DuplicationStorePolicy> storePolicies = policy.getDuplicationStorePolicies(spaceId);
                for(DuplicationStorePolicy storePolicy : storePolicies){
                    morselQueue.add(new Morsel(account, spaceId, null, storePolicy));
                }
            }
        }
        
        morselQueue.addAll(morsels);
        
        return morselQueue;
    }
    
    
    private void persistMorsels(MorselQueue queue, List<Morsel> morselsToReload){
        Set<Morsel> morsels = new HashSet<>();
        morsels.addAll(queue);
        morsels.addAll(morselsToReload);
        stateManager.setMorsels(morsels);
    }

    /**
     * @param morsel
     */
    private void nibble(Morsel morsel) {
        
        String subdomain = morsel.getSubdomain();
        String spaceId = morsel.getSpaceId();
        String marker = morsel.getMarker();
        DuplicationStorePolicy storePolicy = morsel.getStorePolicy();
        
        //get all items from source
        StorageProvider sourceProvider = 
                getStorageProvider(subdomain, 
                                   storePolicy.getSrcStoreId());
        StorageProvider destProvider = 
                getStorageProvider(subdomain, 
                                   storePolicy.getDestStoreId());

        //only perform deletes at the beginning of the space run.
        if(marker == null){
            addDuplicationTasksForContentNotInSource(subdomain,
                                                        spaceId, 
                                                        storePolicy, 
                                                        sourceProvider, 
                                                        destProvider);
        }
        
        int taskQueueSize = taskQueue.size();
        if(taskQueueSize >= maxTaskQueueSize){
            log.info(
                    "Task queue size ({}) has reached or exceeded max size ({}).",
                    taskQueueSize, maxTaskQueueSize);
        }
        
        if(addDuplicationTasksFromSource(morsel, 
                                          sourceProvider,
                                          1000)){
            log.info(
                    "All tasks that could be created were created for subdomain={}, spaceId={}, storePolicy={}. Taskqueue.size = {}",
                    subdomain, spaceId, storePolicy, taskQueue.size());
        }else{
            morselsToReload.add(morsel);
        }
    }




    /**
     * 
     * @param morsel
     * @param sourceProvider
     * @param maxContentIdsToAdd
     * @return true if morsel exhausted.
     */
    private boolean addDuplicationTasksFromSource(Morsel morsel, StorageProvider sourceProvider, int maxContentIdsToAdd) {

        String subdomain = morsel.getSubdomain();
        String spaceId = morsel.getSpaceId();
        String marker = morsel.getMarker();
        DuplicationStorePolicy storePolicy = morsel.getStorePolicy();

        //load in next maxContentIdsToAdd or however many remain 
        List<String> contentIds = sourceProvider.getSpaceContentsChunked(spaceId, 
                                                                         null, 
                                                                         maxContentIdsToAdd, 
                                                                         marker);
        //add to queue
        int contentIdCount = contentIds.size();
        
        
        if(contentIdCount > 0){
            int added = addToTaskQueue(subdomain, spaceId, storePolicy,
                    contentIds);

            //if no tasks were added, it means that this morsel has been
            //fully consumed in this run.
            if(added == 0){
                return true;
            }
            
            marker = contentIds.get(contentIds.size()-1);
            morsel.setMarker(marker);
        } else {
            morsel.setMarker(null);
        }
        

        return false;
    }

    private void addDuplicationTasksForContentNotInSource(
            String subdomain, String spaceId,
            DuplicationStorePolicy storePolicy, StorageProvider sourceProvider,
            StorageProvider destProvider) {

        //load all source into ehcache
        Iterator<String> sourceContentIds = sourceProvider.getSpaceContents(spaceId, null);
        while(sourceContentIds.hasNext()){
            cache.put(new Element(sourceContentIds.next(), null));
        }
        
        //get all items from dest
        Iterator<String> destContentIds = null;
        
        try{
            destContentIds = destProvider.getSpaceContents(spaceId, null);
        }catch(NotFoundException ex){
            log.info("space not found on destination provider: " +
                     "subdomain={}, spaceId={}, storeId={}",
                     subdomain, spaceId, destProvider);
            return;
        }
        
        int deletionTaskCount = 0;
        //for each one 
        List<String> deletions = new LinkedList<String>();
        while(destContentIds.hasNext()){
            String destContentId = destContentIds.next();
            //if not in cache
            if(null == cache.get(destContentId)){
                deletions.add(destContentId);
                //periodically add deletions to prevent OOM
                //in case that there are millions of content ids to delete
                if(deletions.size() == 10000){
                    //create dup task
                    deletionTaskCount += addToTaskQueue(subdomain, spaceId, storePolicy, deletions);
                    deletions = new LinkedList<String>();
                }
            }
        }

        //add any remaining deletions
        deletionTaskCount += addToTaskQueue(subdomain, spaceId, storePolicy, deletions);
        log.info(
                "added {} deletion tasks: subdomain={}, spaceId={}, sourceStoreId={}, destStoreId={}",
                deletionTaskCount, 
                subdomain, 
                spaceId, 
                storePolicy.getSrcStoreId(),
                storePolicy.getDestStoreId());
        cache.removeAll();
    }

    private int addToTaskQueue(String subdomain, String spaceId,
            DuplicationStorePolicy storePolicy, List<String> contentIds) {
        Set<Task> tasks = new HashSet<>();
        int addedCount = 0;
        
        for(String contentId : contentIds){
            DuplicationTask dupTask = new DuplicationTask();
            dupTask.setAccount(subdomain);
            dupTask.setContentId(contentId);
            dupTask.setSpaceId(spaceId);
            dupTask.setStoreId(storePolicy.getSrcStoreId());
            dupTask.setSourceStoreId(storePolicy.getSrcStoreId());
            dupTask.setDestStoreId(storePolicy.getDestStoreId());
            
            Task task = dupTask.writeTask();
            if(queuedTasks.contains(task)){
                continue;
            }
            
            tasks.add(task);

            queuedTasks.add(task);
            addedCount++;

            if(tasks.size() == 10){
                taskQueue.put(tasks);
                tasks = new HashSet<>();
            }
        }

        if(tasks.size() > 0){
            taskQueue.put(tasks);
        }
        
        return addedCount;
    }

    private StorageProvider getStorageProvider(String subdomain,
            String storeId)  {
        StorageProviderCredentials creds;
        try {
            creds = credentialsRepo.getStorageProviderCredentials(subdomain, 
                                                          storeId);
        } catch (CredentialsRepoException e) {
            throw new RuntimeException(e);
        }
        
        return storageProviderFactory.create(creds);
    }
}
