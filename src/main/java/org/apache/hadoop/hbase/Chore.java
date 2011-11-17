/**
 * Copyright 2007 The Apache Software Foundation
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
package org.apache.hadoop.hbase;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.util.Sleeper;

/**
 * Chore is a task performed on a period in hbase.  The chore is run in its own
 * thread. This base abstract class provides while loop and sleeping facility.
 * If an unhandled exception, the threads exit is logged.
 * Implementers just need to add checking if there is work to be done and if
 * so, do it.  Its the base of most of the chore threads in hbase.
 *
 * Don't subclass Chore if the task relies on being woken up for something to
 * do, such as an entry being added to a queue, etc.
 */
public abstract class Chore extends Thread {
  private final Log LOG = LogFactory.getLog(this.getClass());
  private final Sleeper sleeper;

  /**
   * This variable might belong to someone else, e.g. HMaster. Setting this
   * variable might trigger cluster shutdown. To shut down this thread only,
   * use {@link #threadShouldStop}.
   */
  protected volatile AtomicBoolean stop;

  /**
   * Unlike {@link #stop}, this indicates that the current thread should shut
   * down. We use this flag when the master requests a shutdown of base
   * scanners, but we don't want to shut down the whole cluster.
   */
  private volatile boolean threadShouldStop = false;

  /**
   * @param p Period at which we should run.  Will be adjusted appropriately
   * should we find work and it takes time to complete.
   * @param s When this flag is set to true, this thread will cleanup and exit
   * cleanly.
   */
  public Chore(String name, final int p, final AtomicBoolean s) {
    super(name);
    this.sleeper = new Sleeper(p, s);
    this.stop = s;
  }

  /**
   * @see java.lang.Thread#run()
   */
  @Override
  public void run() {
    try {
      boolean initialChoreComplete = false;
      while (!this.stop.get() && !threadShouldStop) {
        long startTime = System.currentTimeMillis();
        try {
          if (!initialChoreComplete) {
            initialChoreComplete = initialChore();
          } else {
            chore();
          }
        } catch (Exception e) {
          LOG.error("Caught exception", e);
          if (this.stop.get()) {
            continue;
          }
        }
        this.sleeper.sleep(startTime);
      }
    } catch (Throwable t) {
      LOG.fatal("Caught error. Starting shutdown.", t);
      this.stop.set(true);
    } finally {
      LOG.info(getName() + " exiting");
    }
  }

  /**
   * If the thread is currently sleeping, trigger the core to happen immediately.
   * If it's in the middle of its operation, will begin another operation
   * immediately after finishing this one.
   */
  public void triggerNow() {
    this.sleeper.skipSleepCycle();
  }

  /**
   * Override to run a task before we start looping.
   * @return true if initial chore was successful
   */
  protected boolean initialChore() {
    // Default does nothing.
    return true;
  }

  /**
   * Look for chores.  If any found, do them else just return.
   */
  protected abstract void chore();

  /**
   * Sleep for period.
   */
  protected void sleep() {
    this.sleeper.sleep();
  }

  /**
   * Sets the flag that this thread should stop, and wakes up the thread if it
   * is sleeping using {@link Sleeper}. Does not interrupt or notify the
   * waiting thread in any other way.
   */
  public void stopThread() {
    threadShouldStop = true;
    sleeper.skipSleepCycle();
  }

}
