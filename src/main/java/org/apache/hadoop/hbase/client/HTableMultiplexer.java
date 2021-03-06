/**
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HServerAddress;
import org.apache.hadoop.hbase.ipc.HBaseRPCOptions;

/**
 * HTableMultiplexer provides a thread-safe non blocking PUT API across all the tables.
 * Each put will be sharded into different buffer queues based on its destination region server.
 * So each region server buffer queue will only have the puts which share the same destination.
 * And each queue will have a flush worker thread to flush the puts request to the region server.
 * If any queue is full, the HTableMultiplexer starts to drop the Put requests for that 
 * particular queue.
 * 
 * Also all the puts will be retried as a configuration number before dropping.
 * And the HTableMultiplexer can report the number of buffered requests and the number of the
 * failed (dropped) requests in total or on per region server basis.
 * 
 * This class is thread safe.
 */
public class HTableMultiplexer {
  private static final Log LOG = LogFactory.getLog(HTableMultiplexer.class.getName());
  private static int poolID = 0;

  private Map<byte[], HTable> tableNameToHTableMap;

  /** The map between each region server to its corresponding buffer queue */
  private Map<HServerAddress, LinkedBlockingQueue<PutStatus>>
    serverToBufferQueueMap;

  /** The map between each region server to its flush worker */
  private Map<HServerAddress, HTableFlushWorker> serverToFlushWorkerMap;

  private Configuration conf;
  private HConnection connection;
  private int retryNum;
  private int perRegionServerBufferQueueSize;
  
  /**
   * 
   * @param conf The HBaseConfiguration
   * @param perRegionServerBufferQueueSize determines the max number of the buffered Put ops 
   *         for each region server before dropping the request.
   */
  public HTableMultiplexer(Configuration conf, int perRegionServerBufferQueueSize) {
    this.conf = conf;
    this.connection = HConnectionManager.getConnection(conf);
    this.serverToBufferQueueMap = new ConcurrentHashMap<HServerAddress,
      LinkedBlockingQueue<PutStatus>>();
    this.serverToFlushWorkerMap = new ConcurrentHashMap<HServerAddress, HTableFlushWorker>();
    this.tableNameToHTableMap = new ConcurrentHashMap<byte[], HTable>();
    this.retryNum = conf.getInt("hbase.client.retries.number", 10);
    this.perRegionServerBufferQueueSize = perRegionServerBufferQueueSize;
  }

  /**
   * The put request will be buffered by its corresponding buffer queue. Return false if the queue
   * is already full.
   * @param table
   * @param put
   * @return true if the request can be accepted by its corresponding buffer queue.
   * @throws IOException
   */
  public boolean put(final byte[] table, final Put put,
      HBaseRPCOptions options) throws IOException {
    return put(table, put, this.retryNum, options);
  }

  /**
   * The puts request will be buffered by their corresponding buffer queue. 
   * Return the list of puts which could not be queued.
   * @param table
   * @param put
   * @return the list of puts which could not be queued
   * @throws IOException
   */
  public List<Put> put(final byte[] table, final List<Put> puts,
      HBaseRPCOptions options) throws IOException {
    if (puts == null)
      return null;
    
    List <Put> failedPuts = null;
    boolean result;
    for (Put put : puts) {
      result = put(table, put, this.retryNum, options);
      if (result == false) {
        
        // Create the failed puts list if necessary
        if (failedPuts == null) {
          failedPuts = new ArrayList<Put>();
        }
        // Add the put to the failed puts list
        failedPuts.add(put);
      }
    }
    return failedPuts;
  }

  /**
   * The put request will be buffered by its corresponding buffer queue. And the put request will be
   * retried before dropping the request.
   * Return false if the queue is already full.
   * @param table
   * @param put
   * @param retry
   * @return true if the request can be accepted by its corresponding buffer queue.
   * @throws IOException
   */
  public boolean put(final byte[] table, final Put put, int retry,
      HBaseRPCOptions options) throws IOException {
    if (retry <= 0) {
      return false;
    }

    LinkedBlockingQueue<PutStatus> queue;
    HTable htable = getHTable(table);
    try {
      htable.validatePut(put);
      HRegionLocation loc = htable.getRegionLocation(put.getRow());
      if (loc != null) {
        // Get the server location for the put
        HServerAddress addr = loc.getServerAddress();
        // Add the put pair into its corresponding queue.
        queue = getBufferedQueue(addr);
        // Generate a MultiPutStatus obj and offer it into the queue
        PutStatus s = new PutStatus(loc.getRegionInfo(), put, retry, options);
        
        return queue.offer(s);
      }
    } catch (Exception e) {
      LOG.debug("Cannot process the put " + put + " because of " + e);
    }
    return false;
  }

  /**
   * @return the current HTableMultiplexerStatus
   */
  public HTableMultiplexerStatus getHTableMultiplexerStatus() {
    return new HTableMultiplexerStatus(serverToFlushWorkerMap);
  }


  private HTable getHTable(final byte[] table) throws IOException {
    HTable htable = this.tableNameToHTableMap.get(table);
    if (htable == null) {
      synchronized (this.tableNameToHTableMap) {
        htable = this.tableNameToHTableMap.get(table);
        if (htable == null)  {
          htable = new HTable(conf, table);
          this.tableNameToHTableMap.put(table, htable);
        }
      }
    }
    return htable;
  }

  private LinkedBlockingQueue<PutStatus> getBufferedQueue(
      HServerAddress addr) {
    LinkedBlockingQueue<PutStatus> queue;
    // Add the put pair into its corresponding queue.
    queue = serverToBufferQueueMap.get(addr);
    if (queue == null) {
      // Create the queue for the new region server
      queue = addNewRegionServer(addr);
    }
    return queue;
  }

  private synchronized LinkedBlockingQueue<PutStatus> addNewRegionServer(HServerAddress addr) {
    LinkedBlockingQueue<PutStatus> queue =
      serverToBufferQueueMap.get(addr);
    if (queue == null) {
      // Create a queue for the new region server
      queue = new LinkedBlockingQueue<PutStatus>(perRegionServerBufferQueueSize);
      serverToBufferQueueMap.put(addr, queue);

      // Create the flush worker
      HTableFlushWorker worker = new HTableFlushWorker(conf, addr,
          this.connection, this, queue);
      this.serverToFlushWorkerMap.put(addr, worker);

      // Launch a daemon thread to flush the puts
      // from the queue to its corresponding region server.
      String name = "HTableFlushWorker-" + addr.getHostNameWithPort() + "-"
          + (poolID++);
      Thread t = new Thread(worker, name);
      t.setDaemon(true);
      t.start();
    }
    return queue;
  }

  /**
   * HTableMultiplexerStatus keeps track of the current status of the HTableMultiplexer.
   * report the number of buffered requests and the number of the failed (dropped) requests
   * in total or on per region server basis.
   */
  public static class HTableMultiplexerStatus {
    private long totalFailedPutCounter;
    private long totalBufferedPutCounter;
    private Map<String, Long> serverToFailedCounterMap;
    private Map<String, Long> serverToBufferedCounterMap;

    public HTableMultiplexerStatus(Map<HServerAddress, HTableFlushWorker> serverToFlushWorkerMap) {
      this.totalBufferedPutCounter = 0;
      this.totalFailedPutCounter = 0;
      this.serverToBufferedCounterMap = new HashMap<String, Long>();
      this.serverToFailedCounterMap = new HashMap<String, Long>();
      this.initialize(serverToFlushWorkerMap);
    }

    private void initialize(Map<HServerAddress, HTableFlushWorker> serverToFlushWorkerMap) {
      if (serverToFlushWorkerMap == null) {
        return;
      }

      for (Map.Entry<HServerAddress, HTableFlushWorker> entry : serverToFlushWorkerMap
          .entrySet()) {
        HServerAddress addr = entry.getKey();
        HTableFlushWorker worker = entry.getValue();

        long bufferedCounter = worker.getTotalBufferedCount();
        long failedCounter = worker.getTotalFailedCount();

        this.totalBufferedPutCounter += bufferedCounter;
        this.totalFailedPutCounter += failedCounter;

        this.serverToBufferedCounterMap.put(addr.getHostNameWithPort(),
            bufferedCounter);
        this.serverToFailedCounterMap.put(addr.getHostNameWithPort(),
            failedCounter);
      }
    }

    public long getTotalBufferedCounter() {
      return this.totalBufferedPutCounter;
    }

    public long getTotalFailedCounter() {
      return this.totalFailedPutCounter;
    }

    public Map<String, Long> getBufferedCounterForEachRegionServer() {
      return this.serverToBufferedCounterMap;
    }

    public Map<String, Long> getFailedCounterForEachRegionServer() {
      return this.serverToFailedCounterMap;
    }
  }
  
  private static class PutStatus {
    private final HRegionInfo regionInfo;
    private final Put put;
    private final int retryCount;
    private final HBaseRPCOptions options;
    public PutStatus(final HRegionInfo regionInfo, final Put put,
        final int retryCount, final HBaseRPCOptions options) {
      this.regionInfo = regionInfo;
      this.put = put;
      this.retryCount = retryCount;
      this.options = options;
    }

    public HRegionInfo getRegionInfo() {
      return regionInfo;
    }
    public Put getPut() {
      return put;
    }
    public int getRetryCount() {
      return retryCount;
    }
    public HBaseRPCOptions getOptions () {
      return options;
    }
  }

  private static class HTableFlushWorker implements Runnable {
    private HServerAddress addr;
    private Configuration conf;
    private LinkedBlockingQueue<PutStatus> queue;
    private HConnection connection;
    private HTableMultiplexer htableMultiplexer;
    private AtomicLong totalFailedPutCount;
    private AtomicInteger currentProcessingPutCount;
    
    public HTableFlushWorker(Configuration conf, HServerAddress addr,
        HConnection connection, HTableMultiplexer htableMultiplexer,
        LinkedBlockingQueue<PutStatus> queue) {
      this.addr = addr;
      this.conf = conf;
      this.connection = connection;
      this.htableMultiplexer = htableMultiplexer;
      this.queue = queue;
      this.totalFailedPutCount = new AtomicLong(0);
      this.currentProcessingPutCount = new AtomicInteger(0);
    }

    public long getTotalFailedCount() {
      return totalFailedPutCount.get();
    }

    public long getTotalBufferedCount() {
      return queue.size() + currentProcessingPutCount.get();
    }

    private boolean resubmitFailedPut(PutStatus failedPutStatus, HServerAddress oldLoc) throws IOException{
      Put failedPut = failedPutStatus.getPut();
      // The currentPut is failed. So get the table name for the currentPut.
      byte[] tableName = failedPutStatus.getRegionInfo().getTableDesc().getName();
      // Decrease the retry count
      int retryCount = failedPutStatus.getRetryCount() - 1;
      
      if (retryCount <= 0) {
        // Update the failed counter and no retry any more.
        return false;
      } else {
        // Retry one more time
        HBaseRPCOptions options = failedPutStatus.getOptions ();
        return this.htableMultiplexer.put(tableName, failedPut, retryCount, options);
      }
    }

    @Override
    public void run() {
      List<PutStatus> processingList = new ArrayList<PutStatus>();
      /** 
       * The frequency in milliseconds for the current thread to process the corresponding  
       * buffer queue.  
       **/
      long frequency = conf.getLong("hbase.htablemultiplexer.flush.frequency.ms", 100);
      
      // initial delay
      try {
        Thread.sleep(frequency);
      } catch (InterruptedException e) {
      } // Ignore

      long start, elapsed;
      int failedCount = 0;
      while (true) {
        try {
          start = System.currentTimeMillis();

          // Clear the processingList, putToStatusMap and failedCount
          processingList.clear();
          failedCount = 0;
          
          // drain all the queued puts into the tmp list
          queue.drainTo(processingList);
          currentProcessingPutCount.set(processingList.size());

          if (processingList.size() > 0) {
            // Create the MultiPut object
            // Amit: Need to change this to use multi, at some point in future.
            MultiPut mput = new MultiPut(this.addr);
            HBaseRPCOptions options = null;
            for (PutStatus putStatus: processingList) {
              // Update the MultiPut
              mput.add(putStatus.getRegionInfo().getRegionName(), 
                  putStatus.getPut());
              if (putStatus.getOptions () != null) {
                options = putStatus.getOptions ();
              }
            }
            
            // Process this multiput request
            List<Put> failed = null;
            try {
              failed = connection.processListOfMultiPut(Arrays.asList(mput), null, options);
            } catch(PreemptiveFastFailException e) {
              // Client is not blocking on us. So, let us treat this
              // as a normal failure, and retry.
              for (PutStatus putStatus: processingList) {
                if (!resubmitFailedPut(putStatus, this.addr)) {
                  failedCount++;
                }
              }
            }

            if (failed != null) {
              if (failed.size() == processingList.size()) {
                // All the puts for this region server are failed. Going to retry it later
                for (PutStatus putStatus: processingList) {
                  if (!resubmitFailedPut(putStatus, this.addr)) {
                    failedCount++;
                  }
                }
              } else {
                Set<Put> failedPutSet = new HashSet<Put>(failed);
                for (PutStatus putStatus: processingList) {
                  if (failedPutSet.contains(putStatus.getPut())
                      && !resubmitFailedPut(putStatus, this.addr)) {
                    failedCount++;
                  }
                }
              }
            }
            // Update the totalFailedCount
            this.totalFailedPutCount.addAndGet(failedCount);
            
            // Reset the current processing put count
            currentProcessingPutCount.set(0);
            
            // Log some basic info
            LOG.debug("Processed " + currentProcessingPutCount
                + " put requests for " + addr.getHostNameWithPort()
                + " and " + failedCount + " failed");
          }

          // Sleep for a while
          elapsed = System.currentTimeMillis() - start;
          if (elapsed < frequency) {
            Thread.sleep(frequency - elapsed);
          }
        } catch (Exception e) {
          // Log all the exceptions and move on
          LOG.debug("Caught some exceptions " + e
              + " when flushing puts to region server "
              + addr.getHostNameWithPort());
        }
      }
    }
  }
}
