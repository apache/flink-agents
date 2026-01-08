#!/usr/bin/env bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

##
## Variables with defaults (if not overwritten by environment)
##
MVN=${MVN:-mvn}
CUSTOM_OPTIONS=${CUSTOM_OPTIONS:-}

# fail immediately
set -o errexit
set -o nounset
# print command before executing
set -o xtrace

CURR_DIR=`pwd`
if [[ `basename $CURR_DIR` != "tools" ]] ; then
  echo "You have to call the script from the tools/ dir"
  exit 1
fi

# Check JDK version - must be JDK 17
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [[ "$JAVA_VERSION" != "17" ]]; then
  echo "Error: JDK 17 is required for release. Current version: $(java -version 2>&1 | head -1)"
  exit 1
fi

###########################

cd ..

echo "=== Flink Agents Multi-JDK Release ==="
echo "Using Java: $(java -version 2>&1 | head -1)"

COMMON_OPTIONS="-Prelease,docs-and-source -DskipTests -DretryFailedDeploymentCount=10 $CUSTOM_OPTIONS"

###########################
# Phase 1: Build and deploy default version (JDK 17)
###########################

echo ""
echo "=== Phase 1: Building and deploying default version (JDK 17 bytecode) ==="
echo ""

$MVN clean deploy $COMMON_OPTIONS

###########################
# Phase 2: Build and deploy api module with jdk11 classifier
###########################

echo ""
echo "=== Phase 2: Building and deploying flink-agents-api with jdk11 classifier (JDK 11 bytecode) ==="
echo ""

$MVN deploy -pl api -Pjava-11-target $COMMON_OPTIONS

echo ""
echo "=== Release complete: Both default (JDK 17) and jdk11 classifier versions deployed ==="
