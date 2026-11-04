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
#  limitations under the License.
#################################################################################
"""Built-in actions echo optional request context onto the matching response."""

import asyncio
import json
from typing import Any
from unittest.mock import MagicMock

import pytest

from flink_agents.api.chat_message import ChatMessage, ToolCallBlock
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
)
from flink_agents.api.events.event import Event
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.tools.tool_response import ToolResponse
from flink_agents.api.trace import ExecutionReporter
from flink_agents.api.vector_stores.vector_store import Document, VectorStoreQuery
from flink_agents.plan.actions.chat_model_action import (
    process_chat_request_or_tool_response,
)
from flink_agents.plan.actions.context_retrieval_action import (
    process_context_retrieval_request,
)


class _Memory:
    def __init__(self) -> None:
        self._store: dict[str, Any] = {}

    def get(self, path: str) -> Any:
        return self._store.get(path)

    def set(self, path: str, value: Any) -> None:
        self._store[path] = value


def _runner(chat_model: Any, max_retries: int = 0) -> tuple[MagicMock, list, _Memory]:
    sent: list = []
    memory = _Memory()
    chat_model.model = "configured-model"
    chat_model.will_apply_native_structured_output = MagicMock(return_value=False)
    config = MagicMock()
    options = {
        id(AgentExecutionOptions.MAX_RETRIES): max_retries,
        id(AgentExecutionOptions.RETRY_WAIT_INTERVAL): 0,
        id(AgentExecutionOptions.CHAT_ASYNC): False,
    }
    config.get = MagicMock(
        side_effect=lambda option: options.get(id(option), option.get_default_value())
    )
    ctx = MagicMock(spec=ExecutionReporter)
    ctx.config = config
    ctx.sensory_memory = memory
    ctx.action_metric_group = None
    ctx.send_event = MagicMock(side_effect=sent.append)
    ctx.get_resource = MagicMock(return_value=chat_model)
    ctx.durable_execute = MagicMock(
        side_effect=lambda fn, *args, durable_id=None, **kwargs: fn(*args, **kwargs)
    )

    async def _async(
        fn: Any, *args: Any, durable_id: str | None = None, **kwargs: Any
    ) -> Any:
        return fn(*args, **kwargs)

    ctx.durable_execute_async = MagicMock(side_effect=_async)
    return ctx, sent, memory


def _text(text: str) -> ChatResult:
    return ChatResult(message=ChatMessage.assistant(text))


def _tool_result() -> ChatResult:
    return ChatResult(
        message=ChatMessage.assistant(
            [ToolCallBlock(call_id="call-1", name="lookup", input={"x": 1})]
        )
    )


def _request(text: str, prompt_args: dict, context: Any | None) -> ChatRequestEvent:
    event = ChatRequestEvent(
        model="test-model",
        messages=[ChatMessage.user(text)],
        prompt_args=prompt_args,
        context=context,
    )
    event.set_attachment("trace", "keep-on-request")
    return event


def test_omitted_context_stays_off_the_response() -> None:
    chat_model = MagicMock()
    chat_model.chat = MagicMock(return_value=_text("hello"))
    ctx, sent, _ = _runner(chat_model)
    request = ChatRequestEvent(
        model="test-model",
        messages=[ChatMessage.user("hi")],
        prompt_args={"tone": "brief"},
    )

    asyncio.run(process_chat_request_or_tool_response(request, ctx))

    response = sent[0]
    assert isinstance(response, ChatResponseEvent)
    assert response.context is None
    assert "context" not in response.attributes
    assert response.request_id == request.id
    assert response.attachments == {}


def test_success_failure_and_retry_echo_context_without_touching_the_prompt() -> None:
    context = {"task": "summary", "item_id": "42", "n": 1, "ok": True, "tags": ["a"]}
    prompt_args = {"tone": "brief"}
    chat_model = MagicMock()
    chat_model.chat = MagicMock(side_effect=[RuntimeError("transient"), _text("done")])
    ctx, sent, _ = _runner(chat_model, max_retries=1)
    request = _request("hi", prompt_args, context)

    asyncio.run(process_chat_request_or_tool_response(request, ctx))

    response = sent[0]
    assert response.is_success
    assert response.context == context
    assert response.retry_count == 1
    assert response.attachments == {}
    assert response.request_id == request.id
    for call in chat_model.chat.call_args_list:
        assert call.kwargs == {"prompt_args": prompt_args}
        assert call.args[0][0].text == "hi"
        assert "summary" not in call.args[0][0].text

    chat_model.chat = MagicMock(side_effect=RuntimeError("persistent"))
    failing = _request("hi", prompt_args, context)
    sent.clear()
    asyncio.run(process_chat_request_or_tool_response(failing, ctx))
    failed = sent[0]
    assert failed.is_failed
    assert "persistent" in failed.error
    assert failed.context == context
    assert failed.request_id == failing.id


def test_tool_rounds_keep_each_request_context() -> None:
    context_a = {"task": "summary", "item_id": "42"}
    context_b = {"task": "risk_assessment", "item_id": "42"}
    chat_model = MagicMock()
    chat_model.chat = MagicMock(
        side_effect=[
            _tool_result(),
            _tool_result(),
            _text("risk done"),
            _text("summary done"),
        ]
    )
    ctx, sent, memory = _runner(chat_model)
    request_a = _request("a", {"who": "a"}, context_a)
    request_b = _request("b", {"who": "b"}, context_b)

    asyncio.run(process_chat_request_or_tool_response(request_a, ctx))
    asyncio.run(process_chat_request_or_tool_response(request_b, ctx))
    tool_a = sent[0]
    tool_b = sent[1]
    assert isinstance(tool_a, ToolRequestEvent)
    assert tool_a.context is None
    assert tool_b.context is None
    assert tool_a.tool_calls[0].input == {"x": 1}
    assert tool_a.attachments == {}

    parked = memory.get("_TOOL_REQUEST_EVENT_CONTEXT")
    parked[str(tool_b.id)]["context"] = json.loads(
        json.dumps(parked[str(tool_b.id)]["context"])
    )

    def _tool_response(tool_request: ToolRequestEvent) -> ToolResponseEvent:
        return ToolResponseEvent(
            request_id=tool_request.id,
            responses={"call-1": ToolResponse.text("ok")},
            success={"call-1": True},
            error={},
        )

    asyncio.run(process_chat_request_or_tool_response(_tool_response(tool_b), ctx))
    asyncio.run(process_chat_request_or_tool_response(_tool_response(tool_a), ctx))

    response_b = sent[2]
    response_a = sent[3]
    assert response_b.request_id == request_b.id
    assert response_b.context == context_b
    assert response_b.response.text == "risk done"
    assert response_a.request_id == request_a.id
    assert response_a.context == context_a
    assert response_a.response.text == "summary done"
    assert response_a.attachments == {}
    for call in chat_model.chat.call_args_list:
        assert "context" not in call.kwargs
        assert set(call.kwargs) == {"prompt_args"}


def test_restored_request_still_echoes_context() -> None:
    chat_model = MagicMock()
    chat_model.chat = MagicMock(return_value=_text("ok"))
    ctx, sent, _ = _runner(chat_model)
    original = ChatRequestEvent(
        model="test-model",
        messages=[ChatMessage.user("hi")],
        context={"task": "summary", "item_id": "42"},
    )
    restored = Event.from_json(original.model_dump_json())

    asyncio.run(process_chat_request_or_tool_response(restored, ctx))

    assert sent[0].request_id == original.id
    assert sent[0].context == original.context


def test_retrieval_echoes_context_and_leaves_the_query_unchanged() -> None:
    seen: list[VectorStoreQuery] = []

    def query(vector_query: VectorStoreQuery) -> Any:
        seen.append(vector_query)
        return MagicMock(documents=[Document(content="body", id="d1")])

    ctx = MagicMock()
    ctx.config.get = MagicMock(return_value=False)
    ctx.get_resource = MagicMock(return_value=MagicMock(query=query))
    ctx.durable_execute = MagicMock(
        side_effect=lambda fn, *args, durable_id=None, **kwargs: fn(*args, **kwargs)
    )
    sent: list = []
    ctx.send_event = MagicMock(side_effect=sent.append)
    context = {"task": "retrieve", "item_id": "42"}
    request = ContextRetrievalRequestEvent(
        query="what is flink", vector_store="store", max_results=2, context=context
    )
    request.set_attachment("trace", "keep-on-request")

    asyncio.run(process_context_retrieval_request(request, ctx))

    response = sent[0]
    assert isinstance(response, ContextRetrievalResponseEvent)
    assert response.request_id == request.id
    assert response.query == "what is flink"
    assert response.context == context
    assert response.attachments == {}
    assert seen[0].query_text == "what is flink"
    assert seen[0].limit == 2
    ctx.get_resource.assert_called_once_with("store", ResourceType.VECTOR_STORE)


def test_retrieval_omits_context_when_the_request_does() -> None:
    ctx = MagicMock()
    ctx.config.get = MagicMock(return_value=False)
    ctx.get_resource = MagicMock(
        return_value=MagicMock(query=MagicMock(return_value=MagicMock(documents=[])))
    )
    ctx.durable_execute = MagicMock(
        side_effect=lambda fn, *args, durable_id=None, **kwargs: fn(*args, **kwargs)
    )
    sent: list = []
    ctx.send_event = MagicMock(side_effect=sent.append)

    asyncio.run(
        process_context_retrieval_request(
            ContextRetrievalRequestEvent(query="q", vector_store="store"), ctx
        )
    )

    assert sent[0].context is None
    assert "context" not in sent[0].attributes


def test_retrieval_query_failure_propagates_without_a_response() -> None:
    def query(vector_query: VectorStoreQuery) -> Any:
        msg = "store down"
        raise RuntimeError(msg)

    ctx = MagicMock()
    ctx.config.get = MagicMock(return_value=False)
    ctx.get_resource = MagicMock(return_value=MagicMock(query=query))
    ctx.durable_execute = MagicMock(
        side_effect=lambda fn, *args, durable_id=None, **kwargs: fn(*args, **kwargs)
    )
    sent: list = []
    ctx.send_event = MagicMock(side_effect=sent.append)
    request = ContextRetrievalRequestEvent(
        query="q", vector_store="store", context={"task": "retrieve"}
    )

    with pytest.raises(RuntimeError, match="store down"):
        asyncio.run(process_context_retrieval_request(request, ctx))

    assert sent == []
    assert request.context == {"task": "retrieve"}
