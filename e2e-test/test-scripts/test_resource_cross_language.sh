#
#   Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#
#

root_dir=$(pwd)

# If we're running from the python subdirectory, adjust the root_dir
if [[ "$(basename "$root_dir")" == "python" ]]; then
    root_dir=$(dirname "$root_dir")
fi

echo "Root directory: $root_dir"

# Run all tests in the resource-cross-language module using Maven
cd "$root_dir/e2e-test/flink-agents-end-to-end-tests-resource-cross-language"

echo "Running all tests in resource-cross-language module..."
mvn -T16 --batch-mode --no-transfer-progress test

ret=$?
if [ "$ret" != "0" ]; then
    echo "Resource cross-language tests failed with exit code $ret"
    exit $ret
fi

echo "All resource cross-language tests passed successfully!"
