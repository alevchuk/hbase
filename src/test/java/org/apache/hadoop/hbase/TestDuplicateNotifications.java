/**
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional infomation
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
package org.apache.hadoop.hbase;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;

import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.DuplicateZKNotificationInjectionHandler;
import org.apache.hadoop.hbase.util.InjectionEvent;
import org.apache.hadoop.hbase.util.InjectionHandler;
import org.apache.hadoop.hbase.util.JVMClusterUtil;

/**
 * Test whether region opening works inspite of duplicate opened notifications.
 */
public class TestDuplicateNotifications extends TestRegionRebalancing {
  DuplicateZKNotificationInjectionHandler duplicator =
      new DuplicateZKNotificationInjectionHandler();


  /**
   * Make sure we can handle duplicate notifications for region
   * being opened. Same as testRebalancing -- but we will duplicate
   * some of the notifications.
   * @throws Exception
   *
   * @throws IOException
   */
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    duplicator.setProbability(0.05);
    duplicator.duplicateEvent(InjectionEvent.ZKUNASSIGNEDWATCHER_REGION_OPENED);
    InjectionHandler.set(duplicator);
  }

  @Override
  protected void tearDown() {
    // make sure that some events did get duplicated.
    assertTrue(duplicator.getDuplicatedEventCnt() > 0);
  }
}

