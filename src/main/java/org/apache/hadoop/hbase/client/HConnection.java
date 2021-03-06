/**
 * Copyright 2010 The Apache Software Foundation
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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HServerAddress;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.ipc.HBaseRPCOptions;
import org.apache.hadoop.hbase.ipc.HMasterInterface;
import org.apache.hadoop.hbase.ipc.HRegionInterface;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWrapper;

/**
 * Cluster connection.
 * {@link HConnectionManager} manages instances of this class.
 */
public interface HConnection extends Closeable {
  /**
   * Retrieve ZooKeeperWrapper used by the connection.
   * @return ZooKeeperWrapper handle being used by the connection.
   * @throws IOException if a remote or network exception occurs
   */
  public ZooKeeperWrapper getZooKeeperWrapper() throws IOException;

  /**
   * @return proxy connection to master server for this instance
   * @throws MasterNotRunningException if the master is not running
   */
  public HMasterInterface getMaster() throws MasterNotRunningException;

  /** @return - true if the master server is running */
  public boolean isMasterRunning();

  /**
   * Checks if <code>tableName</code> exists.
   * @param tableName Table to check.
   * @return True if table exists already.
   * @throws MasterNotRunningException if the master is not running
   */
  public boolean tableExists(final byte [] tableName)
  throws MasterNotRunningException;

  /**
   * A table that isTableEnabled == false and isTableDisabled == false
   * is possible. This happens when a table has a lot of regions
   * that must be processed.
   * @param tableName table name
   * @return true if the table is enabled, false otherwise
   * @throws IOException if a remote or network exception occurs
   */
  public boolean isTableEnabled(byte[] tableName) throws IOException;

  /**
   * @param tableName table name
   * @return true if the table is disabled, false otherwise
   * @throws IOException if a remote or network exception occurs
   */
  public boolean isTableDisabled(byte[] tableName) throws IOException;

  /**
   * @param tableName table name
   * @return true if all regions of the table are available, false otherwise
   * @throws IOException if a remote or network exception occurs
   */
  public boolean isTableAvailable(byte[] tableName) throws IOException;

  /**
   * List all the userspace tables.  In other words, scan the META table.
   *
   * If we wanted this to be really fast, we could implement a special
   * catalog table that just contains table names and their descriptors.
   * Right now, it only exists as part of the META table's region info.
   *
   * @return - returns an array of HTableDescriptors
   * @throws IOException if a remote or network exception occurs
   */
  public HTableDescriptor[] listTables() throws IOException;

  /**
   * @param tableName table name
   * @return table metadata
   * @throws IOException if a remote or network exception occurs
   */
  public HTableDescriptor getHTableDescriptor(byte[] tableName)
  throws IOException;

  /**
   * Find the location of the region of <i>tableName</i> that <i>row</i>
   * lives in.
   * @param tableName name of the table <i>row</i> is in
   * @param row row key you're trying to find the region of
   * @return HRegionLocation that describes where to find the reigon in
   * question
   * @throws IOException if a remote or network exception occurs
   */
  public HRegionLocation locateRegion(final byte [] tableName,
      final byte [] row)
  throws IOException;

  /**
   * Allows flushing the region cache.
   */
  public void clearRegionCache();

  /**
   * Find the location of the region of <i>tableName</i> that <i>row</i>
   * lives in, ignoring any value that might be in the cache.
   * @param tableName name of the table <i>row</i> is in
   * @param row row key you're trying to find the region of
   * @return HRegionLocation that describes where to find the reigon in
   * question
   * @throws IOException if a remote or network exception occurs
   */
  public HRegionLocation relocateRegion(final byte [] tableName,
      final byte [] row)
  throws IOException;

  /**
   * Establishes a connection to the region server at the specified address.
   * @param regionServer - the server to connect to
   * @param options - ipc options to use
   * @return proxy for HRegionServer
   * @throws IOException if a remote or network exception occurs
   */
  public HRegionInterface getHRegionConnection(HServerAddress regionServer, HBaseRPCOptions options)
  throws IOException;

  /**
   * Establishes a connection to the region server at the specified address.
   * @param regionServer - the server to connect to
   * @param getMaster - do we check if master is alive
   * @param options - ipc options to use
   * @return proxy for HRegionServer
   * @throws IOException if a remote or network exception occurs
   */
  public HRegionInterface getHRegionConnection(
      HServerAddress regionServer, boolean getMaster, HBaseRPCOptions options)
  throws IOException;

  /**
   * Establishes a connection to the region server at the specified address.
   * @param regionServer - the server to connect to
   * @return proxy for HRegionServer
   * @throws IOException if a remote or network exception occurs
   */
  public HRegionInterface getHRegionConnection(HServerAddress regionServer)
  throws IOException;

  /**
   * Establishes a connection to the region server at the specified address.
   * @param regionServer - the server to connect to
   * @param getMaster - do we check if master is alive
   * @return proxy for HRegionServer
   * @throws IOException if a remote or network exception occurs
   */
  public HRegionInterface getHRegionConnection(
      HServerAddress regionServer, boolean getMaster)
  throws IOException;

  /**
   * Find region location hosting passed row
   * @param tableName table name
   * @param row Row to find.
   * @param reload If true do not use cache, otherwise bypass.
   * @return Location of row.
   * @throws IOException if a remote or network exception occurs
   */
  HRegionLocation getRegionLocation(byte [] tableName, byte [] row,
    boolean reload)
  throws IOException;

  /**
   * Pass in a ServerCallable with your particular bit of logic defined and
   * this method will manage the process of doing retries with timed waits
   * and refinds of missing regions.
   *
   * @param <T> the type of the return value
   * @param callable callable to run
   * @return an object of type T
   * @throws IOException if a remote or network exception occurs
   * @throws RuntimeException other unspecified error
   */
  public <T> T getRegionServerWithRetries(ServerCallable<T> callable)
  throws IOException, RuntimeException;

  /**
   * Pass in a ServerCallable with your particular bit of logic defined and
   * this method will pass it to the defined region server.
   * @param <T> the type of the return value
   * @param callable callable to run
   * @return an object of type T
   * @throws IOException if a remote or network exception occurs
   * @throws RuntimeException other unspecified error
   */
  public <T> T getRegionServerWithoutRetries(ServerCallable<T> callable)
  throws IOException, RuntimeException;

  /**
   * Process a batch of Gets. Does the retries.
   *
   * @param list
   *          A batch of Gets to process.
   * @param tableName
   *          The name of the table
   * @param options
   *          RPC options object
   * @return Count of committed Puts. On fault, < list.size().
   * @throws IOException
   *           if a remote or network exception occurs
   */
  public Result[] processBatchOfGets(List<Get> actions,
      final byte[] tableName, final HBaseRPCOptions options)
 throws IOException;

  /**
   * Process a batch of Puts. Does the retries.
   * @param list A batch of Puts to process.
   * @param tableName The name of the table
   * @param options ipc options
   * @return Count of committed Puts.  On fault, < list.size().
   * @throws IOException if a remote or network exception occurs
   */
  public int processBatchOfRows(ArrayList<Put> list, byte[] tableName, HBaseRPCOptions options)
  throws IOException;

  /**
   * Process a batch of Deletes. Does the retries.
   * @param list A batch of Deletes to process.
   * @param tableName The name of the table
   * @param options ipc options
   * @return Count of committed Deletes. On fault, < list.size().
   * @throws IOException if a remote or network exception occurs
   */
  public int processBatchOfDeletes(List<Delete> list, byte[] tableName, 
      final HBaseRPCOptions options)
  throws IOException;

  public void processBatchOfPuts(List<Put> list, final byte[] tableName, HBaseRPCOptions options)
  throws IOException;

  public int processBatchOfRowMutations(final List<RowMutations> list,
    final byte[] tableName, HBaseRPCOptions options)
  throws IOException;

  /**
   * Process a mixed batch of Get actions. All actions for a
   * RegionServer are forwarded in one RPC call.
   *
   *
   * @param actions The List of Gets.
   * @param tableName Name of the hbase table
   * @param pool thread pool for parallel execution
   * @param results An empty array, same size as list. If an exception is thrown,
   * you can test here for partial results, and to determine which actions
   * processed successfully.
   * @param options HBaseRPCOptions to be used
   * @throws IOException,InterruptedException if there are problems talking to META.
   *  Or, operations were not successfully completed.
   */
  public void processBatchedGets(List<Get> actions, final byte[] tableName,
      ExecutorService pool, Result[] results, HBaseRPCOptions options)
      throws IOException, InterruptedException;

  /**
   * Process a mixed batch of Put and Delete operations. All actions for a
   * RegionServer are forwarded in one RPC call.
   *
   *
   * @param actions The collection of Put/Delete operations.
   * @param tableName Name of the hbase table
   * @param pool thread pool for parallel execution
   * @param failures populated with failed operation if there are errors.
   * @param options HBaseRPCOptions to be used
   * @throws IOException,InterruptedException if there are problems talking to META.
   *  Or, operations were not successfully completed.
   */
  public void processBatchedMutations(List<Mutation> actions, final byte[] tableName,
      ExecutorService pool, List<Mutation> failures, HBaseRPCOptions options)
      throws IOException, InterruptedException;


  /**
   * Process the MultiPut request by submitting it to the multiPutThreadPool in HTable.
   * The request will be sent to its destination region server by one thread in 
   * the HTable's multiPutThreadPool.
   * 
   * Also it will return the list of failed put among the MultiPut request or return null if all
   * puts are sent to the HRegionServer successfully.
   * @param mputs The list of MultiPut requests
   * @param tableName
   * @param options The RPC options to be used while communicating with the HRegionServer
   * @return the list of failed put among the requests, otherwise return null
   *         if all puts are sent to the HRegionServer successfully.
   * @throws IOException
   */
  public List<Put> processListOfMultiPut(List<MultiPut> mputs,
      final byte[] tableName, HBaseRPCOptions options) throws IOException;
  
  /**
   * Delete the cached location
   * @param tableName
   * @param row
   * @param oldLoc
   */
  public void deleteCachedLocation(final byte [] tableName, final byte [] row,
      HServerAddress oldLoc);
  
  /**
   * Enable or disable region cache prefetch for the table. It will be
   * applied for the given table's all HTable instances within this
   * connection. By default, the cache prefetch is enabled.
   * @param tableName name of table to configure.
   * @param enable Set to true to enable region cache prefetch.
   */
  public void setRegionCachePrefetch(final byte[] tableName,
      final boolean enable);

  /**
   * Check whether region cache prefetch is enabled or not.
   * @param tableName name of table to check
   * @return true if table's region cache prefecth is enabled. Otherwise
   * it is disabled.
   */
  public boolean getRegionCachePrefetch(final byte[] tableName);

  /**
   * Load the region map and warm up the global region cache for the table.
   * @param tableName name of the table to perform region cache prewarm.
   * @param regions a region map.
   */
  public void prewarmRegionCache(final byte[] tableName,
      final Map<HRegionInfo, HServerAddress> regions);

  /**
   * Starts tracking the updates made to the tableName so that
   * we can ensure that the updates were completed and flushed to
   * disk at the end of the job.
   * @param tableName -- the table for which we should start tracking
   */
  public void startBatchedLoad(byte[] tableName);

  /**
   * Ensure that all the updates made to the table, since
   * startBatchedLoad was called are persisted. This method
   * waits for all the regionservers contacted to
   * flush all the data written so far. If this doesn't happen
   * within a configurable amount of time, it requests the regions
   * to flush.
   * @param tableName -- tableName to flush all puts/deletes for.
   * @param options -- hbase rpc options to use when talking to regionservers
   */
  public void endBatchedLoad(byte[] tableName, HBaseRPCOptions options) throws IOException;
}
