// Copyright (c) 2017, Baidu.com, Inc. All Rights Reserved

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.baidu.palo.clone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Database;
import com.baidu.palo.catalog.MaterializedIndex;
import com.baidu.palo.catalog.OlapTable;
import com.baidu.palo.catalog.Partition;
import com.baidu.palo.catalog.Replica;
import com.baidu.palo.catalog.Replica.ReplicaState;
import com.baidu.palo.catalog.Tablet;
import com.baidu.palo.clone.CloneJob.JobPriority;
import com.baidu.palo.clone.CloneJob.JobState;
import com.baidu.palo.clone.CloneJob.JobType;
import com.baidu.palo.common.Config;
import com.baidu.palo.common.MetaNotFoundException;
import com.baidu.palo.common.util.ListComparator;
import com.baidu.palo.common.util.TimeUtils;
import com.baidu.palo.persist.ReplicaPersistInfo;
import com.baidu.palo.task.AgentTaskQueue;
import com.baidu.palo.task.CloneTask;
import com.baidu.palo.thrift.TTabletInfo;
import com.baidu.palo.thrift.TTaskType;

public class Clone {
    private static final Logger LOG = LogManager.getLogger(Clone.class);

    // priority to Map<tabletId, CloneJob> 
    private Map<JobPriority, Map<Long, CloneJob>> priorityToCloneJobs;
    // job num: pending + running 
    private int jobNum;
    // lock for clone job
    // lock is private and must use after db lock
    private ReentrantReadWriteLock lock;
 
    public Clone() {
        // init clone jobs
        priorityToCloneJobs = new HashMap<JobPriority, Map<Long, CloneJob>>();
        for (JobPriority priority : JobPriority.values()) {
            Map<Long, CloneJob> cloneJobs = new LinkedHashMap<Long, CloneJob>();
            priorityToCloneJobs.put(priority, cloneJobs);
        }
        jobNum = 0;
        lock = new ReentrantReadWriteLock(true);
    }

    private void readLock() {
        lock.readLock().lock();
    }

    private void readUnlock() {
        lock.readLock().unlock();
    }

    private void writeLock() {
        lock.writeLock().lock();
    }

    private void writeUnlock() {
        lock.writeLock().unlock();
    }
   
    /**
     * add clone job
     * @return true if add new job else false
     */
    public boolean addCloneJob(long dbId, long tableId, long partitionId, long indexId, long tabletId,
                               long destBackendId, JobType type, JobPriority priority, long timeoutSecond) {
        writeLock();
        try {
            // check priority map
            Map<Long, CloneJob> cloneJobs = priorityToCloneJobs.get(priority);
            if (cloneJobs.containsKey(tabletId)) {
                return false;
            } 
            
            // check other priority map
            CloneJob job = null;
            if (priority == JobPriority.NORMAL) {
                job = priorityToCloneJobs.get(JobPriority.LOW).remove(tabletId);
            } else if (priority == JobPriority.LOW) {
                job = priorityToCloneJobs.get(JobPriority.NORMAL).remove(tabletId);
            } else if (priority == JobPriority.HIGH) {
                job = priorityToCloneJobs.get(JobPriority.HIGH).remove(tabletId);
            }
            if (job != null) {
                job.setPriority(priority);
                cloneJobs.put(tabletId, job);
                return false;
            }
            
            // check job num
            if (jobNum >= Config.clone_max_job_num && priority != JobPriority.NORMAL && priority != JobPriority.HIGH) {
                LOG.debug("too many clone jobs. job num: {}", jobNum);
                return false;
            }
            
            // add job
            job = new CloneJob(dbId, tableId, partitionId, indexId, tabletId, destBackendId,
                               type, priority, timeoutSecond);
            cloneJobs.put(tabletId, job);
            ++jobNum;
            LOG.info("add clone job. job: {}, job num: {}", job, jobNum);
            return true;
        } finally {
            writeUnlock();
        }
    }
 
    public int getJobNum() {
        readLock();
        try {
            return jobNum;
        } finally {
            readUnlock();
        }
    }
    
    public Set<Long> getCloneTabletIds() {
        Set<Long> cloneTabletIds = new HashSet<Long>();
        readLock();
        try {
            for (Map<Long, CloneJob> cloneJobs : priorityToCloneJobs.values()) {
                cloneTabletIds.addAll(cloneJobs.keySet());
            }
            return cloneTabletIds;
        } finally {
            readUnlock();
        }
    }
    
    public boolean containsTablet(long tabletId) {
        readLock();
        try {
            for (Map<Long, CloneJob> cloneJobs : priorityToCloneJobs.values()) {
                if (cloneJobs.containsKey(tabletId)) {
                    return true;
                }
            }
            return false;
        } finally {
            readUnlock();
        }
    }

    /**
     * get state clone jobs order by priority
     */
    public List<CloneJob> getCloneJobs(JobState state) {
        List<CloneJob> cloneJobs = new ArrayList<CloneJob>();
        readLock();
        try {
            for (CloneJob job : priorityToCloneJobs.get(JobPriority.HIGH).values()) {
                if (job.getState() == state) {
                    cloneJobs.add(job);
                }
            }
            for (CloneJob job : priorityToCloneJobs.get(JobPriority.NORMAL).values()) {
                if (job.getState() == state) {
                    cloneJobs.add(job);
                }
            }
            for (CloneJob job : priorityToCloneJobs.get(JobPriority.LOW).values()) {
                if (job.getState() == state) {
                    cloneJobs.add(job);
                }
            }
            return cloneJobs;
        } finally {
            readUnlock();
        }
    }
    
    /**
     * get state clone jobs'num
     */
    public int getCloneJobNum(JobState state, long dbId) {
        int jobNum = 0;
        readLock();
        try {
            for (CloneJob job : priorityToCloneJobs.get(JobPriority.HIGH).values()) {
                if (job.getState() == state && job.getDbId() == dbId) {
                    ++jobNum;
                }
            }
            for (CloneJob job : priorityToCloneJobs.get(JobPriority.NORMAL).values()) {
                if (job.getState() == state && job.getDbId() == dbId) {
                    ++jobNum;
                }
            }
            for (CloneJob job : priorityToCloneJobs.get(JobPriority.LOW).values()) {
                if (job.getState() == state && job.getDbId() == dbId) {
                    ++jobNum;
                }
            }
            return jobNum;
        } finally {
            readUnlock();
        }
    }

    /**
     * get clone jobs for proc
     */
    public List<List<Comparable>> getCloneJobInfosByDb(Database db) {
        List<List<Comparable>> cloneJobInfos = new ArrayList<List<Comparable>>();
        long dbId = db.getId();
        readLock();
        try {
            for (Map<Long, CloneJob> cloneJobs : priorityToCloneJobs.values()) {
                for (CloneJob job : cloneJobs.values()) {
                    if (job.getDbId() != dbId) {
                        continue;
                    }
                    
                    List<Comparable> jobInfo = new ArrayList<Comparable>();
                    jobInfo.add(job.getDbId());
                    jobInfo.add(job.getTableId());
                    jobInfo.add(job.getPartitionId());
                    jobInfo.add(job.getIndexId());
                    jobInfo.add(job.getTabletId());
                    jobInfo.add(job.getDestBackendId());
                    jobInfo.add(job.getState().name());
                    jobInfo.add(job.getType().name());
                    jobInfo.add(job.getPriority().name());
                    jobInfo.add(TimeUtils.longToTimeString(job.getCreateTimeMs()));
                    jobInfo.add(TimeUtils.longToTimeString(job.getCloneStartTimeMs()));
                    jobInfo.add(TimeUtils.longToTimeString(job.getCloneFinishTimeMs()));
                    jobInfo.add(job.getTimeoutMs() / 1000);
                    jobInfo.add(job.getFailMsg());
                    cloneJobInfos.add(jobInfo);
                }
            }
        } finally {
            readUnlock();
        }

        // sort by create time
        ListComparator<List<Comparable>> comparator = new ListComparator<List<Comparable>>(9);
        Collections.sort(cloneJobInfos, comparator);
        return cloneJobInfos;
    }
    
    /**
     * add task to task queue and update job running
     */
    public boolean runCloneJob(CloneJob job, CloneTask task) {
        writeLock();
        try {
            if (job.getState() != JobState.PENDING) {
                LOG.warn("clone job state is not pending. job: {}", job);
                return false;
            }
            if (AgentTaskQueue.addTask(task)) {
                job.setState(JobState.RUNNING);
                job.setCloneStartTimeMs(System.currentTimeMillis()); 
                return true;
            } else {
                return false;
            }
        } finally {
            writeUnlock();
        }
    }
    
    /**
     * check job timeout
     */
    public void checkTimeout() {
        long currentTimeMs = System.currentTimeMillis();
        writeLock();
        try {
            for (Map<Long, CloneJob> cloneJobs : priorityToCloneJobs.values()) {
                for (CloneJob job : cloneJobs.values()) {
                    JobState state = job.getState();
                    if (state == JobState.PENDING || state == JobState.RUNNING) {
                        if (currentTimeMs - job.getCreateTimeMs() > job.getTimeoutMs()) {
                            cancelCloneJob(job, "clone timeout");
                            LOG.warn("clone timeout. job: {}, src state: {}", job, state.name());
                        }
                    }
                }
            }
        } finally {
            writeUnlock();
        }
    }

    /**
     * cancel clone job by job
     */
    public void cancelCloneJob(CloneJob job, String failMsg) {
        writeLock();
        try {
            JobState state = job.getState();
            if (state != JobState.PENDING && state != JobState.RUNNING) {
                LOG.warn("clone job state is not pending or running. job: {}", job);
                return;
            }

            // remove clone task
            AgentTaskQueue.removeTask(job.getDestBackendId(), TTaskType.CLONE, job.getTabletId());

            // the cloned replica will be removed from meta when we remove clone job

            // update job state
            job.setState(JobState.CANCELLED);
            job.setFailMsg(failMsg);
        } finally {
            writeUnlock();
        }
        LOG.warn("cancel clone job. job: {}", job);
    }
    
    /**
     * cancel clone job by tabletId
     */
    public void cancelCloneJob(long tabletId, String failMsg) {
        writeLock();
        try {
            for (Map<Long, CloneJob> cloneJobs : priorityToCloneJobs.values()) {
                if (cloneJobs.containsKey(tabletId)) {
                    cancelCloneJob(cloneJobs.get(tabletId), failMsg);
                    return;
                }
            }
        } finally {
            writeUnlock();
        }
    }
    
    /**
     * cancel clone jobs in table. must use with db lock outside!!!!!!
     */
    public void cancelCloneJob(OlapTable olapTable) {
        for (Partition partition : olapTable.getPartitions()) {
            cancelCloneJob(partition);
        }
    }

    /**
     * cancel clone jobs in partition. must use with db lock outside!!!!!!
     */
    public void cancelCloneJob(Partition partition) {
        String failMsg = "partition[" + partition.getName() + "] has been dropped";
        for (MaterializedIndex materializedIndex : partition.getMaterializedIndices()) {
            for (Tablet tablet : materializedIndex.getTablets()) {
                cancelCloneJob(tablet.getId(), failMsg);
            }
        }
    }

    public void finishCloneJob(CloneTask task, TTabletInfo tabletInfo) {
        // get clone job
        long tabletId = task.getTabletId();
        CloneJob job = null;
        readLock();
        try {
            for (Map<Long, CloneJob> cloneJobs : priorityToCloneJobs.values()) {
                if (cloneJobs.containsKey(tabletId)) {
                    job = cloneJobs.get(tabletId);
                    break;
                }
            }
        } finally {
            readUnlock();
        }
        if (job == null) {
            LOG.warn("clone job does not exist. tablet id: {}", tabletId);
            return;
        }
       
        // update meta
        long dbId = task.getDbId();
        long tableId = task.getTableId();
        long partitionId = task.getPartitionId();
        long indexId = task.getIndexId();
        long backendId = task.getBackendId();
        int schemaHash = task.getSchemaHash();
        long taskVersion = task.getCommittedVersion();
        long taskVersionHash = task.getCommittedVersionHash();
        Database db = Catalog.getInstance().getDb(dbId);
        if (db == null) {
            String failMsg = "db does not exist. id: " + dbId;
            LOG.warn(failMsg);
            cancelCloneJob(job, failMsg);
            return;
        }

        db.writeLock();
        try {
            OlapTable olapTable = (OlapTable) db.getTable(tableId);
            if (olapTable == null) {
                throw new MetaNotFoundException("table does not exist. id: " + tableId);
            }

            Partition partition = olapTable.getPartition(partitionId);
            if (partition == null) {
                throw new MetaNotFoundException("partition does not exist. id: " + partitionId);
            }
            MaterializedIndex index = partition.getIndex(indexId);
            if (index == null) {
                throw new MetaNotFoundException("index does not exist. id: " + indexId);
            }
            if (schemaHash != olapTable.getSchemaHashByIndexId(indexId)) {
                throw new MetaNotFoundException("schema hash is not consistent. index's: "
                        + olapTable.getSchemaHashByIndexId(indexId)
                        + ", task's: " + schemaHash);
            }
            Tablet tablet = index.getTablet(tabletId);
            if (tablet == null) {
                throw new MetaNotFoundException("tablet does not exist. id: " + tabletId);
            }
            Replica replica = tablet.getReplicaByBackendId(backendId);
            if (replica == null) {
                throw new MetaNotFoundException("replica does not exist. tablet id: " + tabletId 
                        + ", backend id: " + backendId);
            }
            
            // Here we do not check is clone version is equal to the commited version.
            // Because in case of high frequency loading, clone version always lags behind the commited version,
            // so the clone job will never succeed, which cause accumulation of quorum finished load jobs.

            // But we will check if the cloned replica's version is larger than or equal to the task's version.
            // We should dicard the cloned replica with stale version.
            if (tabletInfo.getVersion() < taskVersion
                    || (tabletInfo.getVersion() == taskVersion && tabletInfo.getVersion_hash() != taskVersionHash)) {
                throw new MetaNotFoundException(String.format("cloned replica's version info is stale. %ld-%ld,"
                                                        + " expected: %ld-%ld",
                                                              tabletInfo.getVersion(), tabletInfo.getVersion_hash(),
                                                              taskVersion, taskVersionHash));
            }

            long version = tabletInfo.getVersion();
            long versionHash = tabletInfo.getVersion_hash();
            long rowCount = tabletInfo.getRow_count();
            long dataSize = tabletInfo.getData_size();

            writeLock();
            try {
                if (job.getState() != JobState.RUNNING) {
                    LOG.warn("clone job state is not running. job: {}", job);
                    return;
                }
 
                replica.setState(ReplicaState.NORMAL);
                replica.updateInfo(version, versionHash, dataSize, rowCount);

                job.setCloneFinishTimeMs(System.currentTimeMillis());
                job.setState(JobState.FINISHED);
                LOG.info("finish clone job: {}", job);
                
                // Write edit log
                ReplicaPersistInfo info = ReplicaPersistInfo.createForClone(dbId, tableId, partitionId, indexId,
                                                                            tabletId, backendId, replica.getId(),
                                                                            version, versionHash, dataSize, rowCount);
                Catalog.getInstance().getEditLog().logAddReplica(info);
            } finally {
                writeUnlock();
            }
        } catch (MetaNotFoundException e) {
            cancelCloneJob(job, e.getMessage());
        } finally {
            db.writeUnlock();
        }
    }
    
    /**
     * remove finished or cancelled clone job
     */
    public void removeCloneJobs() {
        List<CloneJob> cancelledJobs = new ArrayList<CloneJob>();
        writeLock();
        try {
            for (Map<Long, CloneJob> cloneJobs : priorityToCloneJobs.values()) {
                Iterator<Map.Entry<Long, CloneJob>> iterator = cloneJobs.entrySet().iterator();
                while (iterator.hasNext()) {
                    CloneJob job = iterator.next().getValue();
                    JobState state = job.getState();
                    if (state == JobState.FINISHED || state == JobState.CANCELLED) {
                        iterator.remove();
                        --jobNum;
                        LOG.info("remove clone job. job: {}, job num: {}", job, jobNum);
                        
                        if (state == JobState.CANCELLED) {
                            cancelledJobs.add(job);
                        }
                    }
                }
            }
        } finally {
            writeUnlock();
        }

        // remove cancelled job clone state replica
        if (cancelledJobs.isEmpty()) {
            return;
        }
        for (CloneJob job : cancelledJobs) {
            long dbId = job.getDbId();
            long tableId = job.getTableId();
            long partitionId = job.getPartitionId();
            long indexId = job.getIndexId();
            long tabletId = job.getTabletId();
            long backendId = job.getDestBackendId();
            Database db = Catalog.getInstance().getDb(dbId);
            if (db == null) {
                LOG.warn("db does not exist. id: {}", dbId);
                return;
            }
            
            db.writeLock();
            try {
                OlapTable olapTable = (OlapTable) db.getTable(tableId);
                if (olapTable == null) {
                    throw new MetaNotFoundException("table does not exist. id: " + tableId);
                }

                Partition partition = olapTable.getPartition(partitionId);
                if (partition == null) {
                    throw new MetaNotFoundException("partition does not exist. id: " + partitionId);
                }
                MaterializedIndex index = partition.getIndex(indexId);
                if (index == null) {
                    throw new MetaNotFoundException("index does not exist. id: " + indexId);
                }
                Tablet tablet = index.getTablet(tabletId);
                if (tablet == null) {
                    throw new MetaNotFoundException("tablet does not exist. id: " + tabletId);
                }

                Replica replica = tablet.getReplicaByBackendId(backendId);
                if (replica.getState() == ReplicaState.CLONE) {
                    if (tablet.deleteReplicaByBackendId(backendId)) {
                        LOG.info("remove clone replica. tablet id: {}, backend id: {}", tabletId, backendId);
                    }
                }
            } catch (MetaNotFoundException e) {
                LOG.warn("meta not found, error: {}", e.getMessage());
            } finally {
                db.writeUnlock();
            }
        }
    }

    /**
     * calculate clone job priority
     * @return HIGH if online replica num is lower than quorum else LOW
     */
    public static JobPriority calculatePriority(short onlineReplicaNum, short replicationNum) {
        JobPriority priority = JobPriority.LOW;
        short quorumReplicationNum = (short) (replicationNum / 2 + 1);
        if (onlineReplicaNum < quorumReplicationNum) {
            priority = JobPriority.NORMAL;
        }
        return priority;
    }
}
