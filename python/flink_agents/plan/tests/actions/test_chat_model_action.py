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
from uuid import uuid4

from pydantic import BaseModel
from pyflink.common.typeinfo import BasicTypeInfo, RowTypeInfo

from flink_agents.api.agents.react_agent import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.memory_object import MemoryType
from flink_agents.plan.actions.chat_model_action import (
    _TOOL_CALL_CONTEXT,
    _TOOL_REQUEST_EVENT_CONTEXT,
    _clean_llm_response,
    _get_tool_request_event_context,
    _save_tool_request_event_context,
    _update_tool_call_context,
)
from flink_agents.runtime.tests.local_memory_object import LocalMemoryObject


def _memory() -> LocalMemoryObject:
    return LocalMemoryObject(MemoryType.SHORT_TERM, {})


def _assert_primitive(obj) -> None:
    if obj is None or isinstance(obj, bool | int | float | str | bytes):
        return
    if isinstance(obj, list):
        for item in obj:
            _assert_primitive(item)
        return
    if isinstance(obj, dict):
        for k, v in obj.items():
            assert isinstance(k, str | int | float | bool), (
                f"non-primitive key: {k!r}"
            )
            _assert_primitive(v)
        return
    msg = f"non-primitive value of type {type(obj).__name__}: {obj!r}"
    raise AssertionError(msg)


class _Result(BaseModel):
    result: int


def test_clean_llm_response_with_json_block():
    input_str = '```json\n{"key": "value"}\n```'
    expected = '{"key": "value"}'
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_generic_code_block():
    input_str = '```\n{"key": "value"}\n```'
    expected = '{"key": "value"}'
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_whitespace():
    input_str = '  ```json\n{"key": "value"}\n```  '
    expected = '{"key": "value"}'
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_without_block():
    input_str = '{"key": "value"}'
    expected = '{"key": "value"}'
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_text_around():
    input_str = 'Here is the json: ```json {"key": "value"} ```'
    expected = 'Here is the json: ```json {"key": "value"} ```'
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_multiple_lines_in_block():
    input_str = '```json\n{\n  "key": "value"\n}\n```'
    expected = '{\n  "key": "value"\n}'
    assert _clean_llm_response(input_str) == expected


def test_update_tool_call_context_stores_primitive_only():
    mem = _memory()
    initial = [ChatMessage(role=MessageRole.USER, content="hi")]
    added = [ChatMessage(role=MessageRole.ASSISTANT, content="hello")]
    _update_tool_call_context(mem, uuid4(), initial, added)
    _assert_primitive(mem.get(_TOOL_CALL_CONTEXT))


def test_update_tool_call_context_returns_chat_messages():
    mem = _memory()
    initial = [ChatMessage(role=MessageRole.USER, content="hi")]
    added = [ChatMessage(role=MessageRole.ASSISTANT, content="hello")]
    result = _update_tool_call_context(mem, uuid4(), initial, added)
    assert all(isinstance(message, ChatMessage) for message in result)
    assert [(m.role, m.content) for m in result] == [
        (MessageRole.USER, "hi"),
        (MessageRole.ASSISTANT, "hello"),
    ]


def test_tool_request_event_context_stores_primitive_only():
    mem = _memory()
    _save_tool_request_event_context(
        mem,
        uuid4(),
        uuid4(),
        "ollama",
        {"k": "v"},
        OutputSchema(output_schema=_Result),
    )
    _assert_primitive(mem.get(_TOOL_REQUEST_EVENT_CONTEXT))


def test_tool_request_event_context_round_trip():
    mem = _memory()
    event_id = uuid4()
    initial_request_id = uuid4()
    _save_tool_request_event_context(
        mem,
        event_id,
        initial_request_id,
        "ollama",
        None,
        OutputSchema(output_schema=_Result),
    )
    context = _get_tool_request_event_context(mem, event_id)
    assert context["initial_request_id"] == initial_request_id
    assert isinstance(context["initial_request_id"], type(initial_request_id))
    assert isinstance(context["output_schema"], OutputSchema)
    assert context["output_schema"].output_schema is _Result
    assert context["model"] == "ollama"


def test_get_context_none_output_schema():
    mem = _memory()
    event_id = uuid4()
    _save_tool_request_event_context(mem, event_id, uuid4(), "ollama", None, None)
    assert mem.get(_TOOL_REQUEST_EVENT_CONTEXT)[str(event_id)]["output_schema"] is None
    context = _get_tool_request_event_context(mem, event_id)
    assert context["output_schema"] is None


def test_request_event_key_match_after_normalization():
    mem = _memory()
    event_id = uuid4()
    _save_tool_request_event_context(
        mem, event_id, uuid4(), "ollama", None, None
    )
    context = _get_tool_request_event_context(mem, event_id)
    assert context != {}
    assert context["model"] == "ollama"


def test_tool_call_context_key_match_after_normalization():
    mem = _memory()
    request_id = uuid4()
    initial = [ChatMessage(role=MessageRole.USER, content="hi")]
    _update_tool_call_context(mem, request_id, initial, [])
    extra = ChatMessage(role=MessageRole.TOOL, content="result")
    result = _update_tool_call_context(mem, request_id, None, [extra])
    assert len(result) == 2
    assert len(mem.get(_TOOL_CALL_CONTEXT)[str(request_id)]) == 2


def test_output_schema_rowtypeinfo_round_trip():
    mem = _memory()
    event_id = uuid4()
    schema = OutputSchema(
        output_schema=RowTypeInfo(
            [BasicTypeInfo.INT_TYPE_INFO()],
            ["result"],
        )
    )
    _save_tool_request_event_context(
        mem, event_id, uuid4(), "ollama", None, schema
    )
    context = _get_tool_request_event_context(mem, event_id)
    assert isinstance(context["output_schema"], OutputSchema)
    assert context["output_schema"].output_schema.get_field_names() == ["result"]


def test_save_get_preserves_model_and_prompt_args():
    mem = _memory()
    event_id = uuid4()
    prompt_args = {"a": 1, "b": "x"}
    _save_tool_request_event_context(
        mem, event_id, uuid4(), "ollama", prompt_args, None
    )
    context = _get_tool_request_event_context(mem, event_id)
    assert context["model"] == "ollama"
    assert context["prompt_args"] == prompt_args
