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

package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.ipc.HBaseRPC;
import org.apache.hadoop.hbase.ipc.HBaseServer.Call;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaMetrics;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;

/**
 * Scanner scans both the memstore and the HStore. Coalesce KeyValue stream
 * into List<KeyValue> for a single row.
 */
class StoreScanner extends NonLazyKeyValueScanner
    implements KeyValueScanner, InternalScanner, ChangedReadersObserver {
  static final Log LOG = LogFactory.getLog(StoreScanner.class);
  private Store store;
  private ScanQueryMatcher matcher;
  private KeyValueHeap heap;
  private boolean cacheBlocks;
  private int countPerRow = 0;
  private int storeLimit = -1;
  private int storeOffset = 0;
  private String metricNamePrefix;
  // Used to indicate that the scanner has closed (see HBASE-1107)
  // Doesnt need to be volatile because it's always accessed via synchronized methods
  private boolean closing = false;
  private final boolean isGet;
  private final boolean explicitColumnQuery;
  private final boolean useRowColBloom;
  private final Scan scan;
  private final NavigableSet<byte[]> columns;
  private final long oldestUnexpiredTS;

  /** We don't ever expect to change this, the constant is just for clarity. */
  static final boolean LAZY_SEEK_ENABLED_BY_DEFAULT = true;

  /** Used during unit testing to ensure that lazy seek does save seek ops */
  private static boolean lazySeekEnabledGlobally =
      LAZY_SEEK_ENABLED_BY_DEFAULT;

  // if heap == null and lastTop != null, you need to reseek given the key below
  private KeyValue lastTop = null;

  /** An internal constructor. */
  private StoreScanner(Store store, boolean cacheBlocks, Scan scan,
      final NavigableSet<byte[]> columns, long ttl) {
    this.store = store;
    initializeMetricNames();
    this.cacheBlocks = cacheBlocks;
    isGet = scan.isGetScan();
    int numCol = columns == null ? 0 : columns.size();
    explicitColumnQuery = numCol > 0;
    this.scan = scan;
    this.columns = columns;
    oldestUnexpiredTS = EnvironmentEdgeManager.currentTimeMillis() - ttl;

    // We look up row-column Bloom filters for multi-column queries as part of
    // the seek operation. However, we also look the row-column Bloom filter
    // for multi-row (non-"get") scans because this is not done in
    // StoreFile.passesBloomFilter(Scan, SortedSet<byte[]>).
    useRowColBloom = numCol > 1 || (!isGet && numCol == 1);
  }

  /**
   * Opens a scanner across memstore, snapshot, and all StoreFiles. Assumes we
   * are not in a compaction.
   *
   * @param store who we scan
   * @param scan the spec
   * @param columns which columns we are scanning
   * @throws IOException
   */
  StoreScanner(Store store, Scan scan, final NavigableSet<byte[]> columns)
      throws IOException {
    this(store, scan.getCacheBlocks(), scan, columns, store.ttl);
    matcher =
        new ScanQueryMatcher(scan, store.getFamily().getName(), columns,
            store.comparator.getRawComparator(),
            store.versionsToReturn(scan.getMaxVersions()), Long.MAX_VALUE,
            Long.MAX_VALUE, // do not include the deletes
            oldestUnexpiredTS);

    // Pass columns to try to filter out unnecessary StoreFiles.
    List<KeyValueScanner> scanners = getScannersNoCompaction();

    // Seek all scanners to the start of the Row (or if the exact matching row
    // key does not exist, then to the start of the next matching Row).
    // Always check bloom filter to optimize the top row seek for delete
    // family marker.
    if (explicitColumnQuery && lazySeekEnabledGlobally) {
      for (KeyValueScanner scanner : scanners) {
        scanner.requestSeek(matcher.getStartKey(), false, true);
      }
    } else {
      for (KeyValueScanner scanner : scanners) {
        scanner.seek(matcher.getStartKey());
      }
    }

    // set storeLimit
    this.storeLimit = scan.getMaxResultsPerColumnFamily();

    // set rowOffset
    this.storeOffset = scan.getRowOffsetPerColumnFamily();

    // Combine all seeked scanners with a heap
    heap = new KeyValueHeap(scanners, store.comparator);

    this.store.addChangedReaderObserver(this);
  }

  /**
   * Opens a scanner across specified StoreFiles. Can be used in compactions.
   * @param store who we scan
   * @param scan the spec
   * @param scanners ancillary scanners
   * @param smallestReadPoint the readPoint that we should use for tracking
   *          versions
   * @param retainDeletesInOutput should we retain deletes after compaction?
   */
  StoreScanner(Store store, Scan scan,
      List<? extends KeyValueScanner> scanners, long smallestReadPoint,
      long retainDeletesInOutputUntil) throws IOException {
    this(store, false, scan, null, store.ttl);

    matcher =
        new ScanQueryMatcher(scan, store.getFamily().getName(), null,
            store.comparator.getRawComparator(),
            store.versionsToReturn(scan.getMaxVersions()), smallestReadPoint,
            retainDeletesInOutputUntil, oldestUnexpiredTS);

    // Filter the list of scanners using Bloom filters, time range, TTL, etc.
    scanners = selectScannersFrom(scanners);

    // Seek all scanners to the initial key
    for (KeyValueScanner scanner : scanners) {
      scanner.seek(matcher.getStartKey());
    }

    // Combine all seeked scanners with a heap
    heap = new KeyValueHeap(scanners, store.comparator);
  }

  /** Constructor for testing. */
  StoreScanner(final Scan scan, final byte [] colFamily, final long ttl,
      final KeyValue.KVComparator comparator,
      final NavigableSet<byte[]> columns,
      final List<KeyValueScanner> scanners)
        throws IOException {
    this(scan, colFamily, ttl, comparator, columns, scanners, Long.MAX_VALUE);
  }

  /** Constructor for testing. */
  StoreScanner(final Scan scan, final byte [] colFamily, final long ttl,
      final KeyValue.KVComparator comparator,
      final NavigableSet<byte[]> columns,
      final List<KeyValueScanner> scanners,
      final long retainDeletesInOutputUntil)
        throws IOException {
    this(null, scan.getCacheBlocks(), scan, columns, ttl);
    this.matcher =
        new ScanQueryMatcher(scan, colFamily, columns,
            comparator.getRawComparator(), scan.getMaxVersions(),
            Long.MAX_VALUE,
            retainDeletesInOutputUntil,
            oldestUnexpiredTS);

    // Seek all scanners to the initial key
    for(KeyValueScanner scanner : scanners) {
      scanner.seek(matcher.getStartKey());
    }
    heap = new KeyValueHeap(scanners, comparator);
  }

  /**
   * Method used internally to initialize metric names throughout the
   * constructors.
   *
   * To be called after the store variable has been initialized!
   */
  private void initializeMetricNames() {
    String tableName = SchemaMetrics.UNKNOWN;
    String family = SchemaMetrics.UNKNOWN;
    if (store != null) {
      tableName = store.getTableName();
      family = Bytes.toString(store.getFamily().getName());
    }

    this.metricNamePrefix =
        SchemaMetrics.generateSchemaMetricsPrefix(tableName, family);
  }

  /**
   * Get a filtered list of scanners. Assumes we are not in a compaction.
   * @return list of scanners to seek
   */
  private List<KeyValueScanner> getScannersNoCompaction() throws IOException {
    final boolean isCompaction = false;
    return selectScannersFrom(store.getScanners(cacheBlocks, isGet,
        isCompaction, matcher));
  }

  /**
   * Filters the given list of scanners using Bloom filter, time range, and
   * TTL.
   */
  private List<KeyValueScanner> selectScannersFrom(
      final List<? extends KeyValueScanner> allScanners) {
    List<KeyValueScanner> scanners =
      new ArrayList<KeyValueScanner>(allScanners.size());

    // include only those scan files which pass all filters
    for (KeyValueScanner kvs : allScanners) {
      if (kvs.shouldUseScanner(scan, columns, oldestUnexpiredTS)) {
        scanners.add(kvs);
      }
    }
    return scanners;
  }

  @Override
  public synchronized KeyValue peek() {
    if (this.heap == null) {
      return this.lastTop;
    }
    return this.heap.peek();
  }

  @Override
  public KeyValue next() {
    // throw runtime exception perhaps?
    throw new RuntimeException("Never call StoreScanner.next()");
  }

  @Override
  public synchronized void close() {
    if (this.closing) return;
    this.closing = true;
    // under test, we dont have a this.store
    if (this.store != null)
      this.store.deleteChangedReaderObserver(this);
    if (this.heap != null)
      this.heap.close();
    this.heap = null; // CLOSED!
    this.lastTop = null; // If both are null, we are closed.
  }

  @Override
  public synchronized boolean seek(KeyValue key) throws IOException {
    if (this.heap == null) {

      List<KeyValueScanner> scanners = getScannersNoCompaction();

      heap = new KeyValueHeap(scanners, store.comparator);
    }

    return this.heap.seek(key);
  }

  /**
   * Get the next row of values from this Store.
   * @param outResult
   * @param limit
   * @return true if there are more rows, false if scanner is done
   */
  @Override
  public synchronized boolean next(List<KeyValue> outResult, int limit) throws IOException {
    return next(outResult, limit, null);
  }

  /**
   * Get the next row of values from this Store.
   * @param outResult
   * @param limit
   * @return true if there are more rows, false if scanner is done
   */
  @Override
  public synchronized boolean next(List<KeyValue> outResult, int limit,
      String metric) throws IOException {

    checkReseek();

    // if the heap was left null, then the scanners had previously run out anyways, close and
    // return.
    if (this.heap == null) {
      close();
      return false;
    }

    KeyValue peeked = this.heap.peek();
    if (peeked == null) {
      close();
      return false;
    }

    if ((matcher.row == null) || !peeked.matchingRow(matcher.row)) {
      this.countPerRow = 0;
      matcher.setRow(peeked.getRow());
    }
    KeyValue kv;
    KeyValue prevKV = null;
    List<KeyValue> results = new ArrayList<KeyValue>();


    Call call = HRegionServer.callContext.get();
    long quotaRemaining = (call == null) ? Long.MAX_VALUE
        : HRegionServer.getResponseSizeLimit() - call.getPartialResponseSize();

    // Only do a sanity-check if store and comparator are available.
    KeyValue.KVComparator comparator =
        store != null ? store.getComparator() : null;

    long addedResultsSize = 0;
    try {
      LOOP: while((kv = this.heap.peek()) != null) {
        // kv is no longer immutable due to KeyOnlyFilter! use copy for safety
        KeyValue copyKv = kv.shallowCopy();
        // Check that the heap gives us KVs in an increasing order.
        if (prevKV != null && comparator != null
            && comparator.compare(prevKV, kv) > 0) {
          throw new IOException("Key " + prevKV + " followed by a " +
              "smaller key " + kv + " in cf " + store);
        }
        prevKV = kv;
        ScanQueryMatcher.MatchCode qcode = matcher.match(copyKv);

        switch(qcode) {
          case INCLUDE:
          case INCLUDE_AND_SEEK_NEXT_ROW:
          case INCLUDE_AND_SEEK_NEXT_COL:
            this.countPerRow++;
            if (storeLimit > -1 &&
                this.countPerRow > (storeLimit + storeOffset)) {
              // do what SEEK_NEXT_ROW does.
              if (!matcher.moreRowsMayExistAfter(kv)) {
                outResult.addAll(results);
                return false;
              }
              reseek(matcher.getKeyForNextRow(kv));
              break LOOP;
            }

            // add to results only if we have skipped #rowOffset kvs
            // also update metric accordingly
            if (this.countPerRow > storeOffset) {
              addedResultsSize += copyKv.getLength();
              if (addedResultsSize > quotaRemaining) {
                LOG.warn("Result too large. Please consider using Batching."
                    + " Cannot allow  operations that fetch more than "
                    + HRegionServer.getResponseSizeLimit() + " bytes.");
                throw new DoNotRetryIOException("Result too large");
              }

              results.add(copyKv);
            }

            if (qcode == ScanQueryMatcher.MatchCode.INCLUDE_AND_SEEK_NEXT_ROW) {
              if (!matcher.moreRowsMayExistAfter(kv)) {
                outResult.addAll(results);
                return false;
              }
              reseek(matcher.getKeyForNextRow(kv));
            } else if (qcode == ScanQueryMatcher.MatchCode.INCLUDE_AND_SEEK_NEXT_COL) {
              reseek(matcher.getKeyForNextColumn(kv));
            } else {
              this.heap.next();
            }

            if (limit > 0 && (results.size() == limit)) {
              break LOOP;
            }
            continue;

          case DONE:
            // copy jazz
            outResult.addAll(results);
            return true;

          case DONE_SCAN:
            close();

            // copy jazz
            outResult.addAll(results);

            return false;

          case SEEK_NEXT_ROW:
            // This is just a relatively simple end of scan fix, to short-cut end
            // us if there is an endKey in the scan.
            if (!matcher.moreRowsMayExistAfter(kv)) {
              outResult.addAll(results);
              return false;
            }

            reseek(matcher.getKeyForNextRow(kv));
            break;

          case SEEK_NEXT_COL:
            reseek(matcher.getKeyForNextColumn(kv));
            break;

          case SKIP:
            this.heap.next();
            break;

          case SEEK_NEXT_USING_HINT:
            KeyValue nextKV = matcher.getNextKeyHint(kv);
            if (nextKV != null) {
              reseek(nextKV);
            } else {
              heap.next();
            }
            break;

          default:
            throw new RuntimeException("UNEXPECTED");
        }
      }
    } finally { 
      // update the counter 
      if (addedResultsSize > 0 && metric != null) {
        HRegion.incrNumericMetric(this.metricNamePrefix + metric, 
            addedResultsSize);
      } 
      // update the partial results size
      if (call != null) {
        call.setPartialResponseSize(call.getPartialResponseSize()
            + addedResultsSize);
      }
    }

    if (!results.isEmpty()) {
      // copy jazz
      outResult.addAll(results);
      return true;
    }

    // No more keys
    close();
    return false;
  }

  @Override
  public synchronized boolean next(List<KeyValue> outResult) throws IOException {
    return next(outResult, -1, null);
  }

  @Override
  public synchronized boolean next(List<KeyValue> outResult, String metric)
      throws IOException {
    return next(outResult, -1, metric);
  }

  // Implementation of ChangedReadersObserver
  @Override
  public synchronized void updateReaders() throws IOException {
    if (this.closing) return;

    // All public synchronized API calls will call 'checkReseek' which will cause
    // the scanner stack to reseek if this.heap==null && this.lastTop != null.
    // But if two calls to updateReaders() happen without a 'next' or 'peek' then we
    // will end up calling this.peek() which would cause a reseek in the middle of a updateReaders
    // which is NOT what we want, not to mention could cause an NPE. So we early out here.
    if (this.heap == null) return;

    // this could be null.
    this.lastTop = this.peek();

    //DebugPrint.println("SS updateReaders, topKey = " + lastTop);

    // close scanners to old obsolete Store files
    this.heap.close(); // bubble thru and close all scanners.
    this.heap = null; // the re-seeks could be slow (access HDFS) free up memory ASAP

    // Let the next() call handle re-creating and seeking
  }

  private void checkReseek() throws IOException {
    if (this.heap == null && this.lastTop != null) {
      resetScannerStack(this.lastTop);
      this.lastTop = null; // gone!
    }
    // else dont need to reseek
  }

  private void resetScannerStack(KeyValue lastTopKey) throws IOException {
    if (heap != null) {
      throw new RuntimeException("StoreScanner.reseek run on an existing heap!");
    }

    List<KeyValueScanner> scanners = getScannersNoCompaction();

    for(KeyValueScanner scanner : scanners) {
      scanner.seek(lastTopKey);
    }

    // Combine all seeked scanners with a heap
    heap = new KeyValueHeap(scanners, store.comparator);

    // Reset the state of the Query Matcher and set to top row.
    // Only reset and call setRow if the row changes; avoids confusing the
    // query matcher if scanning intra-row.
    KeyValue kv = heap.peek();
    if (kv == null) {
      kv = lastTopKey;
    }
    if ((matcher.row == null) || !kv.matchingRow(matcher.row)) {
      this.countPerRow = 0;
      matcher.reset();
      matcher.setRow(kv.getRow());
    }
  }

  @Override
  public synchronized boolean reseek(KeyValue kv) throws IOException {
    //Heap cannot be null, because this is only called from next() which
    //guarantees that heap will never be null before this call.
    if (explicitColumnQuery && lazySeekEnabledGlobally) {
      return heap.requestSeek(kv, true, useRowColBloom);
    } else {
      return heap.reseek(kv);
    }
  }

  @Override
  public long getSequenceID() {
    return 0;
  }

  /**
   * Used in testing.
   * @return all scanners in no particular order
   */
  List<KeyValueScanner> getAllScannersForTesting() {
    List<KeyValueScanner> allScanners = new ArrayList<KeyValueScanner>();
    KeyValueScanner current = heap.getCurrentForTesting();
    if (current != null)
      allScanners.add(current);
    for (KeyValueScanner scanner : heap.getHeap())
      allScanners.add(scanner);
    return allScanners;
  }

  static void enableLazySeekGlobally(boolean enable) {
    lazySeekEnabledGlobally = enable;
  }

}
