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
from flink_agents.plan.function import JavaFunction

if __name__ == "__main__":
    json_path = sys.argv[1]
    with Path.open(Path(json_path)) as f:
        java_plan_json = f.read()

    agent_plan = AgentPlan.model_validate_json(java_plan_json)
    actions = agent_plan.actions

    assert len(actions) == 2

    # check the first action
    assert "first_action" in actions
    action1 = actions["first_action"]
    func1 = action1.exec
    assert isinstance(func1, JavaFunction)
    assert func1.qualname == "org.apache.flink.agents.plan.TestAction"
    assert func1.method_name == "legal"
    assert func1.parameter_types == [
        "org.apache.flink.agents.api.InputEvent",
        "org.apache.flink.agents.api.context.RunnerContext",
    ]
    listen_event_types1 = action1.listen_event_types
    assert listen_event_types1 == ["org.apache.flink.agents.api.InputEvent"]

    # check the second action
    assert "second_action" in actions
    action2 = actions["second_action"]
    func2 = action2.exec
    assert isinstance(func2, JavaFunction)
    assert (
            func2.qualname
            == "org.apache.flink.agents.plan.compatibility.GenerateAgentPlanJson$MyAction"
    )
    assert func2.method_name == "doNothing"
    assert func2.parameter_types == [
        "org.apache.flink.agents.api.Event",
        "org.apache.flink.agents.api.context.RunnerContext",
    ]
    listen_event_types2 = action2.listen_event_types
    assert listen_event_types2 == [
        "org.apache.flink.agents.api.InputEvent",
        "org.apache.flink.agents.plan.compatibility.GenerateAgentPlanJson$MyEvent",
    ]

    # check actions_by_event
    actions_by_event = agent_plan.actions_by_event
    assert len(actions_by_event) == 2

    input_evnet = "org.apache.flink.agents.api.InputEvent"
    assert input_evnet in actions_by_event
    assert actions_by_event[input_evnet] == ["first_action", "second_action"]

    my_event = (
        "org.apache.flink.agents.plan.compatibility.GenerateAgentPlanJson$MyEvent"
    )
    assert my_event in actions_by_event
    assert actions_by_event[my_event] == ["second_action"]
