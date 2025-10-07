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
while [[ "$#" -gt 0 ]]; do
    case $1 in
        -p|--python)
            build_java=false
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
    mvn clean package -DskipTests -B
fi

# copy flink-agents-dist jar to python lib
PYTHON_LIB_DIR=${PROJECT_ROOT}/python/flink_agents/lib
rm -rf ${PYTHON_LIB_DIR}
mkdir -p ${PYTHON_LIB_DIR}

PROJECT_VERSION=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' pom.xml | head -n 2 | tail -n 1)
cp "${PROJECT_ROOT}/dist/target/flink-agents-dist-${PROJECT_VERSION}.jar" ${PYTHON_LIB_DIR}

# build python
cd python
uv sync --extra dev
uv run python -m build
uv pip install dist/*.whl

rm -rf ${PYTHON_LIB_DIR}