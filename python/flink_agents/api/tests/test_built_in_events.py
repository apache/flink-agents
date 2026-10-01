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
"""Tests for :mod:`flink_agents.api.events.built_in_events`.

Mirrors the Java ``BuiltInEvents`` / ``BuiltInEventsTest`` coverage: the
registry restores known built-in event types to their concrete subclass at the
JSON boundary, leaves user-defined types generic, and is idempotent.
"""

import importlib
import json
import pkgutil
from uuid import uuid4

import pytest

from flink_agents.api import events
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.events.built_in_events import REGISTRY, restore
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
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
from flink_agents.api.events.run_event import AgentRunBeginEvent
from flink_agents.api.events.tool_event import ToolRequestEvent


def _round_trip_to_base(typed: Event) -> Event:
    """Serialize a typed event and read it back as a generic base ``Event``.

    Reproduces the cross-language shape where nested typed values (such as
    ``ChatMessage``) arrive as plain dicts.
    """
    return Event.model_validate(json.loads(typed.model_dump_json()))


def _chat_request() -> ChatRequestEvent:
    """Build the issue's headline example: a chat request with a typed message."""
    return ChatRequestEvent(
        model="test-model",
        messages=[ChatMessage.user("hello world")],
    )


def _category_fixtures() -> list[Event]:
    """One typed fixture per built-in category restored by the registry."""
    tool_call = {"id": "call_aaaa", "name": "echo", "arguments": {"value": "ping"}}
    return [
        InputEvent(input="hello"),
        OutputEvent(output="world"),
        _chat_request(),
        ChatResponseEvent.success(
            request_id=uuid4(),
            response=ChatMessage.assistant("hi there"),
        ),
        ToolRequestEvent(model="test-model", tool_calls=[tool_call]),
        ContextRetrievalRequestEvent(
            query="what is flink",
            vector_store="test-store",
            max_results=5,
        ),
        AgentRunBeginEvent(key="user-42", value={"user.tier": "gold"}),
        ShortTermWriteEvent(key="user-42", value={"user.tier": "gold"}),
    ]


# ── Registry completeness ────────────────────────────────────────────────


def test_registry_covers_every_builtin_event_type_constant() -> None:
    """Every Python built-in type constant is registered (drift guard)."""
    constants = {
        value
        for name, value in vars(EventType).items()
        if isinstance(value, str) and not name.startswith("_")
    }

    assert set(REGISTRY) == constants


def test_registry_covers_every_concrete_builtin_event_subclass() -> None:
    """The registry lists every concrete built-in ``Event`` subclass.

    Enumerates the real class hierarchy rather than another hand-maintained
    list, so a new built-in event added under ``flink_agents.api.events`` but
    forgotten in ``REGISTRY`` fails here instead of silently degrading to a
    generic ``Event``. It also fails if two concrete subclasses declare the same
    serialized type. ``MemoryEvent`` is excluded because its ``EVENT_TYPE`` is
    ``None``; only concrete subclasses pin a type.
    """
    # Import every module in the events package so a newly added event class is
    # defined even if built_in_events.py never referenced it.
    for module in pkgutil.iter_modules(events.__path__):
        importlib.import_module(f"{events.__name__}.{module.name}")

    def _subclasses(cls: type) -> list[type]:
        found: list[type] = []
        for subclass in cls.__subclasses__():
            found.append(subclass)
            found.extend(_subclasses(subclass))
        return found

    # Track which class claimed each type so two events serializing to the same
    # type fail here with a precise diagnostic instead of collapsing in a set.
    type_to_class: dict[str, type] = {}
    for subclass in _subclasses(Event):
        if not subclass.__module__.startswith("flink_agents.api.events"):
            continue
        event_type = getattr(subclass, "EVENT_TYPE", None)
        if event_type is None:
            continue
        previous = type_to_class.setdefault(event_type, subclass)
        assert previous is subclass, (
            f"serialized type '{event_type}' is claimed by both "
            f"{previous.__name__} and {subclass.__name__}"
        )

    assert set(type_to_class) == set(REGISTRY)


def test_registry_maps_memory_types_to_shared_base() -> None:
    """The memory observation types dispatch through ``MemoryEvent``."""
    memory_types = (
        ShortTermWriteEvent.EVENT_TYPE,
        ShortTermReadEvent.EVENT_TYPE,
        SensoryWriteEvent.EVENT_TYPE,
        SensoryReadEvent.EVENT_TYPE,
        LongTermUpdateEvent.EVENT_TYPE,
        LongTermGetEvent.EVENT_TYPE,
        LongTermSearchEvent.EVENT_TYPE,
    )

    for memory_type in memory_types:
        assert REGISTRY[memory_type] is MemoryEvent


# ── Core restoration (the issue's headline example) ──────────────────────


def test_restore_reconstructs_chat_request_with_typed_messages() -> None:
    """A degraded chat request regains its concrete type and typed messages."""
    typed = _chat_request()
    base = _round_trip_to_base(typed)

    # Pre-restore: a generic Event whose messages degraded to dicts.
    assert type(base) is Event
    assert isinstance(base.attributes["messages"][0], dict)

    restored = restore(base)

    assert type(restored) is ChatRequestEvent
    assert restored.id == typed.id
    assert restored.model == "test-model"
    assert len(restored.messages) == 1
    assert isinstance(restored.messages[0], ChatMessage)
    assert restored.messages[0].role == MessageRole.USER
    assert restored.messages[0].text == "hello world"


def test_restore_reconstructs_every_builtin_category() -> None:
    """Each built-in category is restored to its concrete subclass."""
    for original in _category_fixtures():
        base = _round_trip_to_base(original)
        assert type(base) is Event

        restored = restore(base)

        assert type(restored) is type(original)
        assert restored.type == original.type
        assert restored.id == original.id


def test_restore_dispatches_memory_subtype_to_concrete_class() -> None:
    """A generic memory-typed Event is dispatched to its concrete subclass."""
    base = Event(
        type=ShortTermWriteEvent.EVENT_TYPE,
        attributes={"key": "user-42", "value": {"user.tier": "gold"}},
    )

    restored = restore(base)

    assert type(restored) is ShortTermWriteEvent
    assert restored.key == "user-42"


# ── Fallback, idempotency, lineage ───────────────────────────────────────


def test_restore_returns_unknown_type_unchanged() -> None:
    """An unknown or user-defined type is returned as the same generic Event."""
    base = Event(type="_my_custom_event", attributes={"value": "ping"})

    restored = restore(base)

    assert restored is base
    assert type(restored) is Event
    assert restored.get_attr("value") == "ping"


def test_restore_is_idempotent_for_already_typed_events() -> None:
    """Restoring an already-typed event is a safe no-op for its concrete type."""
    typed = restore(_round_trip_to_base(_chat_request()))

    again = restore(typed)

    assert type(again) is ChatRequestEvent
    assert isinstance(again.messages[0], ChatMessage)
    assert again.id == typed.id


def test_restore_preserves_lineage_and_attachments() -> None:
    """Reconstruction keeps id, lineage metadata, and attachments."""
    upstream = uuid4()
    base = Event(type=InputEvent.EVENT_TYPE, attributes={"input": "hello"})
    base.upstream_event_id = upstream
    base.upstream_action_name = "input_action"
    base.set_attachment("payload", "attachment-value")

    restored = restore(base)

    assert type(restored) is InputEvent
    assert restored.id == base.id
    assert restored.upstream_event_id == upstream
    assert restored.upstream_action_name == "input_action"
    assert restored.get_attachment("payload") == "attachment-value"
    assert restored.input == "hello"


# ── Malformed built-in events fail clearly ───────────────────────────────


def test_restore_raises_for_malformed_memory_event() -> None:
    """A memory event missing its value fails with a clear message."""
    base = Event(
        type=ShortTermWriteEvent.EVENT_TYPE,
        attributes={"key": "user-42"},
    )

    with pytest.raises(
        ValueError,
        match="Malformed built-in event of type '_short_term_write_event'",
    ):
        restore(base)


def test_restore_rejects_output_event_carrying_attachments() -> None:
    """An OutputEvent with attachments is rejected at the boundary."""
    base = Event(
        type=OutputEvent.EVENT_TYPE,
        attributes={"output": "world"},
        attachments={"payload": "attachment-value"},
    )

    with pytest.raises(
        ValueError,
        match="Malformed built-in event of type '_output_event'",
    ):
        restore(base)


# ── Public boundary: Event.from_json ─────────────────────────────────────


def test_from_json_restores_builtin_type_at_the_boundary() -> None:
    """``Event.from_json`` restores a known built-in type to its subclass."""
    event = Event.from_json(_chat_request().model_dump_json())

    assert type(event) is ChatRequestEvent
    assert isinstance(event.messages[0], ChatMessage)


def test_from_json_keeps_user_defined_type_generic() -> None:
    """``Event.from_json`` leaves a user-defined type as a generic Event."""
    event = Event.from_json('{"type": "_my_custom_event", "attributes": {"k": "v"}}')

    assert type(event) is Event
    assert event.get_attr("k") == "v"
