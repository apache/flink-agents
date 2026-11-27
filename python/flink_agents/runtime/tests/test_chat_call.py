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

import pytest

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_result import ChatResult, TokenUsage
from flink_agents.api.events.chat_event import ChatResponseError, ChatResponseEvent
from flink_agents.api.runner_context import DurableFuture
from flink_agents.runtime.tests.future_test_utils import (  # noqa: F401
    RuntimeContext,
    SubgraphBridge,
    close_runtime_contexts,
)


class Bridge(SubgraphBridge):
    @property
    def response(self):
        return self.responses.get("chat:0")

    @response.setter
    def response(self, value) -> None:
        self.responses["chat:0"] = value.model_dump_json()


def context():
    bridge = Bridge()
    return RuntimeContext(bridge), bridge


def test_lazy_snapshot_yield_and_repeated_await():
    ctx, bridge = context()
    messages = [ChatMessage.user("hello")]
    future = ctx.chat("model", messages, prompt_args={"name": "Ada"})
    assert isinstance(future, DurableFuture)
    ctx.gather(future)
    messages.clear()
    assert bridge.preparations == 0
    waiter = future.__await__()
    assert next(waiter) is None
    assert bridge.request.messages[0].text == "hello"
    assert bridge.request.prompt_args == {"name": "Ada"}
    assert next(waiter) is None
    expected = ChatResult(
        message=ChatMessage.assistant("answer"),
        model="test-model",
        response_id="response-1",
        usage=TokenUsage(prompt_tokens=11, completion_tokens=7),
        finish_reason="stop",
        metadata={"provider": "test"},
    )
    bridge.response = ChatResponseEvent.success(uuid4(), expected)
    with pytest.raises(StopIteration) as result:
        next(waiter)
    assert result.value.value == expected
    operations = list(bridge.operations)
    with pytest.raises(StopIteration) as repeated:
        next(future.__await__())
    assert repeated.value.value is result.value.value
    assert bridge.preparations == 1
    assert bridge.operations == operations
    assert future._is_done()


def test_failed_response_raises_on_each_await_without_restarting():
    ctx, bridge = context()
    future = ctx.chat("model", [])
    bridge.response = ChatResponseEvent.failed(uuid4(), "provider refused")
    for _ in range(2):
        with pytest.raises(ChatResponseError, match="provider refused"):
            next(future.__await__())
    assert bridge.preparations == 1
    assert future._is_done()


def test_cancelled_wait_does_not_synthesize_a_failed_response():
    ctx, bridge = context()
    future = ctx.chat("model", [])
    waiter = future.__await__()
    next(waiter)
    with pytest.raises(KeyboardInterrupt):
        waiter.throw(KeyboardInterrupt())
    assert not future._is_done()
    assert bridge.response is None


def test_bridge_failure_is_not_cached_as_chat_outcome(monkeypatch):
    ctx, bridge = context()
    future = ctx.chat("model", [])

    def fail_poll(call_id) -> None:
        msg = "state unavailable"
        raise RuntimeError(msg)

    monkeypatch.setattr(bridge, "isSubgraphCallReady", fail_poll)
    with pytest.raises(RuntimeError, match="Subgraph call failed"):
        next(future.__await__())
    assert not future._is_done()
    assert bridge.preparations == 1
