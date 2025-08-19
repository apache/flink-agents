################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
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
#################################################################################
import sys
from pathlib import Path

from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.tests.compatibility.python_agent_plan_compatibility_test_agent import (
    PythonAgentPlanCompatibilityTestAgent,
)

# The agent plan json will be checked by
# flink-agents/plan/src/test/java/org/apache/flink/agents/plan
# /compatibility/GenerateAgentPlanJson.java
# correspond modification should be applied to it when modify this file.
if __name__ == "__main__":
    json_path = sys.argv[1]
    agent_plan = AgentPlan.from_agent(PythonAgentPlanCompatibilityTestAgent(), AgentConfiguration())
    json_value = agent_plan.model_dump_json(serialize_as_any=True, indent=4)
    with Path(json_path).open("w") as f:
        f.write(json_value)
