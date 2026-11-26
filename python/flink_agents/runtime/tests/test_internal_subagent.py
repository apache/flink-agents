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
################################################################################
"""Internal and external subagents share the mixed-future scheduler."""

import json
from concurrent.futures import CancelledError
from datetime import timedelta
from uuid import uuid4

import pytest

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.chat_event import ChatResponseEvent
from flink_agents.api.subagent import AsyncFuture, SubagentResult
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.runtime.async_future import SingleDurableFuture, map_future
from flink_agents.runtime.internal_subagent import InternalSubagentSetup
from flink_agents.runtime.subagent_handles import SubagentFutureImpl
from flink_agents.runtime.tests.future_test_utils import (  # noqa: F401
    RuntimeContext,
    SubgraphBridge,
    close_runtime_contexts,
)
from flink_agents.runtime.tests.test_base_subagent import _run


def internal(ctx, call_id="one"):
    setup = InternalSubagentSetup(scope="reviewer", plan=AgentPlan(actions={}))
    return _run(setup.submit(ctx, "hello", "session", call_id))


def result_json(success=True):
    return json.dumps(
        {
            "success": success,
            "result": "answer" if success else None,
            "errorMessage": None if success else "child failed",
        }
    )


def test_internal_submit_is_lazy_and_polls_on_the_mailbox():
    bridge = SubgraphBridge()
    ctx = RuntimeContext(bridge)
    future = internal(ctx)
    assert isinstance(future, AsyncFuture)
    assert bridge.call_results == []
    waiter = future.__await__()
    next(waiter)
    assert bridge.started == ["session#one"]
    assert bridge.call_results[0].status == "PENDING"
    bridge.responses["session#one"] = result_json()
    with pytest.raises(StopIteration) as result:
        next(waiter)
    assert result.value.value.result == "answer"
    assert _run(future).result == "answer"
    assert bridge.closed == ["session#one"]


@pytest.mark.parametrize("success", [False, True])
def test_internal_terminal_result_replays_without_starting_child(success):
    bridge = SubgraphBridge()
    ctx = RuntimeContext(bridge)
    bridge.responses["session#one"] = result_json(success)
    assert _run(internal(ctx)).success is success
    assert bridge.started == ["session#one"]
    bridge.current_call_index = 0
    bridge.responses.clear()
    bridge.started.clear()
    replay = _run(internal(RuntimeContext(bridge)))
    assert replay.success is success
    assert bridge.started == []
    assert len(bridge.call_results) == 1


def test_mixed_gather_reserves_in_input_order_and_replays_durable_children():
    bridge = SubgraphBridge()
    counts = {"ordinary": 0, "durable": 0, "prepare": 0}

    def ordinary() -> str:
        counts["ordinary"] += 1
        return "query"

    def durable() -> str:
        counts["durable"] += 1
        return "durable"

    def preparation() -> tuple:
        counts["prepare"] += 1
        return lambda: SubagentResult.ok("deferred"), None

    bridge.responses["session#one"] = result_json()
    bridge.responses["chat:2"] = ChatResponseEvent.success(
        uuid4(), ChatResult(message=ChatMessage.assistant("chat"))
    ).model_dump_json()
    for replay in (False, True):
        bridge.current_call_index = 0
        bridge.started.clear()
        ctx = RuntimeContext(bridge)
        futures = (
            ctx.durable_execute_async(durable, durable_id="durable"),
            internal(ctx),
            ctx.execute_async(ordinary),
            ctx.chat("model", []),
            SubagentFutureImpl(
                "external", "one", SingleDurableFuture(ctx, "external#one", preparation)
            ),
        )
        outcomes = _run(ctx.gather(*futures))
        assert all(outcome.is_success() for outcome in outcomes)
        assert outcomes[0].value == "durable"
        assert outcomes[1].value.result == "answer"
        assert outcomes[2].value == "query"
        assert outcomes[3].value.message.text == "chat"
        assert outcomes[4].value.result == "deferred"
        assert [call.function_id for call in bridge.call_results] == [
            "durable",
            "session#one",
            "chat:2",
            "external#one",
        ]
        assert bridge.current_call_index == 4
        assert bridge.started == ([] if replay else ["session#one", "chat:2"])
        bridge.responses.clear()
    assert counts == {"ordinary": 2, "durable": 1, "prepare": 1}


def test_combine_accepts_internal_and_deferred_handles():
    bridge = SubgraphBridge()
    ctx = RuntimeContext(bridge)
    bridge.responses["session#one"] = result_json()
    deferred = SubagentFutureImpl(
        "external",
        "one",
        SingleDurableFuture(
            ctx, "external#one", lambda: (lambda: SubagentResult.ok("external"), None)
        ),
    )
    assert [result.result for result in _run(internal(ctx).combine(deferred))] == [
        "answer",
        "external",
    ]


def test_cancel_before_await_starts_nothing():
    bridge = SubgraphBridge()
    future = internal(RuntimeContext(bridge))
    future.cancel()
    with pytest.raises(CancelledError):
        _run(future)
    assert bridge.call_results == []


def test_subgraph_timeout_cancels_wait_without_persisting_failure():
    bridge = SubgraphBridge()
    ctx = RuntimeContext(bridge)
    ctx.config.set(AgentExecutionOptions.ASYNC_BATCH_TIMEOUT, timedelta(milliseconds=1))
    outcome = _run(ctx.gather(internal(ctx)))[0]
    assert isinstance(outcome.error, TimeoutError)
    assert bridge.cancelled == bridge.closed == ["session#one"]
    assert bridge.call_results[0].status == "PENDING"


def test_duplicate_mapped_call_is_rejected_before_preparation():
    bridge = SubgraphBridge()
    ctx = RuntimeContext(bridge)
    future = internal(ctx)
    with pytest.raises(ValueError, match="more than once"):
        ctx.gather(future, map_future(future, lambda value: value.result))
    assert bridge.call_results == []
