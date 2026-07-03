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

from flink_agents.api.configuration import ConfigOption
from flink_agents.api.core_options import MemoryEventOptions
from flink_agents.api.events.event import Event
from flink_agents.api.events.event_type import EventType
from flink_agents.api.events.memory_event import (
    LongTermGetEvent,
    LongTermSearchEvent,
    LongTermUpdateEvent,
    MemoryEvent,
    SensoryReadEvent,
    SensoryWriteEvent,
    ShortTermReadEvent,
    ShortTermWriteEvent,
)


def test_subclasses_pin_types_match_java() -> None:
    assert ShortTermWriteEvent.EVENT_TYPE == "_short_term_write_event"
    assert ShortTermReadEvent.EVENT_TYPE == "_short_term_read_event"
    assert SensoryWriteEvent.EVENT_TYPE == "_sensory_write_event"
    assert SensoryReadEvent.EVENT_TYPE == "_sensory_read_event"
    assert LongTermUpdateEvent.EVENT_TYPE == "_long_term_update_event"
    assert LongTermGetEvent.EVENT_TYPE == "_long_term_get_event"
    assert LongTermSearchEvent.EVENT_TYPE == "_long_term_search_event"

    assert ShortTermWriteEvent(key="k", value={}).type == "_short_term_write_event"
    assert LongTermSearchEvent(key="k", value={}).type == "_long_term_search_event"


def test_is_memory_type() -> None:
    assert MemoryEvent.is_memory_type("_short_term_write_event")
    assert not MemoryEvent.is_memory_type("_input_event")
    assert not MemoryEvent.is_memory_type(None)


def test_event_type_aggregate() -> None:
    assert EventType.ShortTermWriteEvent == ShortTermWriteEvent.EVENT_TYPE
    assert EventType.LongTermSearchEvent == LongTermSearchEvent.EVENT_TYPE


def test_key_value_in_attributes() -> None:
    e = ShortTermWriteEvent(key="user-42", value={"user.tier": "gold"})
    assert e.attributes["key"] == "user-42"
    assert e.attributes["value"] == {"user.tier": "gold"}
    assert e.key == "user-42"
    assert e.value["user.tier"] == "gold"


def test_base_class_is_abstract() -> None:
    with pytest.raises(TypeError, match="abstract"):
        MemoryEvent(key="k", value={})


def test_from_event_rejects_non_memory_type() -> None:
    generic = Event(type="_input_event", attributes={"key": "k", "value": {}})
    with pytest.raises(ValueError, match="Not a memory event type"):
        MemoryEvent.from_event(generic)


def test_from_event_dispatches_to_subclass() -> None:
    generic = Event(
        type=LongTermGetEvent.EVENT_TYPE,
        attributes={"key": "k", "value": {"s.m1": "v"}},
    )
    typed = MemoryEvent.from_event(generic)
    assert isinstance(typed, LongTermGetEvent)
    assert typed.id == generic.id
    assert typed.value == {"s.m1": "v"}


def test_constructors_reject_incomplete_attributes() -> None:
    with pytest.raises(TypeError, match="key"):
        ShortTermWriteEvent(key=None, value={})  # type: ignore[arg-type]
    with pytest.raises(TypeError, match="value"):
        ShortTermWriteEvent(key="k", value=None)  # type: ignore[arg-type]

    generic = Event(type=ShortTermWriteEvent.EVENT_TYPE, attributes={"key": "k"})
    with pytest.raises(TypeError, match="value"):
        MemoryEvent.from_event(generic)


def test_long_term_search_typed_results() -> None:
    e = LongTermSearchEvent(
        key="user-42",
        value={
            "refund policy": [
                {"id": "p_01", "value": "7-day refund", "score": 0.92},
                {"id": "p_02", "value": "no custom refunds", "score": 0.81},
            ]
        },
    )
    assert len(e.results["refund policy"]) == 2
    assert e.results["refund policy"][0]["id"] == "p_01"


def test_config_option_keys_match_java() -> None:
    # (option, expected key, expected default) for all nine options. These keys
    # and defaults are the cross-language contract shared with Java
    # MemoryEventOptions; every one must be asserted so a Python-side typo or
    # drift on any sub-key is caught, not just the master switch.
    expected = [
        (MemoryEventOptions.MEMORY_GENERATE_EVENT, "memory.generate-event", None),
        (
            MemoryEventOptions.SHORT_TERM_WRITE,
            "memory.generate-event.short-term-write",
            None,
        ),
        (
            MemoryEventOptions.SHORT_TERM_READ,
            "memory.generate-event.short-term-read",
            None,
        ),
        (
            MemoryEventOptions.SENSORY_WRITE,
            "memory.generate-event.sensory-write",
            None,
        ),
        (
            MemoryEventOptions.SENSORY_READ,
            "memory.generate-event.sensory-read",
            None,
        ),
        (
            MemoryEventOptions.LONG_TERM_UPDATE,
            "memory.generate-event.long-term-update",
            None,
        ),
        (
            MemoryEventOptions.LONG_TERM_GET,
            "memory.generate-event.long-term-get",
            None,
        ),
        (
            MemoryEventOptions.LONG_TERM_SEARCH,
            "memory.generate-event.long-term-search",
            None,
        ),
        (MemoryEventOptions.AGENT_RUN_BEGIN_EVENT, "agent-run.begin-event", False),
    ]
    for option, key, default in expected:
        assert isinstance(option, ConfigOption)
        assert option.get_key() == key
        assert option.get_default_value() is default
