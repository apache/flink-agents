#    Licensed to the Apache Software Foundation (ASF) under one
#   or more contributor license agreements.  See the NOTICE file
#   distributed with this work for additional information
#   regarding copyright ownership.  The ASF licenses this file
#   to you under the Apache License, Version 2.0 (the
#   "License"); you may not use this file except in compliance
#   with the License.  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#  limitations under the License.
#

# Parse command-line arguments
build_java=true
build_python=true
while [[ "$#" -gt 0 ]]; do
    case $1 in
        -p|--python)
            build_java=false
            ;;
        -j|--java)
            build_python=false
            ;;
        *)
            echo "Error: Unknown option '$1'" >&2
            show_help
            exit 1
            ;;
    esac
    shift
done

CURR_DIR=`pwd`
BASE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"
PROJECT_ROOT="${BASE_DIR}/../"

# build java
if $build_java; then
    mvn --version
    mvn clean install -DskipTests -B
fi

if $build_python; then
  # copy flink-agents-dist jars to python lib with version subdirectories
  PYTHON_LIB_DIR=${PROJECT_ROOT}/python/flink_agents/lib
  rm -rf ${PYTHON_LIB_DIR}
  mkdir -p ${PYTHON_LIB_DIR}

  PROJECT_VERSION=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' pom.xml | head -n 2 | tail -n 1)

  # Automatically detect and copy all Flink version JARs from dist subdirectories
  DIST_DIR="${PROJECT_ROOT}/dist"
  for version_dir in "${DIST_DIR}"/flink-*; do
    if [ -d "$version_dir" ]; then
      version_name=$(basename "$version_dir")
      echo "Processing $version_name..."

      # Create corresponding lib subdirectory
      mkdir -p "${PYTHON_LIB_DIR}/${version_name}"

      # Find and copy the JAR file
      jar_file="${version_dir}/target/flink-agents-dist-${version_name}-${PROJECT_VERSION}.jar"
      if [ -f "$jar_file" ]; then
        cp "$jar_file" "${PYTHON_LIB_DIR}/${version_name}/"
        echo "  Copied: flink-agents-dist-${version_name}-${PROJECT_VERSION}.jar"
      else
        echo "  Warning: JAR file not found at $jar_file"
      fi
    fi
  done

  # build python
  cd python
  rm -rf dist/  # Clean old build artifacts before building
  pip install uv
  uv sync --extra dev
  uv run python -m build
  uv pip install dist/*.whl

  rm -rf ${PYTHON_LIB_DIR}
fi