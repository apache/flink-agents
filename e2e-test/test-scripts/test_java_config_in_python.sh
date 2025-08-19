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

echo $root_dir

python_script_path=$root_dir/python/flink_agents/plan/tests/compatibility

function test_create_python_option_from_java_option {
  python $python_script_path/create_python_option_from_java_option.py

  ret=$?
  if [ "$ret" != "0" ]
  then
    echo "There is failure when create python option from java option, please check the log for details."
    rm -f $json_path
    exit $ret
  fi

  rm -f $json_path
}

test_create_python_option_from_java_option