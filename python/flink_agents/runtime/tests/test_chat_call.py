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
from flink_agents.api.events.chat_event import ChatResponseError, ChatResponseEvent
from flink_agents.api.events.event import Event
from flink_agents.api.runner_context import DurableFuture
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


class Bridge:
    """Only the narrow mailbox-confined chat bridge; no executor available."""

    def __init__(self) -> None:
        self.request = None
        self.response = None
        self.starts = 0
        self.polls = 0

    def getChatContext(self):
        return self

    def getManager(self):
        return self

    def prepareCall(self, ctx, request_json):
        assert ctx is self
        self.starts += 1
        self.request = Event.from_json(request_json)
        return "call-1"

    def tryCompleteCall(self, ctx, call_id):
        assert ctx is self
        assert call_id == "call-1"
        self.polls += 1
        return self.response.model_dump_json() if self.response else None


def context():
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._j_runner_context = Bridge()
    return ctx, ctx._j_runner_context


def test_lazy_snapshot_yield_and_repeated_await():
    ctx, bridge = context()
    messages = [ChatMessage.user("hello")]
    future = ctx.chat("model", messages, prompt_args={"name": "Ada"})
    assert isinstance(future, DurableFuture)
    messages.clear()
    assert bridge.starts == 0
    waiter = future.__await__()
    assert next(waiter) is None
    assert bridge.request.messages[0].text == "hello"
    assert bridge.request.prompt_args == {"name": "Ada"}
    assert next(waiter) is None
    bridge.response = ChatResponseEvent.success(
        uuid4(), ChatMessage.assistant("answer")
    )
    with pytest.raises(StopIteration) as result:
        next(waiter)
    assert result.value.value.text == "answer"
    polls = bridge.polls
    with pytest.raises(StopIteration) as repeated:
        next(future.__await__())
    assert repeated.value.value is result.value.value
    assert bridge.starts == 1
    assert bridge.polls == polls
    assert future._is_done()


def test_failed_response_raises_on_each_await_without_restarting():
    ctx, bridge = context()
    future = ctx.chat("model", [])
    bridge.response = ChatResponseEvent.failed(uuid4(), "provider refused")
    for _ in range(2):
        with pytest.raises(ChatResponseError, match="provider refused"):
            next(future.__await__())
    assert bridge.starts == 1
    assert bridge.polls == 1
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


def test_gather_rejects_chat_handle_before_bootstrap():
    ctx, bridge = context()
    future = ctx.chat("model", [])
    with pytest.raises(TypeError, match="gather only accepts"):
        ctx.gather(future)
    assert bridge.starts == 0


def test_bridge_failure_is_not_cached_as_chat_outcome(monkeypatch):
    ctx, bridge = context()
    future = ctx.chat("model", [])

    def fail_poll(ctx, call_id) -> None:
        msg = "state unavailable"
        raise RuntimeError(msg)

    monkeypatch.setattr(bridge, "tryCompleteCall", fail_poll)
    with pytest.raises(RuntimeError, match="state unavailable"):
        next(future.__await__())
    assert not future._is_done()
    assert bridge.starts == 1
