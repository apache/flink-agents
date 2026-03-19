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
PYTHON_BIN=${PYTHON_BIN:-python3}
REPOSITORY_APACHE_BASE_URL=${REPOSITORY_APACHE_BASE_URL:-https://repository.apache.org/content/repositories}

if [ -z "${RELEASE_VERSION:-}" ]; then
    echo "RELEASE_VERSION was not set."
    exit 1
fi

if [ -z "${STAGING_REPOSITORY_ID:-}" ] && [ -z "${MAVEN_REPOSITORY_URL:-}" ]; then
    echo "Either STAGING_REPOSITORY_ID or MAVEN_REPOSITORY_URL must be set."
    exit 1
fi

# fail immediately
set -o errexit
set -o nounset
set -o pipefail
# print command before executing
set -o xtrace

CURR_DIR=`pwd`
if [[ `basename $CURR_DIR` != "tools" ]] ; then
  echo "You have to call the script from the tools/ dir"
  exit 1
fi

###########################

cd ..

if [ -n "${MAVEN_REPOSITORY_URL:-}" ]; then
  RESOLVED_MAVEN_REPOSITORY_URL="${MAVEN_REPOSITORY_URL}"
else
  RESOLVED_MAVEN_REPOSITORY_URL="${REPOSITORY_APACHE_BASE_URL}/${STAGING_REPOSITORY_ID}"
fi

RESOLVED_MAVEN_REPOSITORY_URL="${RESOLVED_MAVEN_REPOSITORY_URL%/}"
MANIFEST_PATH="python/jar_manifest.json"

"${PYTHON_BIN}" - "${MANIFEST_PATH}" "${RELEASE_VERSION}" "${RESOLVED_MAVEN_REPOSITORY_URL}" <<'PY'
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

manifest_path = Path(sys.argv[1])
release_version = sys.argv[2]
repository_url = sys.argv[3].rstrip("/")

manifest = json.loads(manifest_path.read_text())
group_path = manifest["group_id"].replace(".", "/")
manifest["version"] = release_version

for jar in manifest["jars"]:
    artifact_id = jar["artifact_id"]
    classifier = jar.get("classifier")
    filename = f"{artifact_id}-{release_version}"
    if classifier:
        filename = f"{filename}-{classifier}"
    filename = f"{filename}.jar"

    artifact_url = (
        f"{repository_url}/{group_path}/{artifact_id}/{release_version}/{filename}"
    )
    sha256 = hashlib.sha256()
    with urllib.request.urlopen(artifact_url) as response:
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            sha256.update(chunk)
    jar["sha256"] = sha256.hexdigest()

manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
PY
