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

package org.apache.hadoop.hbase.ipc;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.util.DaemonThreadFactory;
import org.apache.hadoop.hbase.util.SizeBasedThrottler;
import org.apache.hadoop.hbase.io.WritableWithSize;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.io.hfile.Compression.Algorithm;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.monitoring.MonitoredRPCHandler;
import org.apache.hadoop.hbase.monitoring.TaskMonitor;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.ObjectWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.compress.Compressor;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.hbase.util.HasThread;

/** An abstract IPC service.  IPC calls take a single {@link Writable} as a
 * parameter, and return a {@link Writable} as their value.  A service runs on
 * a port and is defined by a parameter class and a value class.
 *
 *
 * <p>Copied local so can fix HBASE-900.
 *
 * @see HBaseClient
 */
public abstract class HBaseServer {

  /**
   * The first four bytes of Hadoop RPC connections
   */
  public static final ByteBuffer HEADER = ByteBuffer.wrap("hrpc".getBytes());

  // 1 : Introduce ping and server does not throw away RPCs
  // 3 : RPC was refactored in 0.19
  public static final byte VERSION_3 = 3;
  // 4 : RPC options object in RPC protocol with compression,
  //     profiling, and tagging
  public static final byte VERSION_RPCOPTIONS = 4;

  public static final byte CURRENT_VERSION = VERSION_RPCOPTIONS;

  /**
   * How many calls/handler are allowed in the queue.
   */
  private static final int MAX_QUEUE_SIZE_PER_HANDLER = 100;

  public static final Log LOG = LogFactory.getLog(HBaseServer.class.getName());

  protected static final ThreadLocal<HBaseServer> SERVER =
    new ThreadLocal<HBaseServer>();

  /** Returns the server instance called under or null.  May be called under
   * {@link #call(Writable, long)} implementations, and under {@link Writable}
   * methods of paramters and return values.  Permits applications to access
   * the server context.
   * @return HBaseServer
   */
  public static HBaseServer get() {
    return SERVER.get();
  }

  /** This is set to Call object before Handler invokes an RPC and reset
   * after the call returns.
   */
  protected static final ThreadLocal<Call> CurCall = new ThreadLocal<Call>();

  /** Returns the remote side ip address when invoked inside an RPC
   *  Returns null incase of an error.
   *  @return InetAddress
   */
  public static InetAddress getRemoteIp() {
    Call call = CurCall.get();
    if (call != null) {
      return call.connection.socket.getInetAddress();
    }
    return null;
  }
  /** Returns remote address as a string when invoked inside an RPC.
   *  Returns null in case of an error.
   *  @return String
   */
  public static String getRemoteAddress() {
    InetAddress addr = getRemoteIp();
    return (addr == null) ? null : addr.getHostAddress();
  }

  /**
   * Stub method for getting information about a RPC call. In the future, we
   *  could use this for real pretty printing of non-rpc calls.
   * @see HBaseRPC#Server#getParamFormatMap(java.lang.reflect.Method, Object[])
   * @param method Ignored for now
   * @param params Ignored for now
   * @return null
   */
  public Map<String, Object> getParamFormatMap(Method method, Object[] params) {
    return null;
  }

  protected String bindAddress;
  protected int port;                             // port we listen on
  private int handlerCount;                       // number of handler threads
  protected Class<? extends Writable> paramClass; // class of call parameters
  protected int maxIdleTime;                      // the maximum idle time after
                                                  // which a client may be
                                                  // disconnected
  protected int thresholdIdleConnections;         // the number of idle
                                                  // connections after which we
                                                  // will start cleaning up idle
                                                  // connections
  int maxConnectionsToNuke;                       // the max number of
                                                  // connections to nuke
                                                  // during a cleanup

  protected HBaseRpcMetrics  rpcMetrics;

  protected Configuration conf;

  @SuppressWarnings({"FieldCanBeLocal"})
  private int maxQueueSize;
  protected int socketSendBufferSize;
  protected final boolean tcpNoDelay;   // if T then disable Nagle's Algorithm
  protected final boolean tcpKeepAlive; // if T then use keepalives

  // responseQueuesSizeThrottler is shared among all responseQueues,
  // it bounds memory occupied by responses in all responseQueues
  final SizeBasedThrottler responseQueuesSizeThrottler;

  // RESPONSE_QUEUE_MAX_SIZE limits total size of responses in every response queue
  private static final long DEFAULT_RESPONSE_QUEUES_MAX_SIZE = 1024 * 1024 * 1024; // 1G
  private static final String RESPONSE_QUEUES_MAX_SIZE = "ipc.server.response.queue.maxsize";

  volatile protected boolean running = true;         // true while server runs
  protected BlockingQueue<Call> callQueue; // queued calls

  protected final List<Connection> connectionList =
    Collections.synchronizedList(new LinkedList<Connection>());
  //maintain a list
  //of client connections
  private Listener listener = null;
  protected Responder responder = null;
  protected int numConnections = 0;
  private Handler[] handlers = null;
  protected HBaseRPCErrorHandler errorHandler = null;

  /**
   * A convenience method to bind to a given address and report
   * better exceptions if the address is not a valid host.
   * @param socket the socket to bind
   * @param address the address to bind to
   * @param backlog the number of connections allowed in the queue
   * @throws BindException if the address can't be bound
   * @throws UnknownHostException if the address isn't a valid host name
   * @throws IOException other random errors from bind
   */
  public static void bind(ServerSocket socket, InetSocketAddress address,
                          int backlog) throws IOException {
    try {
      socket.bind(address, backlog);
    } catch (BindException e) {
      BindException bindException =
        new BindException("Problem binding to " + address + " : " +
            e.getMessage());
      bindException.initCause(e);
      throw bindException;
    } catch (SocketException e) {
      // If they try to bind to a different host's address, give a better
      // error message.
      if ("Unresolved address".equals(e.getMessage())) {
        throw new UnknownHostException("Invalid hostname for server: " +
                                       address.getHostName());
      }
      throw e;
    }
  }

  /** A call queued for handling. */
  public static class Call {
    protected int id;                             // the client's call id
    protected Writable param;                     // the parameter passed
    protected Connection connection;              // connection to client
    protected long timestamp;      // the time received when response is null
                                   // the time served when response is not null
    protected ByteBuffer response;                // the response for this call
    protected Compression.Algorithm compressionAlgo =
      Compression.Algorithm.NONE;
    protected int version = CURRENT_VERSION;     // version used for the call
    
    protected boolean shouldProfile = false;
    protected ProfilingData profilingData = null;
    protected String tag = null;
    protected long partialResponseSize; // size of the results collected so far

    public Call(int id, Writable param, Connection connection) {
      this.id = id;
      this.param = param;
      this.connection = connection;
      this.timestamp = System.currentTimeMillis();
      this.response = null;
      this.partialResponseSize = 0;
    }
    
    public void setTag(String tag) {
      this.tag = tag;
    }
    
    public String getTag() {
      return tag;
    }

    public void setVersion(int version) {
     this.version = version;
    }

    public int getVersion() {
      return version;
    }

    public void setRPCCompression(Compression.Algorithm compressionAlgo) {
      this.compressionAlgo = compressionAlgo;
    }

    public Compression.Algorithm getRPCCompression() {
      return this.compressionAlgo;
    }

    public long getPartialResponseSize() {
      return partialResponseSize;
    }

    public void setPartialResponseSize(long partialResponseSize) {
      this.partialResponseSize = partialResponseSize;
    }

    @Override
    public String toString() {
      return param.toString() + " from " + connection.toString();
    }

    public void setResponse(ByteBuffer response) {
      this.response = response;
    }

    public ProfilingData getProfilingData(){
      return this.profilingData;
    }
  }

  /** Listens on the socket. Creates jobs for the handler threads*/
  private class Listener extends HasThread {

    private ServerSocketChannel acceptChannel = null; //the accept channel
    private Selector selector = null; //the selector that we use for the server
    private InetSocketAddress address; //the address we bind at
    private Random rand = new Random();
    private long lastCleanupRunTime = 0; //the last time when a cleanup connec-
                                         //-tion (for idle connections) ran
    private long cleanupInterval = 10000; //the minimum interval between
                                          //two cleanup runs
    private int backlogLength = conf.getInt("ipc.server.listen.queue.size", 128);
    
    /** The ipc deserialization thread pool */
    protected ThreadPoolExecutor deserializationThreadPool;

    public Listener() throws IOException {
      // this will trigger a DNS lookup
      address = new InetSocketAddress(bindAddress, port);
      // Create a new server socket and set to non blocking mode
      acceptChannel = ServerSocketChannel.open();
      acceptChannel.configureBlocking(false);

      // Bind the server socket to the local host and port
      bind(acceptChannel.socket(), address, backlogLength);
      port = acceptChannel.socket().getLocalPort(); //Could be an ephemeral port
      // create a selector;
      selector= Selector.open();

      // Register accepts on the server socket with the selector.
      acceptChannel.register(selector, SelectionKey.OP_ACCEPT);
      this.setName("IPC Server listener on " + port);
      this.setDaemon(true);
      
      // initialize the ipc deserializationThreadPool thread pool
      int deserializationPoolMaxSize = conf.getInt("ipc.server.deserialization.threadPool.maxSize", 
          Runtime.getRuntime().availableProcessors() + 1);
      deserializationThreadPool = new ThreadPoolExecutor(
          1, // the core pool size
          deserializationPoolMaxSize, // the max pool size
          60L, TimeUnit.SECONDS, // keep-alive time for each worker thread
          new SynchronousQueue<Runnable>(), // direct handoffs
          new DaemonThreadFactory("IPC-Deserialization"),
          new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
              try {
                // The submission (listener) thread will be blocked until the thread pool frees up.
                executor.getQueue().put(r);
              } catch (InterruptedException e) {
                throw new RejectedExecutionException(
                    "Failed to requeue the rejected request because of ", e);
              }
            }
          });
      if (LOG.isDebugEnabled()) {
        LOG.debug("Initialize the deserializationThreadPool with maxium " + 
            deserializationPoolMaxSize + " threads");
      }
    }

    /** cleanup connections from connectionList. Choose a random range
     * to scan and also have a limit on the number of the connections
     * that will be cleanedup per run. The criteria for cleanup is the time
     * for which the connection was idle. If 'force' is true then all
     * connections will be looked at for the cleanup.
     * @param force all connections will be looked at for cleanup
     */
    private void cleanupConnections(boolean force) {
      if (force || numConnections > thresholdIdleConnections) {
        long currentTime = System.currentTimeMillis();
        if (!force && (currentTime - lastCleanupRunTime) < cleanupInterval) {
          return;
        }
        int start = 0;
        int end = numConnections - 1;
        if (!force) {
          start = rand.nextInt() % numConnections;
          end = rand.nextInt() % numConnections;
          int temp;
          if (end < start) {
            temp = start;
            start = end;
            end = temp;
          }
        }
        int i = start;
        int numNuked = 0;
        while (i <= end) {
          Connection c;
          synchronized (connectionList) {
            try {
              c = connectionList.get(i);
            } catch (Exception e) {return;}
          }
          if (c.timedOut(currentTime)) {
            if (LOG.isTraceEnabled())
              LOG.trace(getName() + ": disconnecting client " + c.getHostAddress());
            closeConnection(c);
            numNuked++;
            end--;
            //noinspection UnusedAssignment
            c = null;
            if (!force && numNuked == maxConnectionsToNuke) break;
          }
          else i++;
        }
        lastCleanupRunTime = System.currentTimeMillis();
      }
    }

    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      SERVER.set(HBaseServer.this);

      while (running) {
        SelectionKey key = null;
        try {
          selector.select(); // FindBugs IS2_INCONSISTENT_SYNC
          Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
          while (iter.hasNext()) {
            key = iter.next();
            iter.remove();
            try {
              if (key.isValid()) {
                if (key.isAcceptable()) {
                  doAccept(key);
                } else if (key.isReadable()) {
                  doAsyncRead(key);
                }
              }
            } catch (IOException ignored) {
            }
            key = null;
          }
        } catch (OutOfMemoryError e) {
          if (errorHandler != null) {
            if (errorHandler.checkOOME(e)) {
              LOG.info(getName() + ": exiting on OOME");
              closeCurrentConnection(key);
              cleanupConnections(true);
              return;
            }
          } else {
            // we can run out of memory if we have too many threads
            // log the event and sleep for a minute and give
            // some thread(s) a chance to finish
            LOG.warn("Out of Memory in server select", e);
            closeCurrentConnection(key);
            cleanupConnections(true);
            try { Thread.sleep(60000); } catch (Exception ignored) {}
          }
        } catch (Exception e) {
          closeCurrentConnection(key);
        }
        cleanupConnections(false);
      }
      LOG.info("Stopping " + this.getName());

      synchronized (this) {
        try {
          acceptChannel.close();
          selector.close();
        } catch (IOException ignored) { }

        selector= null;
        acceptChannel= null;

        // clean up all connections
        while (!connectionList.isEmpty()) {
          closeConnection(connectionList.remove(0));
        }
      }
    }

    private void doAsyncRead(final SelectionKey readSelectionKey) {
      unsetReadInterest(readSelectionKey);
      
      // submit the doRead request to the thread pool in order to deserialize the data in parallel
      try {
        deserializationThreadPool.submit(new Runnable() {
          @Override
          public void run() {
            try {
              doRead(readSelectionKey);
            } catch (InterruptedException e) {
              if (LOG.isTraceEnabled()) {
                LOG.trace("Caught: " + StringUtils.stringifyException(e) +
                    " when processing " + readSelectionKey.attachment());
              }
            } finally {
              setReadInterest(readSelectionKey);
              // wake up the selector from the blocking function select()
              selector.wakeup();
            }
          }
        });
      } catch (Throwable e) {
        setReadInterest(readSelectionKey);
        if (LOG.isTraceEnabled()) {
          LOG.trace("Caught " + e.getMessage() + " when processing the remote connection " +
              readSelectionKey.attachment().toString());
        }
      }
    }

    private void closeCurrentConnection(SelectionKey key) {
      if (key != null) {
        Connection c = (Connection)key.attachment();
        if (c != null) {
          if (LOG.isTraceEnabled())
            LOG.trace(getName() + ": disconnecting client " + c.getHostAddress());
          closeConnection(c);
        }
      }
    }

    InetSocketAddress getAddress() {
      return (InetSocketAddress)acceptChannel.socket().getLocalSocketAddress();
    }

    void doAccept(SelectionKey key) throws IOException, OutOfMemoryError {
      Connection c;
      ServerSocketChannel server = (ServerSocketChannel) key.channel();
      // accept up to 10 connections
      for (int i = 0; i < 10; i++) {
        SocketChannel channel = server.accept();
        if (channel==null) return;

        channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(tcpNoDelay);
        channel.socket().setKeepAlive(tcpKeepAlive);
        SelectionKey readKey = channel.register(selector, SelectionKey.OP_READ);
        c = new Connection(channel, System.currentTimeMillis());
        readKey.attach(c);
        synchronized (connectionList) {
          connectionList.add(numConnections, c);
          numConnections++;
        }
        if (LOG.isTraceEnabled())
          LOG.trace("Server connection from " + c.toString() +
              "; # active connections: " + numConnections +
              "; # queued calls: " + callQueue.size());
      }
    }

    void doRead(SelectionKey key) throws InterruptedException {
      int count = 0;
      Connection c = (Connection)key.attachment();
      if (c == null) {
        return;
      }
      c.setLastContact(System.currentTimeMillis());

      try {
        count = c.readAndProcess();
      } catch (InterruptedException ieo) {
        throw ieo;
      } catch (Exception e) {
        if (count > 0) {
          LOG.warn(getName() + ": readAndProcess threw exception " + e +
              ". Count of bytes read: " + count, e);
        }
        count = -1; //so that the (count < 0) block is executed
      }
      if (count < 0) {
        if (LOG.isTraceEnabled())
          LOG.trace(getName() + ": disconnecting client " +
                    c.getHostAddress() + ". Number of active connections: "+
                    numConnections);
        closeConnection(c);
        // c = null;
      }
      else {
        c.setLastContact(System.currentTimeMillis());
      }
    }

    synchronized void doStop() {
      if (selector != null) {
        selector.wakeup();
        Thread.yield();
      }
      if (acceptChannel != null) {
        try {
          acceptChannel.socket().close();
        } catch (IOException e) {
          LOG.warn(getName() + ":Exception in closing listener socket. " + e);
        }
      }
    }
  }

  // Sends responses of RPC back to clients.
  private class Responder extends HasThread {
    private Selector writeSelector;
    private int pending;         // connections waiting to register

    final static int PURGE_INTERVAL = 900000; // 15mins

    Responder() throws IOException {
      this.setName("IPC Server Responder");
      this.setDaemon(true);
      writeSelector = Selector.open(); // create a selector
      pending = 0;
    }

    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      SERVER.set(HBaseServer.this);
      long lastPurgeTime = 0;   // last check for old calls.

      while (running) {
        try {
          waitPending();     // If a channel is being registered, wait.
          writeSelector.select(PURGE_INTERVAL);
          Iterator<SelectionKey> iter = writeSelector.selectedKeys().iterator();
          while (iter.hasNext()) {
            SelectionKey key = iter.next();
            iter.remove();
            try {
              if (key.isValid() && key.isWritable()) {
                  doAsyncWrite(key);
              }
            } catch (IOException e) {
              LOG.warn(getName() + ": doAsyncWrite threw exception " + e);
            }
          }
          long now = System.currentTimeMillis();
          if (now < lastPurgeTime + PURGE_INTERVAL) {
            continue;
          }
          lastPurgeTime = now;
          //
          // If there were some calls that have not been sent out for a
          // long time, discard them.
          //
          if (LOG.isTraceEnabled()) {
            LOG.trace("Checking for old call responses.");
          }
          ArrayList<Call> calls;

          // get the list of channels from list of keys.
          synchronized (writeSelector.keys()) {
            calls = new ArrayList<Call>(writeSelector.keys().size());
            iter = writeSelector.keys().iterator();
            while (iter.hasNext()) {
              SelectionKey key = iter.next();
              Call call = (Call)key.attachment();
              if (call != null && key.channel() == call.connection.channel) {
                calls.add(call);
              }
            }
          }

          for(Call call : calls) {
            doPurge(call, now);
          }
        } catch (OutOfMemoryError e) {
          if (errorHandler != null) {
            if (errorHandler.checkOOME(e)) {
              LOG.info(getName() + ": exiting on OOME");
              return;
            }
          } else {
            //
            // we can run out of memory if we have too many threads
            // log the event and sleep for a minute and give
            // some thread(s) a chance to finish
            //
            LOG.warn("Out of Memory in server select", e);
            try { Thread.sleep(60000); } catch (Exception ignored) {}
      }
        } catch (Exception e) {
          LOG.warn("Exception in Responder " +
                   StringUtils.stringifyException(e));
        }
      }
      LOG.info("Stopping " + this.getName());
    }

    private void doAsyncWrite(SelectionKey key) throws IOException {
      Call call = (Call)key.attachment();
      if (call == null) {
        return;
      }
      if (key.channel() != call.connection.channel) {
        throw new IOException("doAsyncWrite: bad channel");
      }

      synchronized(call.connection.responseQueue) {
        if (processResponse(call.connection.responseQueue, false)) {
          try {
            key.interestOps(0);
          } catch (CancelledKeyException e) {
            /* The Listener/reader might have closed the socket.
             * We don't explicitly cancel the key, so not sure if this will
             * ever fire.
             * This warning could be removed.
             */
            LOG.warn("Exception while changing ops : " + e);
          }
        }
      }
    }

    //
    // Remove calls that have been pending in the responseQueue
    // for a long time.
    //
    private void doPurge(Call call, long now) {
      synchronized (call.connection.responseQueue) {
        Iterator<Call> iter = call.connection.responseQueue.listIterator(0);
        while (iter.hasNext()) {
          Call nextCall = iter.next();
          if (now > nextCall.timestamp + PURGE_INTERVAL) {
            closeConnection(nextCall.connection);
            break;
          }
        }
      }
    }

    // Processes one response. Returns true if there are no more pending
    // data for this channel.
    //
    @SuppressWarnings({"ConstantConditions"})
    private boolean processResponse(final LinkedList<Call> responseQueue,
                                    boolean inHandler) throws IOException {
      boolean error = true;
      boolean done = false;       // there is more data for this channel.
      int numElements;
      Call call = null;
      try {
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (responseQueue) {
          //
          // If there are no items for this channel, then we are done
          //
          numElements = responseQueue.size();
          if (numElements == 0) {
            error = false;
            return true;              // no more data for this channel.
          }
          //
          // Extract the first call
          //
          call = responseQueue.peek();
          SocketChannel channel = call.connection.channel;
          if (LOG.isTraceEnabled()) {
            LOG.trace(getName() + ": responding to #" + call.id + " from " +
                      call.connection);
          }
          //
          // Send as much data as we can in the non-blocking fashion
          //
          int numBytes = channelWrite(channel, call.response);
          if (numBytes < 0) {
            // Error flag is set, so returning here closes connection and
            // clears responseQueue.
            return true;
          }
          if (!call.response.hasRemaining()) {
            responseQueue.poll();
            responseQueuesSizeThrottler.decrease(call.response.limit());
            call.connection.decRpcCount();
            //noinspection RedundantIfStatement
            if (numElements == 1) {    // last call fully processes.
              done = true;             // no more data for this channel.
            } else {
              done = false;            // more calls pending to be sent.
            }
            if (LOG.isTraceEnabled()) {
              LOG.trace(getName() + ": responding to #" + call.id + " from " +
                        call.connection + " Wrote " + numBytes + " bytes.");
            }
          } else {
            if (inHandler) {
              // set the serve time when the response has to be sent later
              call.timestamp = System.currentTimeMillis();

              incPending();
              try {
                // Wakeup the thread blocked on select, only then can the call
                // to channel.register() complete.
                writeSelector.wakeup();
                channel.register(writeSelector, SelectionKey.OP_WRITE, call);
              } catch (ClosedChannelException e) {
                //Its ok. channel might be closed else where.
                done = true;
              } finally {
                decPending();
              }
            }
            if (LOG.isTraceEnabled()) {
              LOG.trace(getName() + ": responding to #" + call.id + " from " +
                        call.connection + " Wrote partial " + numBytes +
                        " bytes.");
            }
          }
          error = false;              // everything went off well
        }
      } finally {
        if (error && call != null) {
          LOG.warn(getName()+", call " + call + ": output error");
          done = true;               // error. no more data for this channel.
          closeConnection(call.connection);
        }
      }
      return done;
    }

    //
    // Enqueue a response from the application.
    //
    void doRespond(Call call) throws IOException, InterruptedException {
      boolean closed;
      responseQueuesSizeThrottler.increase(call.response.remaining());
      synchronized (call.connection.responseQueue) {
        closed = call.connection.closed;
        if (!closed) {
          call.connection.responseQueue.addLast(call);
          if (call.connection.responseQueue.size() == 1) {
            processResponse(call.connection.responseQueue, true);
          }
        }
      }
      if (closed) {
        // Connection was closed when we tried to submit response, but we
        // increased responseQueues size already. It shoud be
        // decreased here.
        responseQueuesSizeThrottler.decrease(call.response.remaining());
      }
    }

    private synchronized void incPending() {   // call waiting to be enqueued.
      pending++;
    }

    private synchronized void decPending() { // call done enqueueing.
      pending--;
      notify();
    }

    private synchronized void waitPending() throws InterruptedException {
      while (pending > 0) {
        wait();
      }
    }
  }

  /** Reads calls from a connection and queues them for handling. */
  private class Connection {
    private boolean versionRead = false; //if initial signature and
                                         //version are read
    private int version = -1;
    private boolean headerRead = false;  //if the connection header that
                                         //follows version is read.

    protected volatile boolean closed = false;    // indicates if connection was closed
    protected SocketChannel channel;
    private ByteBuffer data;
    private ByteBuffer dataLengthBuffer;
    protected final LinkedList<Call> responseQueue;
    private volatile int rpcCount = 0; // number of outstanding rpcs
    private long lastContact;
    private int dataLength;
    protected Socket socket;
    // Cache the remote host & port info so that even if the socket is
    // disconnected, we can say where it used to connect to.
    private String hostAddress;
    private int remotePort;
    protected UserGroupInformation ticket = null;

    public Connection(SocketChannel channel, long lastContact) {
      this.channel = channel;
      this.lastContact = lastContact;
      this.data = null;
      this.dataLengthBuffer = ByteBuffer.allocate(4);
      this.socket = channel.socket();
      InetAddress addr = socket.getInetAddress();
      if (addr == null) {
        this.hostAddress = "*Unknown*";
      } else {
        this.hostAddress = addr.getHostAddress();
      }
      this.remotePort = socket.getPort();
      this.responseQueue = new LinkedList<Call>();
      if (socketSendBufferSize != 0) {
        try {
          socket.setSendBufferSize(socketSendBufferSize);
        } catch (IOException e) {
          LOG.warn("Connection: unable to set socket send buffer size to " +
                   socketSendBufferSize);
        }
      }
    }

    @Override
    public String toString() {
      return getHostAddress() + ":" + remotePort;
    }

    public String getHostAddress() {
      return hostAddress;
    }

    public int getRemotePort() {
      return remotePort;
    }

    public void setLastContact(long lastContact) {
      this.lastContact = lastContact;
    }

    public long getLastContact() {
      return lastContact;
    }

    /* Return true if the connection has no outstanding rpc */
    private boolean isIdle() {
      return rpcCount == 0;
    }

    /* Decrement the outstanding RPC count */
    protected void decRpcCount() {
      rpcCount--;
    }

    /* Increment the outstanding RPC count */
    private void incRpcCount() {
      rpcCount++;
    }

    protected boolean timedOut(long currentTime) {
      return isIdle() && currentTime - lastContact > maxIdleTime;
    }

    public int readAndProcess() throws IOException, InterruptedException {
      while (true) {
        /* Read at most one RPC. If the header is not read completely yet
         * then iterate until we read first RPC or until there is no data left.
         */
        int count;
        if (dataLengthBuffer.remaining() > 0) {
          count = channelRead(channel, dataLengthBuffer);
          if (count < 0 || dataLengthBuffer.remaining() > 0)
            return count;
        }

        if (!versionRead) {
          //Every connection is expected to send the header.
          ByteBuffer versionBuffer = ByteBuffer.allocate(1);
          count = channelRead(channel, versionBuffer);
          if (count <= 0) {
            return count;
          }
          version = versionBuffer.get(0);

          dataLengthBuffer.flip();
          if (!HEADER.equals(dataLengthBuffer) ||
              version < VERSION_3 || version > CURRENT_VERSION) {
            //Warning is ok since this is not supposed to happen.
            LOG.warn("Incorrect header or version mismatch from " +
                     hostAddress + ":" + remotePort +
                     " got header " + dataLengthBuffer +
                     ", version " + version +
                     " supported versions [" + VERSION_3 +
                     " ... " + CURRENT_VERSION + "]");
            return -1;
          }
          dataLengthBuffer.clear();
          versionRead = true;
          continue;
        }

        if (data == null) {
          dataLengthBuffer.flip();
          dataLength = dataLengthBuffer.getInt();

          if (dataLength == HBaseClient.PING_CALL_ID) {
            dataLengthBuffer.clear();
            return 0;  //ping message
          }
          data = ByteBuffer.allocate(dataLength);
          incRpcCount();  // Increment the rpc count
        }

        count = channelRead(channel, data);

        if (data.remaining() == 0) {
          dataLengthBuffer.clear();
          data.flip();
          if (headerRead) {
            processData();
            data = null;
            return count;
          }
          processHeader();
          headerRead = true;
          data = null;
          continue;
        }
        return count;
      }
    }

    /// Reads the header following version
    private void processHeader() throws IOException {
      /* In the current version, it is just a ticket.
       * Later we could introduce a "ConnectionHeader" class.
       */
      DataInputStream in =
        new DataInputStream(new ByteArrayInputStream(data.array()));
      ticket = (UserGroupInformation) ObjectWritable.readObject(in, conf);
    }

    private void processData() throws  IOException, InterruptedException {
      DataInputStream uncompressedIs =
        new DataInputStream(new ByteArrayInputStream(data.array()));
      Compression.Algorithm txCompression = Algorithm.NONE;
      Compression.Algorithm rxCompression = Algorithm.NONE;
      DataInputStream dis = uncompressedIs;

      // 1. read the call id uncompressed
      int id = uncompressedIs.readInt();
      if (LOG.isTraceEnabled())
        LOG.trace(" got #" + id);
      
      HBaseRPCOptions options = new HBaseRPCOptions ();
      Decompressor decompressor = null;
      if (version >= VERSION_RPCOPTIONS) {
        // 2. read rpc options uncompressed
        options.readFields(dis);
        txCompression = options.getTxCompression();   // server receives this
        rxCompression = options.getRxCompression();   // server responds with
        // 3. set up a decompressor to read the rest of the request
        if (txCompression != Compression.Algorithm.NONE) {
          decompressor = txCompression.getDecompressor();
          InputStream is = txCompression.createDecompressionStream(
              uncompressedIs, decompressor, 0);
          dis = new DataInputStream(is);
        }
      }
      // 4. read the rest of the params
      Writable param = ReflectionUtils.newInstance(paramClass, conf);
      param.readFields(dis);

      Call call = new Call(id, param, this);
      call.shouldProfile = options.getRequestProfiling ();
      
      call.setRPCCompression(rxCompression);
      call.setVersion(version);
      call.setTag(options.getTag());
      callQueue.put(call);              // queue the call; maybe blocked here
      
      if (decompressor != null) {
        txCompression.returnDecompressor(decompressor);
      }
    }

    protected synchronized void close() {
      closed = true;
      data = null;
      dataLengthBuffer = null;
      if (!channel.isOpen())
        return;
      try {socket.shutdownOutput();} catch(Exception ignored) {} // FindBugs DE_MIGHT_IGNORE
      if (channel.isOpen()) {
        try {channel.close();} catch(Exception ignored) {}
      }
      try {socket.close();} catch(Exception ignored) {}
    }
  }

  /** Handles queued calls . */
  private class Handler extends HasThread {
    static final int BUFFER_INITIAL_SIZE = 1024;
    private MonitoredRPCHandler status;

    public Handler(int instanceNumber) {
      this.setDaemon(true);
      String name = "IPC Server handler "+ instanceNumber + " on " + port;
      this.setName(name);
      this.status = TaskMonitor.get().createRPCStatus(name);
    }

    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      status.setStatus("starting");
      status.setProcessingServer(HBaseServer.this);
      SERVER.set(HBaseServer.this);
      while (running) {
        try {
          status.pause("Waiting for a call");
          Call call = callQueue.take(); // pop the queue; maybe blocked here
          status.setStatus("Setting up call");
          status.setConnection(call.connection.getHostAddress(),
              call.connection.getRemotePort());

          if (LOG.isTraceEnabled())
            LOG.trace(getName() + ": has #" + call.id + " from " + call.connection);

          String errorClass = null;
          String error = null;
          Writable value = null;
          
          if (call.shouldProfile) {
            call.profilingData = new ProfilingData ();
          } else {
            call.profilingData = null;
          }
          HRegionServer.callContext.set(call);
          
          CurCall.set(call);
          UserGroupInformation previous = UserGroupInformation.getCurrentUGI();
          UserGroupInformation.setCurrentUser(call.connection.ticket);
          long start = System.currentTimeMillis ();
          try {
            // make the call
            value = call(call.param, call.timestamp, status);
          } catch (Throwable e) {
            LOG.warn(getName()+", call "+call+": error: " + e, e);
            errorClass = e.getClass().getName();
            error = StringUtils.stringifyException(e);
          }
          long total = System.currentTimeMillis () - start;
          UserGroupInformation.setCurrentUser(previous);
          CurCall.set(null);
          
          if (call.shouldProfile) {
            call.profilingData.addLong(
                ProfilingData.TOTAL_SERVER_TIME_MS, total);
          }
          HRegionServer.callContext.remove();
          
          int size = BUFFER_INITIAL_SIZE;
          if (value instanceof WritableWithSize) {
            // get the size hint.
            WritableWithSize ohint = (WritableWithSize)value;
            long hint = ohint.getWritableSize();
            if (hint > 0) {
              hint = hint + Bytes.SIZEOF_BYTE + Bytes.SIZEOF_INT;
              if (hint > Integer.MAX_VALUE) {
                // oops, new problem.
                IOException ioe =
                    new IOException("Result buffer size too large: " + hint);
                errorClass = ioe.getClass().getName();
                error = StringUtils.stringifyException(ioe);
              } else {
                size = ((int)hint);
              }
            }
          }
          ByteBufferOutputStream buf = new ByteBufferOutputStream(size);
          DataOutputStream rawOS = new DataOutputStream(buf);
          DataOutputStream out = rawOS;
          Compressor compressor = null;

          // 1. write call id uncompressed
          out.writeInt(call.id);
          
          // 2. write error flag uncompressed
          out.writeBoolean(error != null);
          
          if (call.getVersion() >= VERSION_RPCOPTIONS) {
            // 3. write the compression type for the rest of the response
            out.writeUTF(call.getRPCCompression().getName());

            // 4. create a compressed output stream if compression was enabled
            if (call.getRPCCompression() != Compression.Algorithm.NONE) {
              compressor = call.getRPCCompression().getCompressor();
              OutputStream compressedOutputStream =
                call.getRPCCompression().createCompressionStream(rawOS, compressor, 0);
              out = new DataOutputStream(compressedOutputStream);
            }
          }

          // 5. write the output as per the compression
          if (error == null) {
            value.write(out);
            // write profiling data if requested
            if (call.getVersion () >= VERSION_RPCOPTIONS) {
              if (!call.shouldProfile) {
                out.writeBoolean(false);
              } else {
                out.writeBoolean(true);
                call.profilingData.write(out);
              }
            }
          } else {
            WritableUtils.writeString(out, errorClass);
            WritableUtils.writeString(out, error);
          }

          out.flush();
          buf.flush();
          call.setResponse(buf.getByteBuffer());
          responder.doRespond(call);
          if (compressor != null) {
            call.getRPCCompression().returnCompressor(compressor);
          }
        } catch (InterruptedException e) {
          if (running) {                          // unexpected -- log it
            LOG.warn(getName() + " caught: " +
                     StringUtils.stringifyException(e));
          }
        } catch (OutOfMemoryError e) {
          if (errorHandler != null) {
            if (errorHandler.checkOOME(e)) {
              LOG.error(getName() + ": exiting on OOME");
              return;
            }
          } else {
            // rethrow if no handler
            throw e;
          }
        } catch (Exception e) {
          LOG.warn(getName() + " caught: " +
                   StringUtils.stringifyException(e));
        }
      }
      LOG.info(getName() + ": exiting");
    }
  }

  protected HBaseServer(String bindAddress, int port,
                  Class<? extends Writable> paramClass, int handlerCount,
                  Configuration conf)
    throws IOException
  {
    this(bindAddress, port, paramClass, handlerCount,  conf, Integer.toString(port));
  }
  /* Constructs a server listening on the named port and address.  Parameters passed must
   * be of the named class.  The <code>handlerCount</handlerCount> determines
   * the number of handler threads that will be used to process calls.
   *
   */
  protected HBaseServer(String bindAddress, int port,
                  Class<? extends Writable> paramClass, int handlerCount,
                  Configuration conf, String serverName)
    throws IOException {
    this.bindAddress = bindAddress;
    this.conf = conf;
    this.port = port;
    this.paramClass = paramClass;
    this.handlerCount = handlerCount;
    this.socketSendBufferSize = 0;
    this.maxQueueSize = handlerCount * MAX_QUEUE_SIZE_PER_HANDLER;
    this.callQueue  = new LinkedBlockingQueue<Call>(maxQueueSize);
    this.maxIdleTime = 2*conf.getInt("ipc.client.connection.maxidletime", 1000);
    this.maxConnectionsToNuke = conf.getInt("ipc.client.kill.max", 10);
    this.thresholdIdleConnections = conf.getInt("ipc.client.idlethreshold", 4000);

    // Start the listener here and let it bind to the port
    listener = new Listener();
    this.port = listener.getAddress().getPort();
    this.rpcMetrics = new HBaseRpcMetrics(serverName,
                          Integer.toString(this.port));
    this.tcpNoDelay = conf.getBoolean("ipc.server.tcpnodelay", false);
    this.tcpKeepAlive = conf.getBoolean("ipc.server.tcpkeepalive", true);

    this.responseQueuesSizeThrottler = new SizeBasedThrottler(
        conf.getLong(RESPONSE_QUEUES_MAX_SIZE, DEFAULT_RESPONSE_QUEUES_MAX_SIZE));

    // Create the responder here
    responder = new Responder();
  }

  protected void closeConnection(Connection connection) {
    synchronized (connectionList) {
      if (connectionList.remove(connection))
        numConnections--;
    }
    connection.close();

    long bytes = 0;
    synchronized (connection.responseQueue) {
      for (Call c : connection.responseQueue) {
        bytes += c.response.limit();
      }
      connection.responseQueue.clear();
    }
    responseQueuesSizeThrottler.decrease(bytes);
  }

  /** Sets the socket buffer size used for responding to RPCs.
   * @param size send size
   */
  public void setSocketSendBufSize(int size) { this.socketSendBufferSize = size; }

  /** Starts the service.  Must be called before any calls will be handled. */
  public synchronized void start() {
    responder.start();
    listener.start();
    handlers = new Handler[handlerCount];

    for (int i = 0; i < handlerCount; i++) {
      handlers[i] = new Handler(i);
      handlers[i].start();
    }
  }

  /** Stops the service.  No new calls will be handled after this is called. */
  public synchronized void stop() {
    LOG.info("Stopping server on " + port);
    running = false;
    if (handlers != null) {
      for (int i = 0; i < handlerCount; i++) {
        if (handlers[i] != null) {
          handlers[i].interrupt();
        }
      }
    }
    listener.interrupt();
    listener.doStop();
    responder.interrupt();
    notifyAll();
    if (this.rpcMetrics != null) {
      this.rpcMetrics.shutdown();
    }
  }

  /** Wait for the server to be stopped.
   * Does not wait for all subthreads to finish.
   *  See {@link #stop()}.
   * @throws InterruptedException e
   */
  public synchronized void join() throws InterruptedException {
    while (running) {
      wait();
    }
  }

  /**
   * Return the socket (ip+port) on which the RPC server is listening to.
   * @return the socket (ip+port) on which the RPC server is listening to.
   */
  public synchronized InetSocketAddress getListenerAddress() {
    return listener.getAddress();
  }

  /** Called for each call.
   * @param param writable parameter
   * @param receiveTime time
   * @param status The task monitor for the associated handler.
   * @return Writable
   * @throws IOException e
   */
  public abstract Writable call(Writable param, long receiveTime,
      MonitoredRPCHandler status) throws IOException;

  /**
   * The number of open RPC conections
   * @return the number of open rpc connections
   */
  public int getNumOpenConnections() {
    return numConnections;
  }

  /**
   * The number of rpc calls in the queue.
   * @return The number of rpc calls in the queue.
   */
  public int getCallQueueLen() {
    return callQueue.size();
  }

  /**
   * Set the handler for calling out of RPC for error conditions.
   * @param handler the handler implementation
   */
  public void setErrorHandler(HBaseRPCErrorHandler handler) {
    this.errorHandler = handler;
  }

  /**
   * When the read or write buffer size is larger than this limit, i/o will be
   * done in chunks of this size. Most RPC requests and responses would be
   * be smaller.
   */
  private static int NIO_BUFFER_LIMIT = 8*1024; //should not be more than 64KB.

  /**
   * This is a wrapper around {@link WritableByteChannel#write(ByteBuffer)}.
   * If the amount of data is large, it writes to channel in smaller chunks.
   * This is to avoid jdk from creating many direct buffers as the size of
   * buffer increases. This also minimizes extra copies in NIO layer
   * as a result of multiple write operations required to write a large
   * buffer.
   *
   * @param channel writable byte channel to write to
   * @param buffer buffer to write
   * @return number of bytes written
   * @throws java.io.IOException e
   * @see WritableByteChannel#write(ByteBuffer)
   */
  protected static int channelWrite(WritableByteChannel channel,
                                    ByteBuffer buffer) throws IOException {
    return (buffer.remaining() <= NIO_BUFFER_LIMIT) ?
           channel.write(buffer) : channelIO(null, channel, buffer);
  }

  /**
   * This is a wrapper around {@link ReadableByteChannel#read(ByteBuffer)}.
   * If the amount of data is large, it writes to channel in smaller chunks.
   * This is to avoid jdk from creating many direct buffers as the size of
   * ByteBuffer increases. There should not be any performance degredation.
   *
   * @param channel writable byte channel to write on
   * @param buffer buffer to write
   * @return number of bytes written
   * @throws java.io.IOException e
   * @see ReadableByteChannel#read(ByteBuffer)
   */
  protected static int channelRead(ReadableByteChannel channel,
                                   ByteBuffer buffer) throws IOException {
    return (buffer.remaining() <= NIO_BUFFER_LIMIT) ?
           channel.read(buffer) : channelIO(channel, null, buffer);
  }

  /**
   * Helper for {@link #channelRead(ReadableByteChannel, ByteBuffer)}
   * and {@link #channelWrite(WritableByteChannel, ByteBuffer)}. Only
   * one of readCh or writeCh should be non-null.
   *
   * @param readCh read channel
   * @param writeCh write channel
   * @param buf buffer to read or write into/out of
   * @return bytes written
   * @throws java.io.IOException e
   * @see #channelRead(ReadableByteChannel, ByteBuffer)
   * @see #channelWrite(WritableByteChannel, ByteBuffer)
   */
  private static int channelIO(ReadableByteChannel readCh,
                               WritableByteChannel writeCh,
                               ByteBuffer buf) throws IOException {

    int originalLimit = buf.limit();
    int initialRemaining = buf.remaining();
    int ret = 0;

    while (buf.remaining() > 0) {
      try {
        int ioSize = Math.min(buf.remaining(), NIO_BUFFER_LIMIT);
        buf.limit(buf.position() + ioSize);

        ret = (readCh == null) ? writeCh.write(buf) : readCh.read(buf);

        if (ret < ioSize) {
          break;
        }

      } finally {
        buf.limit(originalLimit);
      }
    }

    int nBytes = initialRemaining - buf.remaining();
    return (nBytes > 0) ? nBytes : ret;
  }
  
  /**
   * Set the OP_READ interest for the selection key
   * @param selectionKey
   */
  private static void setReadInterest(final SelectionKey selectionKey) {
    synchronized (selectionKey) {
      selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_READ);
    }
  }
  
  /**
   * Unset the OP_READ interest for the selection key
   * @param selectionKey
   */
  private static void unsetReadInterest(final SelectionKey selectionKey) {
    synchronized (selectionKey) {
      selectionKey.interestOps(selectionKey.interestOps() & (~SelectionKey.OP_READ));
    }
  }

  public long getResponseQueueSize(){
    return responseQueuesSizeThrottler.getCurrentValue();
  }
}
