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
import pytest

from flink_agents.api.events.event import Event
from flink_agents.api.events.event_type import EventType
from flink_agents.api.events.run_event import AgentRunBeginEvent


def test_type_constant() -> None:
    assert AgentRunBeginEvent.EVENT_TYPE == "_agent_run_begin_event"
    assert EventType.AgentRunBeginEvent == AgentRunBeginEvent.EVENT_TYPE


def test_key_value() -> None:
    e = AgentRunBeginEvent(key="user-42", value={"user.tier": "gold"})
    assert e.key == "user-42"
    assert e.value == {"user.tier": "gold"}
    assert e.attributes["key"] == "user-42"


def test_from_event() -> None:
    generic = Event(
        type=AgentRunBeginEvent.EVENT_TYPE,
        attributes={"key": "k", "value": {"a": 1}},
    )
    typed = AgentRunBeginEvent.from_event(generic)
    assert typed.id == generic.id
    assert typed.value == {"a": 1}


def test_from_event_missing_attributes_raises() -> None:
    # Missing "value" -> from_event must reject rather than silently build a bad event.
    generic = Event(type=AgentRunBeginEvent.EVENT_TYPE, attributes={"key": "k"})
    with pytest.raises(TypeError):
        AgentRunBeginEvent.from_event(generic)
