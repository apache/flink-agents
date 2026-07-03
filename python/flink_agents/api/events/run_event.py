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
"""Agent-run lifecycle events, mirroring the Java classes."""

from typing import Any, ClassVar, Dict

from typing_extensions import override

from flink_agents.api.events.event import Event
from flink_agents.api.events.memory_event import _observation_attributes


class AgentRunBeginEvent(Event):
    """Lifecycle event carrying one run's STM value snapshot.

    When ``agent-run.begin-event`` is enabled, the framework emits this event
    before the run's actions execute.
    """

    EVENT_TYPE: ClassVar[str] = "_agent_run_begin_event"

    def __init__(self, *, key: str, value: Dict[str, Any]) -> None:
        """Create an AgentRunBeginEvent carrying STM VALUE nodes for the key."""
        super().__init__(
            type=AgentRunBeginEvent.EVENT_TYPE,
            attributes=_observation_attributes(key, value),
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "AgentRunBeginEvent":
        """Rebuild the typed view from a generic Event of this type."""
        result = AgentRunBeginEvent(
            key=event.attributes.get("key"), value=event.attributes.get("value")
        )
        result.id = event.id
        return result

    @property
    def key(self) -> str:
        """Return the String Flink key of this run."""
        return self.get_attr("key")

    @property
    def value(self) -> Dict[str, Any]:
        """Return the STM value snapshot as a dot-key flat map."""
        return self.get_attr("value")
