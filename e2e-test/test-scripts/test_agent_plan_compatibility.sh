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

tempdir=$1
mkdir $tempdir/agent
data_dir=$tempdir/agent
jar_path=$2

python_script_path=$root_dir/python/flink_agents/plan/tests/compatibility

function test_create_java_agent_from_python_agent_json {
  json_path=$data_dir/python_agent.json

  # generate python agent plan json file
  python $python_script_path/generate_agent_plan_json.py $json_path

  # test create java agent plan from python agent plan json
  java -cp $jar_path org.apache.flink.agents.plan.compatibility.CreateJavaAgentPlanFromJson $json_path

  ret=$?
  if [ "$ret" != "0" ]
  then
    echo "There is failure when create java agent from python agent json, please check the log for details."
    rm -f $json_path
    exit $ret
  fi

  rm -f $json_path
}

function test_create_python_agent_from_java_agent_json {
  json_path=$data_dir/java_agent.json

  # generate java agent plan json file
  java -cp $jar_path org.apache.flink.agents.plan.compatibility.GenerateAgentPlanJson $json_path

  # test create python agent plan from java agent plan json
  python $python_script_path/create_python_agent_plan_from_json.py $json_path

  ret=$?
  if [ "$ret" != "0" ]
  then
    echo "There is failure when create python agent from java agent json, please check the log for details."
    rm -f $json_path
    exit $ret
  fi

  rm -f $json_path
}

test_create_java_agent_from_python_agent_json
test_create_python_agent_from_java_agent_json