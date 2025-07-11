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

root_path=$(pwd)
if [[ -n $1 ]]; then
  root_path=$1
fi
echo $root_path

# build python
cd python
pip install -e .[build]
python -m build
python -m pip install python/dist/*.whl

cd $root_path

# build java
mvn clean package -DskipTests

json_path=$root_path/e2e-test/python_agent.json
echo $json_path

# generate python agent plan json file
python python/flink_agents/plan/tests/compatibility/generate_agent_plan_json.py $json_path

# test create java agent plan from python agent plan json
mvn exec:java -pl plan -Dexec.mainClass="org.apache.flink.agents.plan.compatibility.CreateJavaAgentPlanFromPython" -Dexec.classpathScope="test" -Dexec.args="${json_path}"

if [ "$?" != "0" ]
then
  rm -f $json_path
  exit $?
fi

rm -f $json_path

json_path=$root_path/e2e-test/java_agent.json

# generate java agent plan json file
mvn exec:java -pl plan -Dexec.mainClass="org.apache.flink.agents.plan.compatibility.GenerateAgentPlanJson" -Dexec.classpathScope="test" -Dexec.args="${json_path}"

# test create python agent plan from java agent plan json
python python/flink_agents/plan/tests/compatibility/create_python_agent_plan_from_java.py $json_path

if [ "$?" != "0" ]
then
  rm -f $json_path
  exit $?
fi

rm -f $json_path


