#
#/**
# * Copyright 2007 The Apache Software Foundation
# *
# * Licensed to the Apache Software Foundation (ASF) under one
# * or more contributor license agreements.  See the NOTICE file
# * distributed with this work for additional information
# * regarding copyright ownership.  The ASF licenses this file
# * to you under the Apache License, Version 2.0 (the
# * "License"); you may not use this file except in compliance
# * with the License.  You may obtain a copy of the License at
# *
# *     http://www.apache.org/licenses/LICENSE-2.0
# *
# * Unless required by applicable law or agreed to in writing, software
# * distributed under the License is distributed on an "AS IS" BASIS,
# * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# * See the License for the specific language governing permissions and
# * limitations under the License.
# */

# included in all the hbase scripts with source command
# should not be executable directly
# also should not be passed any arguments, since we need original $*
# Modelled after $HADOOP_HOME/bin/hadoop-env.sh.

# resolve links - $0 may be a softlink

this="$0"
while [ -h "$this" ]; do
  ls=`ls -ld "$this"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '.*/.*' > /dev/null; then
    this="$link"
  else
    this=`dirname "$this"`/"$link"
  fi
done

# convert relative path to absolute path
bin=`dirname "$this"`
script=`basename "$this"`
bin=`cd "$bin">/dev/null; pwd`
this="$bin/$script"

# the root of the hbase installation
if [ -z "$HBASE_HOME" ]; then
  export HBASE_HOME=`dirname "$this"`/..
fi

#check to see if the conf dir or hbase home are given as an optional arguments
while [ $# -gt 1 ]
do
  if [ "--config" = "$1" ]
  then
    shift
    confdir=$1
    shift
    HBASE_CONF_DIR=$confdir
  elif [ "--hosts" = "$1" ]
  then
    shift
    hosts=$1
    shift
    HBASE_REGIONSERVERS=$hosts
  else
    # Presume we are at end of options and break
    break
  fi
done

# Allow alternate hbase conf dir location.
HBASE_CONF_DIR="${HBASE_CONF_DIR:-$HBASE_HOME/conf}"
# List of hbase regions servers.
HBASE_REGIONSERVERS="${HBASE_REGIONSERVERS:-$HBASE_CONF_DIR/regionservers}"
# List of hbase secondary masters.
HBASE_BACKUP_MASTERS="${HBASE_BACKUP_MASTERS:-$HBASE_CONF_DIR/backup-masters}"

# Source the hbase-env.sh.  Will have JAVA_HOME defined.
if [ -f "${HBASE_CONF_DIR}/hbase-env.sh" ]; then
  . "${HBASE_CONF_DIR}/hbase-env.sh"
fi

if [ -z "$JAVA_HOME" ]; then
  for candidate in \
    /usr/lib/jvm/java-6-sun \
    /usr/lib/j2sdk1.6-sun \
    /usr/java/jdk1.6* \
    /usr/java/jre1.6* \
    /Library/Java/Home ; do
    if [ -e $candidate/bin/java ]; then
      export JAVA_HOME=$candidate
      break
    fi
  done
  # if we didn't set it
  if [ -z "$JAVA_HOME" ]; then
    cat 1>&2 <<EOF
+======================================================================+
|      Error: JAVA_HOME is not set and Java could not be found         |
+----------------------------------------------------------------------+
| Please download the latest Sun JDK from the Sun Java web site        |
|       > http://java.sun.com/javase/downloads/ <                      |
|                                                                      |
| HBase requires Java 1.6 or later.                                    |
| NOTE: This script will find Sun Java whether you install using the   |
|       binary or the RPM based installer.                             |
+======================================================================+
EOF
    exit 1
  fi
fi

HBASE_JMX_OPTS="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.ssl=false"
HBASE_JMX_OPTS="$HBASE_JMX_OPTS -Dcom.sun.management.jmxremote.authenticate=false"
# HBASE_JMX_OPTS="$HBASE_JMX_OPTS -Dcom.sun.management.jmxremote.password.file=$HBASE_HOME/conf/jmxremote.passwd"
# HBASE_JMX_OPTS="$HBASE_JMX_OPTS -Dcom.sun.management.jmxremote.access.file=$HBASE_HOME/conf/jmxremote.access"

# JMX options. Set default values if not overridden in hbase-env.sh.
if [ -z "$HBASE_MASTER_JMX_OPTS" ]; then
  HBASE_MASTER_JMX_OPTS="$HBASE_JMX_OPTS -Dcom.sun.management.jmxremote.port=8090"
fi

if [ -z "$HBASE_REGIONSERVER_JMX_OPTS" ]; then
  HBASE_REGIONSERVER_JMX_OPTS="$HBASE_JMX_OPTS -Dcom.sun.management.jmxremote.port=8091"
fi

if [ -z "$HBASE_ZOOKEEPER_JMX_OPTS" ]; then
  HBASE_ZOOKEEPER_JMX_OPTS="$HBASE_JMX_OPTS -Dcom.sun.management.jmxremote.port=8092"
fi

if [ -z "$HBASE_THRIFT_JMX_OPTS" ]; then
  HBASE_THRIFT_JMX_OPTS="$HBASE_JMX_OPTS -Dcom.sun.management.jmxremote.port=8093"
fi

# YourKit Java Profiling
# Note that you need to have yjpagent.so & yjp.jar on your computer and have
# LD_LIBRARY_PATH entry to their dir location. for example:
# export LD_LIBRARY_PATH=/usr/local/hadoop/:$LD_LIBRARY_PATH
HBASE_YOURKIT_PROFILE="-agentlib:yjpagent"

HBASE_GC_LOG=$HBASE_LOG_DIR/gc-hbase.log
HBASE_GC_OPTIONS="-verbose:gc -XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:$HBASE_GC_LOG"

HBASE_MASTER_JDWP_OPTIONS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8070"
HBASE_REGIONSERVER_JDWP_OPTIONS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8071"

# Java debugging options. Set default values if not overridden in hbase-env.sh.
if [ -z "$HBASE_MASTER_DBG_OPTS" ]; then
  HBASE_MASTER_DBG_OPTS="$HBASE_MASTER_JDWP_OPTIONS $HBASE_GC_OPTIONS"
fi

if [ -z "$HBASE_REGIONSERVER_DBG_OPTS" ]; then
  HBASE_REGIONSERVER_DBG_OPTS="$HBASE_REGIONSERVER_JDWP_OPTIONS $HBASE_GC_OPTIONS"
fi

if [ -z "$HBASE_ZOOKEEPER_DBG_OPTS" ]; then
  HBASE_ZOOKEEPER_DBG_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8072"
fi

if [ -z "$HBASE_MASTER_OPTS" ]; then
  export HBASE_MASTER_OPTS="$HBASE_MASTER_DBG_OPTS $HBASE_MASTER_JMX_OPTS"
fi

if [ -z "$HBASE_REGIONSERVER_OPTS" ]; then
  export HBASE_REGIONSERVER_OPTS="$HBASE_REGIONSERVER_DBG_OPTS $HBASE_REGIONSERVER_JMX_OPTS"
fi

if [ -z "$HBASE_ZOOKEEPER_OPTS" ]; then
  export HBASE_ZOOKEEPER_OPTS="$HBASE_ZOOKEEPER_DBG_OPTS $HBASE_ZOOKEEPER_JMX_OPTS"
fi

if [ -z "$HBASE_THRIFT_OPTS" ]; then
  export HBASE_THRIFT_OPTS="$HBASE_THRIFT_JMX_OPTS"
fi

export HBASE_SLAVE_TIMEOUT=300
