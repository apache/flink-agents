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
import uuid

from pydantic import BaseModel
from pyflink.datastream import KeySelector

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.runner_context import RunnerContext


class WorkflowData(BaseModel):
    """Input record for the short-term-memory workflow.

    Attributes:
    ----------
    value : str
        Payload echoed back in the output.
    key : str | None
        Routing key. A record that arrives without a key is assigned an
        auto-generated one during deserialization.
    """

    value: str
    key: str | None = None


def deserialize_with_key(line: str) -> WorkflowData:
    """Parse a JSON line, assigning a unique key when the record has none."""
    data = WorkflowData.model_validate_json(line)
    if data.key is None:
        data.key = str(uuid.uuid4())
    return data


class WorkflowKeySelector(KeySelector):
    """KeySelector routing each record by its (possibly auto-generated) key."""

    def get_key(self, value: WorkflowData) -> str:
        """Extract the routing key from a WorkflowData."""
        return value.key


class VisitCountAgent(Agent):
    """Agent that counts how many records it has seen per key.

    Each key owns an isolated short-term memory, so ``visit_count`` accumulates
    independently per key. A keyless input record lands under its own
    auto-generated key and therefore counts from one.
    """

    @action(EventType.InputEvent)
    @staticmethod
    def count_visits(event: Event, ctx: RunnerContext) -> None:
        """Increment and emit the per-key visit count."""
        data = WorkflowData.model_validate(InputEvent.from_event(event).input)
        stm = ctx.short_term_memory

        count = (stm.get("visit_count") or 0) + 1
        stm.set("visit_count", count)

        ctx.send_event(
            OutputEvent(output={"content": data.value, "visit_count": count})
        )
