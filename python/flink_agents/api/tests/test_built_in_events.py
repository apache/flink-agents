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
from uuid import UUID, uuid4

import pytest

from flink_agents.api import events
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.events.built_in_events import REGISTRY, restore
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
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
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent


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


# ── Malformed built-in events are rejected at the JSON boundary ──────────


def test_from_json_rejects_chat_request_missing_required_attributes() -> None:
    """A ChatRequestEvent with empty attributes fails at the boundary."""
    with pytest.raises(
        ValueError,
        match="Malformed built-in event of type '_chat_request_event'",
    ):
        Event.from_json('{"type": "_chat_request_event", "attributes": {}}')


def test_from_json_rejects_chat_request_with_invalid_message_element() -> None:
    """messages:[1] must be rejected, not silently kept or dropped."""
    with pytest.raises(
        ValueError,
        match="Malformed built-in event of type '_chat_request_event'",
    ):
        Event.from_json(
            '{"type": "_chat_request_event", '
            '"attributes": {"model": "m", "messages": [1]}}'
        )


# ── Every registered built-in type enforces its schema at the boundary ──

_FIXED_ID = UUID("00000000-0000-0000-0000-000000000001")
_REQUEST_ID = "00000000-0000-0000-0000-000000000002"


def _memory_attrs() -> dict:
    """The ``{key, value}`` shape shared by every memory observation event."""
    return {"key": "user-42", "value": {"user.tier": "gold"}}


def _valid_attributes_by_type() -> dict:
    """Minimal schema-valid attributes for every registered built-in type.

    Each entry satisfies both its ``from_event`` schema check and the concrete
    constructor, so the positive-control test below proves the fixtures are
    genuinely valid and the unknown-attribute test fails only because of the
    injected key. ``ModelRoutingEvent`` is Java-only and absent from ``REGISTRY``.
    """
    return {
        InputEvent.EVENT_TYPE: {"input": "hello"},
        OutputEvent.EVENT_TYPE: {"output": "world"},
        ChatRequestEvent.EVENT_TYPE: {"model": "test-model", "messages": []},
        ChatResponseEvent.EVENT_TYPE: {
            "request_id": _REQUEST_ID,
            "status": ChatResponseEvent.FAILED,
            "error": "boom",
        },
        ToolRequestEvent.EVENT_TYPE: {"model": "test-model", "tool_calls": []},
        ToolResponseEvent.EVENT_TYPE: {"request_id": _REQUEST_ID, "responses": {}},
        ContextRetrievalRequestEvent.EVENT_TYPE: {
            "query": "what is flink",
            "vector_store": "test-store",
            "max_results": 5,
        },
        ContextRetrievalResponseEvent.EVENT_TYPE: {
            "request_id": _REQUEST_ID,
            "query": "what is flink",
            "documents": [],
        },
        AgentRunBeginEvent.EVENT_TYPE: _memory_attrs(),
        ShortTermWriteEvent.EVENT_TYPE: _memory_attrs(),
        ShortTermReadEvent.EVENT_TYPE: _memory_attrs(),
        SensoryWriteEvent.EVENT_TYPE: _memory_attrs(),
        SensoryReadEvent.EVENT_TYPE: _memory_attrs(),
        LongTermGetEvent.EVENT_TYPE: _memory_attrs(),
        LongTermSearchEvent.EVENT_TYPE: _memory_attrs(),
        LongTermUpdateEvent.EVENT_TYPE: _memory_attrs(),
    }


def _event_json(type_name: str, attributes: dict) -> str:
    """Serialize a base-event JSON envelope carrying the given attributes."""
    return json.dumps(
        {"id": str(_FIXED_ID), "type": type_name, "attributes": attributes}
    )


def test_valid_attributes_cover_every_registered_type() -> None:
    """Guard: every registered type has a minimal-valid fixture.

    A newly registered type without a fixture fails here rather than silently
    escaping the parameterized schema tests below.
    """
    assert set(_valid_attributes_by_type()) == set(REGISTRY)


@pytest.mark.parametrize("type_name", sorted(REGISTRY))
def test_from_json_restores_every_builtin_from_minimal_valid_attributes(
    type_name: str,
) -> None:
    """Positive control: minimal fixtures restore to a concrete subclass."""
    event = Event.from_json(
        _event_json(type_name, _valid_attributes_by_type()[type_name])
    )

    assert event.type == type_name
    assert type(event) is not Event


@pytest.mark.parametrize("type_name", sorted(REGISTRY))
def test_from_json_rejects_unknown_attribute_for_every_builtin_type(
    type_name: str,
) -> None:
    """A valid event plus one out-of-schema attribute must be rejected."""
    attributes = {
        **_valid_attributes_by_type()[type_name],
        "__unknown_attribute__": "bogus",
    }

    with pytest.raises(
        ValueError,
        match=f"Malformed built-in event of type '{type_name}'",
    ):
        Event.from_json(_event_json(type_name, attributes))


@pytest.mark.parametrize("type_name", sorted(REGISTRY))
def test_from_json_rejects_missing_required_attributes_for_every_builtin_type(
    type_name: str,
) -> None:
    """Every built-in type has a required attribute, so empty attributes fail."""
    with pytest.raises(
        ValueError,
        match=f"Malformed built-in event of type '{type_name}'",
    ):
        Event.from_json(_event_json(type_name, {}))


# ── Representative invalid field types across the distinct type checks ──


def test_from_json_rejects_chat_request_with_non_string_model() -> None:
    """A non-string ``model`` is rejected rather than coerced."""
    with pytest.raises(
        ValueError,
        match="Malformed built-in event of type '_chat_request_event'",
    ):
        Event.from_json(
            '{"type": "_chat_request_event", '
            '"attributes": {"model": 123, "messages": []}}'
        )


def test_from_json_rejects_tool_request_with_non_dict_tool_call_element() -> None:
    """A ``tool_calls`` element that is not a dict is rejected."""
    with pytest.raises(
        ValueError,
        match="Malformed built-in event of type '_tool_request_event'",
    ):
        Event.from_json(
            '{"type": "_tool_request_event", '
            '"attributes": {"model": "m", "tool_calls": [1]}}'
        )


def test_from_json_rejects_tool_response_with_non_uuid_request_id() -> None:
    """A ``request_id`` that is not a UUID or UUID string is rejected."""
    with pytest.raises(
        ValueError,
        match="Malformed built-in event of type '_tool_response_event'",
    ):
        Event.from_json(
            '{"type": "_tool_response_event", '
            '"attributes": {"request_id": "not-a-uuid", "responses": {}}}'
        )


def test_from_json_rejects_context_retrieval_request_with_non_int_max_results() -> None:
    """A non-int ``max_results`` is rejected rather than coerced."""
    with pytest.raises(
        ValueError,
        match="Malformed built-in event of type '_context_retrieval_request_event'",
    ):
        Event.from_json(
            '{"type": "_context_retrieval_request_event", '
            '"attributes": {"query": "q", "vector_store": "vs", '
            '"max_results": "many"}}'
        )


# ── Every type-checked attribute has a dedicated mistyped-value rejection test ──


def _mistyped_attribute_cases() -> list[tuple[str, str, object]]:
    """One mistyped value per type-checked attribute of every registered type.

    Mirrors the Java ``mistypedAttributeCases``: each row overrides (required) or
    adds (optional) one attribute of that type's minimal-valid fixture with a value
    of the wrong shape, so the only schema violation is the target attribute's
    type. ``ModelRoutingEvent`` is Java-only and absent here. Untyped attributes
    (``required_untyped`` / ``optional_untyped``) are omitted because the boundary
    performs no type check on them;
    :func:`test_mistyped_attribute_cases_cover_every_typed_schema_attribute` keeps
    this table in lockstep with the schemas.
    """
    return [
        # ChatRequestEvent
        (ChatRequestEvent.EVENT_TYPE, "model", 0),
        (ChatRequestEvent.EVENT_TYPE, "messages", [0]),
        (ChatRequestEvent.EVENT_TYPE, "prompt_args", "not-a-dict"),
        # ChatResponseEvent
        (ChatResponseEvent.EVENT_TYPE, "retry_count", "not-a-number"),
        (ChatResponseEvent.EVENT_TYPE, "total_retry_wait_sec", "not-a-number"),
        # ToolRequestEvent
        (ToolRequestEvent.EVENT_TYPE, "model", 0),
        (ToolRequestEvent.EVENT_TYPE, "tool_calls", [0]),
        # ToolResponseEvent
        (ToolResponseEvent.EVENT_TYPE, "request_id", "not-a-uuid"),
        (ToolResponseEvent.EVENT_TYPE, "responses", "not-a-dict"),
        (ToolResponseEvent.EVENT_TYPE, "success", "not-a-dict"),
        (ToolResponseEvent.EVENT_TYPE, "error", "not-a-dict"),
        (ToolResponseEvent.EVENT_TYPE, "external_ids", "not-a-dict"),
        (ToolResponseEvent.EVENT_TYPE, "timestamp", "not-a-number"),
        # ContextRetrievalRequestEvent
        (ContextRetrievalRequestEvent.EVENT_TYPE, "query", 0),
        (ContextRetrievalRequestEvent.EVENT_TYPE, "vector_store", 0),
        (ContextRetrievalRequestEvent.EVENT_TYPE, "max_results", "not-a-number"),
        # ContextRetrievalResponseEvent
        (ContextRetrievalResponseEvent.EVENT_TYPE, "request_id", "not-a-uuid"),
        (ContextRetrievalResponseEvent.EVENT_TYPE, "query", 0),
        (ContextRetrievalResponseEvent.EVENT_TYPE, "documents", [0]),
    ]


@pytest.mark.parametrize(
    ("type_name", "attribute", "wrong_value"), _mistyped_attribute_cases()
)
def test_from_json_rejects_every_mistyped_builtin_attribute(
    type_name: str, attribute: str, wrong_value: object
) -> None:
    """Each type-checked attribute rejects a wrong-shaped value at the boundary."""
    attributes = {**_valid_attributes_by_type()[type_name], attribute: wrong_value}

    with pytest.raises(
        ValueError,
        match=f"Malformed built-in event of type '{type_name}'",
    ):
        Event.from_json(_event_json(type_name, attributes))


def _is_type_checked(attribute) -> bool:
    """Return True iff the boundary type-checks this attribute.

    A ``list`` or ``uuid`` attribute is always type-checked; a ``scalar`` is
    type-checked only when it declares a type (``required`` / ``optional``),
    whereas ``required_untyped`` / ``optional_untyped`` declare none and are
    checked for presence only. ``kind.value`` is one of ``scalar`` / ``list`` /
    ``uuid`` (see ``_AttrKind``).
    """
    kind = attribute.kind.value
    if kind in ("list", "uuid"):
        return True
    return kind == "scalar" and bool(attribute.types)


def _typed_schema_attributes() -> set[str]:
    """Derive the ``"<type>#<attribute>"`` keys that carry a real type check.

    Scans every ``*_ATTRIBUTE_SCHEMA`` tuple declared on each ``Event`` subclass
    under ``flink_agents.api.events``, mirroring the Java ``typedSchemaAttributes``
    reflection guard. Typed attributes are keyed by the declaring class's own
    ``EVENT_TYPE``; a shared base such as ``MemoryEvent`` declares only untyped
    schemas and has ``EVENT_TYPE is None``, so a typed attribute there would fail
    the assertion loudly instead of silently escaping coverage.
    """
    for module in pkgutil.iter_modules(events.__path__):
        importlib.import_module(f"{events.__name__}.{module.name}")

    def _subclasses(cls: type) -> list[type]:
        found: list[type] = []
        for subclass in cls.__subclasses__():
            found.append(subclass)
            found.extend(_subclasses(subclass))
        return found

    typed: set[str] = set()
    for subclass in _subclasses(Event):
        if not subclass.__module__.startswith("flink_agents.api.events"):
            continue
        event_type = subclass.__dict__.get("EVENT_TYPE")
        for name, schema in vars(subclass).items():
            if not name.endswith("_ATTRIBUTE_SCHEMA") or not isinstance(schema, tuple):
                continue
            for attribute in schema:
                if not _is_type_checked(attribute):
                    continue
                assert event_type is not None, (
                    f"typed attribute '{attribute.name}' on shared base "
                    f"{subclass.__name__} cannot be keyed to one type; extend "
                    f"_typed_schema_attributes() to its subtypes"
                )
                typed.add(f"{event_type}#{attribute.name}")
    return typed


def test_mistyped_attribute_cases_cover_every_typed_schema_attribute() -> None:
    """Guard: the mistyped table matches the type-checked schema attributes exactly."""
    covered = [
        f"{type_name}#{attribute}"
        for type_name, attribute, _ in _mistyped_attribute_cases()
    ]

    assert len(covered) == len(set(covered)), "duplicate mistyped-attribute case"
    assert set(covered) == _typed_schema_attributes()
