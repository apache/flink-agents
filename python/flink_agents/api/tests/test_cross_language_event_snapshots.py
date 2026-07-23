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
"""Cross-language event SerDe snapshot tests."""

import json
import os
from pathlib import Path
from typing import ClassVar
from uuid import UUID

import pytest

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.vector_stores.vector_store import Document

_REPO_ROOT = Path(__file__).resolve().parents[4]
_SNAPSHOT_DIR = _REPO_ROOT / "e2e-test" / "cross-language-event-snapshots"

_FIXED_EVENT_ID = UUID("00000000-0000-0000-0000-000000000001")
_FIXED_REQUEST_ID = UUID("00000000-0000-0000-0000-000000000002")
_FIXED_TOOL_CALL_ID = "call_aaaa"
_FIXED_TOOL_CALL_ID_NUMERIC = "call_bbbb"
_FIXED_TOOL_CALL_ID_BOOL = "call_cccc"


def _regenerate_enabled() -> bool:
    return os.environ.get("REGENERATE_SNAPSHOTS", "").lower() in {"1", "true", "yes"}


def _force_id(event: Event, fixed_id: UUID) -> Event:
    return event.model_copy(update={"id": fixed_id})


def _write_python_snapshot(name: str, event: Event) -> None:
    target = _SNAPSHOT_DIR / "python" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(event.model_dump_json(indent=2) + "\n")


def _assert_python_snapshot_stable(name: str, event: Event) -> None:
    actual = json.loads(event.model_dump_json())
    committed_path = _SNAPSHOT_DIR / "python" / name
    assert committed_path.exists(), (
        f"Python snapshot {name} missing from {committed_path}. "
        f"If you added a new event, regenerate with REGENERATE_SNAPSHOTS=1 "
        f"and commit alongside the test."
    )
    expected = json.loads(committed_path.read_text())
    assert actual == expected, (
        f"Python serialization of {name} drifted from committed snapshot."
    )


def _read_java_snapshot(name: str) -> Event:
    java_snapshot = _SNAPSHOT_DIR / "java" / name
    assert java_snapshot.exists(), (
        f"Java snapshot {name} missing from {java_snapshot}. "
        f"Regenerate the Java side with -Dregenerate.snapshots=true "
        f"and commit alongside this test."
    )
    return Event.from_json(java_snapshot.read_text())


# ── InputEvent ──────────────────────────────────────────────────────────


def _build_input_event() -> InputEvent:
    return _force_id(InputEvent(input="hello"), _FIXED_EVENT_ID)


def test_regenerate_input_event_python_snapshot() -> None:
    if not _regenerate_enabled():
        pytest.skip("Set REGENERATE_SNAPSHOTS=1 to refresh.")
    _write_python_snapshot("input_event.json", _build_input_event())


def test_input_event_python_snapshot_is_stable() -> None:
    _assert_python_snapshot_stable("input_event.json", _build_input_event())


def test_python_can_deserialize_input_event_from_java_snapshot() -> None:
    base = _read_java_snapshot("input_event.json")
    typed = InputEvent.from_event(base)
    assert typed.input == "hello", "InputEvent.input mismatch."
    assert typed.type == InputEvent.EVENT_TYPE


# ── OutputEvent ─────────────────────────────────────────────────────────


def _build_output_event() -> OutputEvent:
    return _force_id(OutputEvent(output="world"), _FIXED_EVENT_ID)


def test_regenerate_output_event_python_snapshot() -> None:
    if not _regenerate_enabled():
        pytest.skip("Set REGENERATE_SNAPSHOTS=1 to refresh.")
    _write_python_snapshot("output_event.json", _build_output_event())


def test_output_event_python_snapshot_is_stable() -> None:
    _assert_python_snapshot_stable("output_event.json", _build_output_event())


def test_python_can_deserialize_output_event_from_java_snapshot() -> None:
    base = _read_java_snapshot("output_event.json")
    typed = OutputEvent.from_event(base)
    assert typed.output == "world", "OutputEvent.output mismatch."
    assert typed.type == OutputEvent.EVENT_TYPE


# ── ChatRequestEvent ────────────────────────────────────────────────────


def _build_chat_request_event() -> ChatRequestEvent:
    event = ChatRequestEvent(
        model="test-model",
        messages=[ChatMessage(role=MessageRole.USER, content="hello world")],
    )
    return _force_id(event, _FIXED_EVENT_ID)


def test_regenerate_chat_request_event_python_snapshot() -> None:
    if not _regenerate_enabled():
        pytest.skip("Set REGENERATE_SNAPSHOTS=1 to refresh.")
    _write_python_snapshot("chat_request_event.json", _build_chat_request_event())


def test_chat_request_event_python_snapshot_is_stable() -> None:
    _assert_python_snapshot_stable(
        "chat_request_event.json", _build_chat_request_event()
    )


def test_python_can_deserialize_chat_request_event_from_java_snapshot() -> None:
    base = _read_java_snapshot("chat_request_event.json")
    typed = ChatRequestEvent.from_event(base)
    assert typed.model == "test-model"
    assert len(typed.messages) == 1
    msg = typed.messages[0]
    assert msg.role == MessageRole.USER, f"Role mismatch: got {msg.role!r}"
    assert msg.content == "hello world"


def test_chat_request_row_type_info_output_schema_is_not_portable_across_languages_known_gap() -> None:
    """Known 0.3 gap — RowTypeInfo-typed output_schema does not round-trip across the language
    boundary. Python emits ``{"names": [...], "types": [<BasicTypeInfo ordinal>]}`` while Java
    emits ``{"fieldNames": [...], "types": [<Class>]}``, so a ChatRequestEvent carrying a
    RowTypeInfo schema cannot be deserialized on the other side. The BaseModel (Pydantic class)
    branch is symmetric and works. Reconciling the RowTypeInfo wire format requires a canonical
    shape + bilateral OutputSchema serdes shims; tracked as a follow-up.
    """
    from pyflink.common.typeinfo import BasicTypeInfo, RowTypeInfo

    from flink_agents.api.agents.types import OutputSchema

    schema = OutputSchema(
        output_schema=RowTypeInfo(
            field_types=[BasicTypeInfo.STRING_TYPE_INFO()],
            field_names=["name"],
        ),
    )
    event = ChatRequestEvent(
        model="test-model",
        messages=[ChatMessage(role=MessageRole.USER, content="hi")],
        output_schema=schema,
    )
    payload = event.model_dump_json()
    # Pin Python's local shape so a future regression can't silently change it. The gap with
    # Java's `{"fieldNames": ...}` shape is the documented limitation, not the assertion.
    assert "\"names\"" in payload
    assert "\"fieldNames\"" not in payload


# ── ChatResponseEvent ───────────────────────────────────────────────────


def _build_chat_response_event() -> ChatResponseEvent:
    event = ChatResponseEvent(
        request_id=_FIXED_REQUEST_ID,
        response=ChatMessage(role=MessageRole.ASSISTANT, content="hi there"),
    )
    return _force_id(event, _FIXED_EVENT_ID)


def test_regenerate_chat_response_event_python_snapshot() -> None:
    if not _regenerate_enabled():
        pytest.skip("Set REGENERATE_SNAPSHOTS=1 to refresh.")
    _write_python_snapshot("chat_response_event.json", _build_chat_response_event())


def test_chat_response_event_python_snapshot_is_stable() -> None:
    _assert_python_snapshot_stable(
        "chat_response_event.json", _build_chat_response_event()
    )


def test_python_can_deserialize_chat_response_event_from_java_snapshot() -> None:
    base = _read_java_snapshot("chat_response_event.json")
    typed = ChatResponseEvent.from_event(base)
    expected_request_id = str(_FIXED_REQUEST_ID)
    actual_request_id = (
        str(typed.request_id) if not isinstance(typed.request_id, str) else typed.request_id
    )
    assert actual_request_id == expected_request_id, "request_id mismatch."
    assert typed.response is not None, "response is None."
    assert typed.response.role == MessageRole.ASSISTANT, (
        f"Response role mismatch: got {typed.response.role!r}"
    )
    assert typed.response.content == "hi there"


# ── ToolRequestEvent ────────────────────────────────────────────────────


def _build_tool_request_event() -> ToolRequestEvent:
    tool_call = {"id": _FIXED_TOOL_CALL_ID, "name": "echo", "arguments": {"value": "ping"}}
    event = ToolRequestEvent(model="test-model", tool_calls=[tool_call])
    return _force_id(event, _FIXED_EVENT_ID)


def test_regenerate_tool_request_event_python_snapshot() -> None:
    if not _regenerate_enabled():
        pytest.skip("Set REGENERATE_SNAPSHOTS=1 to refresh.")
    _write_python_snapshot("tool_request_event.json", _build_tool_request_event())


def test_tool_request_event_python_snapshot_is_stable() -> None:
    _assert_python_snapshot_stable(
        "tool_request_event.json", _build_tool_request_event()
    )


def test_python_can_deserialize_tool_request_event_from_java_snapshot() -> None:
    base = _read_java_snapshot("tool_request_event.json")
    typed = ToolRequestEvent.from_event(base)
    assert typed.model == "test-model"
    assert len(typed.tool_calls) == 1
    assert typed.tool_calls[0]["id"] == _FIXED_TOOL_CALL_ID


# ── ToolResponseEvent ───────────────────────────────────────────────────


def _build_tool_response_event() -> ToolResponseEvent:
    # Mixed scalar value types pin the Python -> Java round-trip on the Java
    # ToolResponseEvent.fromEvent fall-through that wraps non-ToolResponse/Map
    # values via ToolResponse.success(v).
    event = ToolResponseEvent(
        request_id=_FIXED_REQUEST_ID,
        responses={
            _FIXED_TOOL_CALL_ID: "pong",
            _FIXED_TOOL_CALL_ID_NUMERIC: 42,
            _FIXED_TOOL_CALL_ID_BOOL: True,
        },
        external_ids={
            _FIXED_TOOL_CALL_ID: None,
            _FIXED_TOOL_CALL_ID_NUMERIC: None,
            _FIXED_TOOL_CALL_ID_BOOL: None,
        },
    )
    return _force_id(event, _FIXED_EVENT_ID)


def test_regenerate_tool_response_event_python_snapshot() -> None:
    if not _regenerate_enabled():
        pytest.skip("Set REGENERATE_SNAPSHOTS=1 to refresh.")
    _write_python_snapshot("tool_response_event.json", _build_tool_response_event())


def test_tool_response_event_python_snapshot_is_stable() -> None:
    _assert_python_snapshot_stable(
        "tool_response_event.json", _build_tool_response_event()
    )


def test_python_can_deserialize_java_tool_response_event_status_fields() -> None:
    base = _read_java_snapshot("tool_response_event.json")
    typed = ToolResponseEvent.from_event(base)

    assert typed.request_id == _FIXED_REQUEST_ID
    assert typed.success[_FIXED_TOOL_CALL_ID] is True
    assert typed.error == {}

    response_value = typed.responses[_FIXED_TOOL_CALL_ID]
    assert isinstance(response_value, dict)
    assert "result" in response_value

    assert "timestamp" not in typed.attributes


# ── ContextRetrievalRequestEvent ────────────────────────────────────────


def _build_context_retrieval_request_event() -> ContextRetrievalRequestEvent:
    event = ContextRetrievalRequestEvent(
        query="what is flink",
        vector_store="test-store",
        max_results=5,
    )
    return _force_id(event, _FIXED_EVENT_ID)


def test_regenerate_context_retrieval_request_event_python_snapshot() -> None:
    if not _regenerate_enabled():
        pytest.skip("Set REGENERATE_SNAPSHOTS=1 to refresh.")
    _write_python_snapshot(
        "context_retrieval_request_event.json",
        _build_context_retrieval_request_event(),
    )


def test_context_retrieval_request_event_python_snapshot_is_stable() -> None:
    _assert_python_snapshot_stable(
        "context_retrieval_request_event.json",
        _build_context_retrieval_request_event(),
    )


def test_python_can_deserialize_context_retrieval_request_event_from_java_snapshot() -> None:
    base = _read_java_snapshot("context_retrieval_request_event.json")
    typed = ContextRetrievalRequestEvent.from_event(base)
    assert typed.query == "what is flink"
    assert typed.vector_store == "test-store"
    assert typed.max_results == 5


# ── ContextRetrievalResponseEvent ───────────────────────────────────────


def _build_context_retrieval_response_event() -> ContextRetrievalResponseEvent:
    doc = Document(content="doc content", metadata={"k": "v"}, id="doc-1")
    event = ContextRetrievalResponseEvent(
        request_id=_FIXED_REQUEST_ID,
        query="what is flink",
        documents=[doc],
    )
    return _force_id(event, _FIXED_EVENT_ID)


def test_regenerate_context_retrieval_response_event_python_snapshot() -> None:
    if not _regenerate_enabled():
        pytest.skip("Set REGENERATE_SNAPSHOTS=1 to refresh.")
    _write_python_snapshot(
        "context_retrieval_response_event.json",
        _build_context_retrieval_response_event(),
    )


def test_context_retrieval_response_event_python_snapshot_is_stable() -> None:
    _assert_python_snapshot_stable(
        "context_retrieval_response_event.json",
        _build_context_retrieval_response_event(),
    )


def test_python_can_deserialize_context_retrieval_response_event_from_java_snapshot() -> None:
    base = _read_java_snapshot("context_retrieval_response_event.json")
    typed = ContextRetrievalResponseEvent.from_event(base)
    expected_request_id = str(_FIXED_REQUEST_ID)
    actual_request_id = (
        str(typed.request_id) if not isinstance(typed.request_id, str) else typed.request_id
    )
    assert actual_request_id == expected_request_id
    assert typed.query == "what is flink"
    assert len(typed.documents) == 1
    assert typed.documents[0].content == "doc content"
    assert typed.documents[0].id == "doc-1"


# ── Generic Event with primitive attributes (user-authored axis) ───────


_GENERIC_EVENT_TYPE = "_my_custom_event"
_GENERIC_EVENT_ATTRS = {
    "k_int": 42,
    "k_float": 1.5,
    "k_bool": True,
    "k_str": "hello",
    "k_null": None,
    "k_list": [1, 2, 3],
    "k_dict": {"nested": "value"},
}


def _build_generic_event() -> Event:
    return _force_id(
        Event(type=_GENERIC_EVENT_TYPE, attributes=dict(_GENERIC_EVENT_ATTRS)),
        _FIXED_EVENT_ID,
    )


def test_regenerate_generic_event_python_snapshot() -> None:
    if not _regenerate_enabled():
        pytest.skip("Set REGENERATE_SNAPSHOTS=1 to refresh.")
    _write_python_snapshot("generic_event_with_attrs.json", _build_generic_event())


def test_generic_event_python_snapshot_is_stable() -> None:
    _assert_python_snapshot_stable(
        "generic_event_with_attrs.json", _build_generic_event()
    )


def test_python_can_deserialize_generic_event_from_java_snapshot() -> None:
    base = _read_java_snapshot("generic_event_with_attrs.json")

    assert base.type == _GENERIC_EVENT_TYPE
    assert base.attributes["k_int"] == 42
    assert isinstance(base.attributes["k_int"], int)
    assert base.attributes["k_float"] == 1.5
    assert isinstance(base.attributes["k_float"], float)
    assert base.attributes["k_bool"] is True
    assert base.attributes["k_str"] == "hello"
    assert base.attributes["k_null"] is None
    assert base.attributes["k_list"] == [1, 2, 3]
    assert base.attributes["k_dict"] == {"nested": "value"}


# ── Python-only subclass with no Java counterpart (graceful fallback) ──


class _MyPythonOnlyEvent(Event):
    EVENT_TYPE: ClassVar[str] = "_my_python_only_event"

    def __init__(self, value: str, count: int) -> None:
        super().__init__(
            type=_MyPythonOnlyEvent.EVENT_TYPE,
            attributes={"value": value, "count": count},
        )


def _build_python_only_subclass_event() -> _MyPythonOnlyEvent:
    return _force_id(
        _MyPythonOnlyEvent(value="ping", count=7),
        _FIXED_EVENT_ID,
    )


def test_regenerate_python_only_subclass_event_snapshot() -> None:
    if not _regenerate_enabled():
        pytest.skip("Set REGENERATE_SNAPSHOTS=1 to refresh.")
    _write_python_snapshot(
        "python_only_subclass_event.json", _build_python_only_subclass_event()
    )


def test_python_only_subclass_event_snapshot_is_stable() -> None:
    _assert_python_snapshot_stable(
        "python_only_subclass_event.json", _build_python_only_subclass_event()
    )


# ── Smoke ───────────────────────────────────────────────────────────────


def test_snapshot_directory_exists() -> None:
    assert _SNAPSHOT_DIR.is_dir(), f"Expected snapshot directory at {_SNAPSHOT_DIR}"
