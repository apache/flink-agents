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

if [ -z "${SHORT_RELEASE_VERSION:-}" ]; then
    echo "SHORT_RELEASE_VERSION was not set."
    exit 1
fi

if [ -z "${RELEASE_VERSION:-}" ]; then
    echo "RELEASE_VERSION was not set."
    exit 1
fi

CURR_DIR=`pwd`
if [[ `basename $CURR_DIR` != "tools" ]] ; then
  echo "You have to call the script from the tools/ dir"
  exit 1
fi

# fail immediately
set -o errexit
set -o nounset

config_file=../docs/config.toml
url_base="//nightlies.apache.org/flink/flink-agents-docs-release-"

# Pre-flight checks before making any changes
# Check 1: Ensure release branch does not already exist
if git rev-parse --verify "release-${SHORT_RELEASE_VERSION}" >/dev/null 2>&1; then
    echo "Error: Branch 'release-${SHORT_RELEASE_VERSION}' already exists."
    exit 1
fi

# Check 2: Ensure version is not already in PreviousDocs
if grep -q "\[\"${SHORT_RELEASE_VERSION}\"," ${config_file}; then
    echo "Error: Version '${SHORT_RELEASE_VERSION}' already exists in PreviousDocs."
    exit 1
fi

# Step 1: Update PreviousDocs on main branch first
git checkout main

perl -pi -e "s#^  PreviousDocs = \[#  PreviousDocs = \[\n    \[\"${SHORT_RELEASE_VERSION}\", \"http:${url_base}${SHORT_RELEASE_VERSION}\"\],#" ${config_file}

git commit -am "[release] Add ${SHORT_RELEASE_VERSION} to PreviousDocs"

# Step 2: Create release branch (inherits PreviousDocs update from main)
git checkout -b release-${SHORT_RELEASE_VERSION}

# Step 3: Update release branch specific configurations
perl -pi -e "s#^  Version = .*#  Version = \"${RELEASE_VERSION}\"#" ${config_file}

# The version in the title should not contain the bugfix version (e.g. 1.3)
perl -pi -e "s#^  VersionTitle = .*#  VersionTitle = \"${SHORT_RELEASE_VERSION}\"#" ${config_file}

perl -pi -e "s#^  Branch = .*#  Branch = \"release-${SHORT_RELEASE_VERSION}\"#" ${config_file}

perl -pi -e "s#^baseURL = .*#baseURL = \'${url_base}${SHORT_RELEASE_VERSION}\'#" ${config_file}

perl -pi -e "s#^  IsStable = .*#  IsStable = true#" ${config_file}

git commit -am "[release] Update docs for release-${SHORT_RELEASE_VERSION} branch"

echo ""
echo "Done. Don't forget to push both branches:"
echo "  git push origin main"
echo "  git push origin release-${SHORT_RELEASE_VERSION}"
