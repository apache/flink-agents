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
SKIP_GPG=${SKIP_GPG:-false}
MVN=${MVN:-mvn}

if [ -z "${RELEASE_VERSION:-}" ]; then
    echo "RELEASE_VERSION was not set."
    exit 1
fi

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

if [ "$(uname)" == "Darwin" ]; then
    SHASUM="shasum -a 512"
else
    SHASUM="sha512sum"
fi

cd ..

FLINK_AGENTS_DIR=`pwd`
RELEASE_DIR=${FLINK_AGENTS_DIR}/tools/releasing/release
PYTHON_RELEASE_DIR=${RELEASE_DIR}/python
mkdir -p ${PYTHON_RELEASE_DIR}

###########################

# build maven package, create Flink distribution, generate signature
make_binary_release() {

  echo "Creating binary release"

  DIST_JAR_NAME="flink-agents-dist-${RELEASE_VERSION}.jar"

  # enable release profile here (to check for the maven version)
  $MVN clean package -Prelease -pl dist -am -Dgpg.skip -Dcheckstyle.skip=true -DskipTests

  cd dist/target
  cp $DIST_JAR_NAME ${RELEASE_DIR}
  cd ${RELEASE_DIR}

  # Sign sha the tgz
  if [ "$SKIP_GPG" == "false" ] ; then
    gpg --armor --detach-sig "${DIST_JAR_NAME}"
  fi
  $SHASUM "${DIST_JAR_NAME}" > "${DIST_JAR_NAME}.sha512"

  cd ${FLINK_AGENTS_DIR}
}

make_python_release() {
  FLINK_AGENTS_VERSION=${RELEASE_VERSION/-SNAPSHOT/.dev0}
  cd python/dist

  # Need to move the downloaded wheel packages from Azure CI to the directory flink-python/dist manually.
  for wheel_file in *.whl; do
    if [[ ! ${wheel_file} =~ ^flink_agents-$FLINK_AGENTS_VERSION- ]]; then
        echo -e "\033[31;1mThe file name of the python package: ${wheel_file} is not consistent with given release version: ${FLINK_AGENTS_VERSION}!\033[0m"
        exit 1
    fi
    cp ${wheel_file} "${PYTHON_RELEASE_DIR}/${wheel_file}"
  done

  cd ${PYTHON_RELEASE_DIR}

  # Sign sha the tgz and wheel packages
  if [ "$SKIP_GPG" == "false" ] ; then
    for wheel_file in *.whl; do
      gpg --armor --detach-sig "${wheel_file}"
    done
  fi

  for wheel_file in *.whl; do
    $SHASUM "${wheel_file}" > "${wheel_file}.sha512"
  done

  cd ${FLINK_AGENTS_DIR}
}

make_binary_release
make_python_release
