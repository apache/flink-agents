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
PYTHON_BIN=${PYTHON_BIN:-python3}
UV=${UV:-uv}

if [ -z "${RELEASE_VERSION:-}" ]; then
    echo "RELEASE_VERSION was not set."
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

  # Dynamically discover dist sub-modules (directories containing pom.xml).
  # Exclude 'common' because it is published to Maven Central for Python installs,
  # not distributed as a convenience binary.
  DIST_MODULES=()
  for module_dir in dist/*/; do
    if [ -f "${module_dir}pom.xml" ]; then
      module_name=$(basename "${module_dir}")
      if [ "$module_name" != "common" ]; then
        DIST_MODULES+=("$module_name")
      fi
    fi
  done

  # Build comma-separated list of dist sub-modules for Maven -pl
  DIST_MODULE_LIST=$(printf "dist/%s," "${DIST_MODULES[@]}")
  DIST_MODULE_LIST=${DIST_MODULE_LIST%,}  # Remove trailing comma

  # enable release profile here (to check for the maven version)
  $MVN clean package -Prelease -pl ${DIST_MODULE_LIST} -am -Dgpg.skip -Dcheckstyle.skip=true -DskipTests

  # Copy jars from all dist sub-modules
  for module in "${DIST_MODULES[@]}"; do
    DIST_JAR_NAME="flink-agents-dist-${module}-${RELEASE_VERSION}.jar"

    cd dist/${module}/target
    cp $DIST_JAR_NAME ${RELEASE_DIR}
    cd ${RELEASE_DIR}

    # Sign sha the jar
    if [ "$SKIP_GPG" == "false" ] ; then
      gpg --armor --detach-sig "${DIST_JAR_NAME}"
    fi
    $SHASUM "${DIST_JAR_NAME}" > "${DIST_JAR_NAME}.sha512"

    cd ${FLINK_AGENTS_DIR}
  done
}

make_python_release() {
  FLINK_AGENTS_VERSION=${RELEASE_VERSION/-SNAPSHOT/.dev0}
  PYTHON_SDIST="flink_agents-${FLINK_AGENTS_VERSION}.tar.gz"

  ${PYTHON_BIN} - <<'PY'
import json
import os
import sys
from pathlib import Path

manifest_path = Path("python/jar_manifest.json")
manifest = json.loads(manifest_path.read_text())
release_version = os.environ["RELEASE_VERSION"]

if manifest["version"] != release_version:
    print(
        f"jar_manifest.json version mismatch: expected {release_version}, found {manifest['version']}",
        file=sys.stderr,
    )
    sys.exit(1)

missing_sha256 = [
    jar["artifact_id"]
    for jar in manifest["jars"]
    if not jar.get("sha256") or jar["sha256"] == "PLACEHOLDER"
]
if missing_sha256:
    print(
        "jar_manifest.json still contains placeholder SHA-256 values for: "
        + ", ".join(missing_sha256),
        file=sys.stderr,
    )
    sys.exit(1)
PY

  cd python

  if command -v "${UV}" >/dev/null 2>&1; then
    "${UV}" run --with build "${PYTHON_BIN}" -m build --sdist
  else
    "${PYTHON_BIN}" -m build --version >/dev/null 2>&1 || {
      echo "Neither '${UV}' nor '${PYTHON_BIN} -m build' is available to build the Python sdist."
      exit 1
    }
    "${PYTHON_BIN}" -m build --sdist
  fi

  if [ ! -f "dist/${PYTHON_SDIST}" ] ; then
    echo -e "\033[31;1mExpected Python sdist dist/${PYTHON_SDIST} was not produced.\033[0m"
    exit 1
  fi

  cp "dist/${PYTHON_SDIST}" "${PYTHON_RELEASE_DIR}/${PYTHON_SDIST}"

  cd ${PYTHON_RELEASE_DIR}

  # Sign and hash the Python sdist.
  if [ "$SKIP_GPG" == "false" ] ; then
    gpg --armor --detach-sig "${PYTHON_SDIST}"
  fi

  $SHASUM "${PYTHON_SDIST}" > "${PYTHON_SDIST}.sha512"

  cd ${FLINK_AGENTS_DIR}
}

make_binary_release
make_python_release
