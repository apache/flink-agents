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
from flink_agents.api.events.event import InputEvent
from flink_agents.api.function import JavaFunction

JAVA_HANDLER_QUALNAME = "org.apache.flink.agents.resource.test.JavaActionHandler"
JAVA_HANDLER_METHOD = "multiplyByTwo"
JAVA_HANDLER_PARAMETER_TYPES = [
    "org.apache.flink.agents.api.Event",
    "org.apache.flink.agents.api.context.RunnerContext",
]


class PythonAgentWithJavaActionAgent(Agent):
    """Python agent that dispatches into ``JavaActionHandler.multiplyByTwo``."""

    def __init__(self) -> None:
        """Create a PythonAgentWithJavaActionAgent."""
        super().__init__()
        self.add_action(
            name="multiply_by_two",
            trigger_conditions=[InputEvent.EVENT_TYPE],
            func=JavaFunction(
                qualname=JAVA_HANDLER_QUALNAME,
                method_name=JAVA_HANDLER_METHOD,
                parameter_types=JAVA_HANDLER_PARAMETER_TYPES,
            ),
        )


class SingleKeySelector(KeySelector):
    """Mirror of Java ``JavaAgentWithPythonActionAgent.SingleKeySelector``."""

    def get_key(self, value: int) -> int:
        """Force all records onto a single key."""
        return 0
