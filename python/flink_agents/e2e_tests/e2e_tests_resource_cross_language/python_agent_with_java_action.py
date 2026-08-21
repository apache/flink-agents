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
"""Python agent whose action body is a Java static method (Java→Python mirror)."""

from pyflink.datastream import KeySelector

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event
from flink_agents.api.function import JavaFunction
from flink_agents.api.runner_context import RunnerContext

JAVA_HANDLER_QUALNAME = "org.apache.flink.agents.resource.test.JavaActionHandler"
JAVA_HANDLER_METHOD = "multiplyByTwo"


class PythonAgentWithJavaActionAgent(Agent):
    """Python agent whose overlapping expressions dispatch one Java action."""

    def __init__(self) -> None:
        """Create a PythonAgentWithJavaActionAgent."""
        super().__init__()
        self.add_action(
            name="multiply_by_two",
            trigger_conditions=[
                "type == EventType.InputEvent && input > 1 && input < 7",
                "type == EventType.InputEvent && input > 3 && input < 9",
            ],
            func=JavaFunction.for_action(JAVA_HANDLER_QUALNAME, JAVA_HANDLER_METHOD),
        )


class InvalidTriggerConditionAgent(Agent):
    """Agent used to exercise Java Plan validation through the real gateway."""

    @action("type ==")
    @staticmethod
    def invalid_condition(event: Event, ctx: RunnerContext) -> None:
        """Provide a serializable action body that AgentPlan validation must reject."""
        message = "an invalid trigger condition must never execute"
        raise AssertionError(message)


class SingleKeySelector(KeySelector):
    """Mirror of Java ``JavaAgentWithPythonActionAgent.SingleKeySelector``."""

    def get_key(self, value: int) -> int:
        """Force all records onto a single key."""
        return 0
