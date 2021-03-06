/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.zookeeper;

import java.util.Arrays;

import org.apache.hadoop.hbase.util.Bytes;

import static org.apache.zookeeper.Watcher.Event.EventType;;

public class ZNodeEventData {

  private final EventType eventType;
  private final String path;
  private final byte[] data;

  public ZNodeEventData(EventType eventType, String path, byte[] data) {
    this.eventType = eventType;
    this.path = path;
    this.data = data;
  }

  @Override
  public int hashCode() {
    return path.hashCode() ^ Arrays.hashCode(data);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null)
      return false;
    ZNodeEventData that = (ZNodeEventData) obj;
    return that.path.equals(path) && Bytes.equals(that.data, data);
  }

  public EventType getEventType() {
    return eventType;
  }
  
  public String getzNodePath() {
    return path;
  }

  public byte[] getData() {
    return data;
  }

}
