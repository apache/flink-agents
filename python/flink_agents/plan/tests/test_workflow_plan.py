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

from flink_agents.api.event import InputEvent, OutputEvent
from flink_agents.plan.action import Action
from flink_agents.plan.function import Function
from flink_agents.plan.workflow_plan import WorkflowPlan


def increment(event: InputEvent) -> OutputEvent: # noqa: D103
    value = event.input
    value = int(value) + 1
    return OutputEvent(isLegal=True, result=str(value))


def test_simplest_workflow_plan() -> None: # noqa: D103
    INCREMENT_ACTION = Action(
        name="increment",
        exec=Function.from_callable(increment),
        listen_event_types=[InputEvent],
    )
    actions = {InputEvent: [INCREMENT_ACTION]}
    workflow_plan = WorkflowPlan(actions=actions)

    input_event = InputEvent(input="1")
    input_event_triggered_actions = workflow_plan.get_action(type(input_event))
    for action in input_event_triggered_actions:
        result_event = action.exec(input_event)
        if isinstance(result_event, OutputEvent):
            assert result_event.isLegal
            print(result_event.result)
            assert result_event.result == "2"
