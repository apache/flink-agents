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
import asyncio
import json
import pickle
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, MagicMock, Mock
from uuid import UUID, uuid4

import pytest

from flink_agents.api.chat_message import ChatMessage, ToolCallBlock
from flink_agents.api.chat_models.routing import (
    CustomRoutingExecutor,
    ModelRouter,
    RoutingDecision,
    Strategies,
)
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event
from flink_agents.api.events.model_routing_event import ModelRoutingEvent
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.tools.tool_response import ToolResponse
from flink_agents.plan.actions import chat_model_action
from flink_agents.plan.actions.chat_model_action import (
    chat,
    process_chat_request_or_tool_response,
)
from flink_agents.plan.resource_provider import ResourceProvider
from flink_agents.plan.tests.actions.test_chat_model_action_retry import (
    _create_mock_runner_context,
)
from flink_agents.runtime.resource_cache import ResourceCache


class Select(CustomRoutingExecutor):
    calls = 0

    def __init__(self, args) -> None:
        self.args = args

    def route(self, strategy, context):
        type(self).calls += 1
        if self.args.get("fail"):
            msg = "strategy unavailable"
            raise ValueError(msg)
        return RoutingDecision(
            self.args.get("model"), reason="custom reason", score=0.9
        )


class NoArgs(CustomRoutingExecutor):
    def route(self, strategy, context):
        return RoutingDecision.abstain()


def result(text="answer"):
    return ChatResult(message=ChatMessage.assistant(text))


def setup(strategy=None, *, fallback=False, default=None, retries=0, chat_async=False):
    builder = (
        ModelRouter.of("small", "big", "last")
        .strategy(strategy or Strategies.rules({"big": "code"}))
        .fallback(enabled=fallback)
    )
    if default is not None:
        builder.default_model(default)
    router = ModelRouter(**builder.build().arguments)
    models = {name: MagicMock(model=name) for name in router.candidates}
    for model in models.values():
        model.chat.return_value = result()
        model.allowed_script_dirs = []
        model.skills = []
    ctx, events, _, memory = _create_mock_runner_context(
        models["small"],
        max_retries=retries,
        retry_wait_interval_sec=0,
        chat_async=chat_async,
    )
    ctx.has_resource.side_effect = (
        lambda name, kind: kind == ResourceType.MODEL_ROUTER and name == "router"
    )
    ctx.get_resource.side_effect = (
        lambda name, kind: router if kind == ResourceType.MODEL_ROUTER else models[name]
    )
    ctx.action_metric_group = MagicMock()
    return ctx, events, models, memory


def run(ctx, text="write CODE", request_id=None):
    asyncio.run(
        chat(request_id or uuid4(), "router", [ChatMessage.user(text)], None, None, ctx)
    )


@pytest.mark.parametrize("chat_async", [False, True])
def test_rule_selection_fallback_order_and_retry_exhaustion(chat_async):
    ctx, events, models, _ = setup(fallback=True, retries=1, chat_async=chat_async)
    models["big"].chat.side_effect = RuntimeError("big down")
    models["small"].chat.side_effect = RuntimeError("small down")
    run(ctx)
    assert models["big"].chat.call_count == models["small"].chat.call_count == 2
    assert models["last"].chat.call_count == 1
    assert [e.selected_model for e in events if isinstance(e, ModelRoutingEvent)] == [
        "big",
        "last",
    ]
    response = events[-1]
    assert response.status == ChatResponseEvent.SUCCESS
    assert response.retry_count == 2
    assert response.attributes["model_routing"] == {
        "router": "router",
        "selected_model": "big",
        "final_model": "last",
        "candidates": ["small", "big", "last"],
        "decision_source": "fallback",
        "fallback_enabled": True,
        "fallback_attempted": True,
        "fallback_models_tried": ["small", "last"],
        "metadata": {},
        "reason": "matched rule: code",
    }
    durable = ctx.durable_execute_async if chat_async else ctx.durable_execute
    ids = [call.kwargs["durable_id"] for call in durable.call_args_list]
    assert ids[-5:] == ["chat:router:big"] * 2 + ["chat:router:small"] * 2 + [
        "chat:router:last"
    ]
    ctx.action_metric_group.get_histogram.assert_called_once_with(
        "routingDecisionLatencyMs"
    )


@pytest.mark.parametrize(("default", "selected"), [(None, "small"), ("last", "last")])
def test_abstention_and_invalid_pick_use_default(default, selected):
    ctx, events, _, _ = setup(
        Strategies.custom(Select, {"model": "unknown"}), default=default
    )
    run(ctx)
    decision = events[0]
    assert decision.selected_model == selected
    assert decision.decision_source == "default"
    assert decision.get_attr("score") is None
    assert decision.get_attr("metadata") == {
        "rejected_model": "unknown",
        "rejected_reason": "custom reason",
    }


def test_rule_order_and_ascii_case_folding():
    ctx, events, _, _ = setup(Strategies.rules({"last": "code", "big": "code"}))
    run(ctx)
    assert events[0].selected_model == "last"
    ctx, events, _, _ = setup(Strategies.rules({"big": "k"}))
    run(ctx, "\u212a")
    assert events[0].selected_model == "small"


def test_all_candidates_fail_once_and_retain_each_cause():
    ctx, events, models, memory = setup(fallback=True)
    for name, model in models.items():
        model.chat.side_effect = ValueError(name + " down")
    run(ctx)
    failures = [e for e in events if isinstance(e, ChatResponseEvent)]
    assert len(failures) == 1
    assert failures[0].status == ChatResponseEvent.FAILED
    assert all(name + " down" in failures[0].error for name in models)
    assert not memory.get("_ROUTING_METADATA_CONTEXT")


def test_resource_preparation_failure_can_fall_back():
    ctx, events, models, _ = setup(fallback=True)
    lookup = ctx.get_resource.side_effect

    def get(name, kind) -> Any:
        if name == "big":
            msg = "open failed"
            raise RuntimeError(msg)
        return lookup(name, kind)

    ctx.get_resource.side_effect = get
    run(ctx)
    assert events[-1].attributes["model_routing"]["final_model"] == "small"
    models["big"].chat.assert_not_called()


@pytest.mark.parametrize("strategy_error", [False, True])
def test_durable_replay_reuses_strategy_and_model_results(strategy_error):
    Select.calls = 0
    ctx, events, models, _ = setup(
        Strategies.custom(Select, {"model": "big", "fail": strategy_error}),
        fallback=True,
    )
    models["big"].chat.side_effect = RuntimeError("big down")
    records = []
    cursor = 0

    def durable(fn, *args: Any, durable_id=None, **kwargs: Any) -> Any:
        nonlocal cursor
        index = cursor
        cursor += 1
        if index < len(records):
            saved_id, payload = records[index]
            assert durable_id == saved_id
            return pickle.loads(payload)
        outcome = fn(*args, **kwargs)
        records.append((durable_id, pickle.dumps(outcome)))
        return outcome

    ctx.durable_execute.side_effect = durable
    run(ctx)
    first_response = events[-1]
    cursor = 0
    events.clear()
    run(ctx, request_id=uuid4())
    assert Select.calls == 1
    assert models["big"].chat.call_count == (0 if strategy_error else 1)
    assert models["small"].chat.call_count == (0 if strategy_error else 1)
    assert events[-1].status == first_response.status
    assert events[-1].attributes.get("error") == first_response.attributes.get("error")


def test_storage_failure_never_falls_back_or_sends_failure():
    ctx, events, models, _ = setup(fallback=True)
    ordinary = ctx.durable_execute.side_effect

    def durable(fn, *args: Any, durable_id=None, **kwargs: Any) -> Any:
        if durable_id.startswith("chat:"):
            msg = "storage unavailable"
            raise OSError(msg)
        return ordinary(fn, *args, durable_id=durable_id, **kwargs)

    ctx.durable_execute.side_effect = durable
    with pytest.raises(OSError, match="storage unavailable"):
        run(ctx)
    assert len(events) == 1
    for model in models.values():
        model.chat.assert_not_called()


@pytest.mark.parametrize("tool_failure", [False, True])
def test_tool_round_reuses_concrete_model_and_cleans_metadata(tool_failure):
    Select.calls = 0
    ctx, events, models, memory = setup(
        Strategies.custom(Select, {"model": "big"}), fallback=True
    )
    models["big"].chat.side_effect = [
        result([ToolCallBlock(call_id="c", name="tool", input={})]),
        RuntimeError("tool round failed") if tool_failure else result(),
    ]
    run(ctx)
    tool_request = events[-1]
    assert isinstance(tool_request, ToolRequestEvent)
    assert memory.get("_ROUTING_METADATA_CONTEXT")
    response = ToolResponseEvent(
        request_id=tool_request.id, responses={"c": ToolResponse.text("done")}
    )
    asyncio.run(process_chat_request_or_tool_response(response, ctx))
    assert Select.calls == 1
    assert len([e for e in events if isinstance(e, ModelRoutingEvent)]) == 1
    models["small"].chat.assert_not_called()
    assert not memory.get("_ROUTING_METADATA_CONTEXT")
    if tool_failure:
        assert events[-1].status == ChatResponseEvent.FAILED
    else:
        assert events[-1].attributes["model_routing"]["final_model"] == "big"
    assert ctx.durable_execute.call_args.kwargs["durable_id"] == "chat"


def test_routing_event_round_trip_preserves_lineage():
    event = ModelRoutingEvent(
        request_id=uuid4(),
        router="router",
        candidates=["small"],
        selected_model="small",
        decision_source="strategy",
        fallback_enabled=False,
        score=0.5,
        decision_ms=1.2,
    )
    restored = Event.from_json(event.model_dump_json())
    assert isinstance(restored, ModelRoutingEvent)
    assert restored.id == event.id
    assert restored.attributes == event.attributes


def test_python_routing_event_matches_committed_cross_language_snapshot():
    event = ModelRoutingEvent(request_id=UUID("00000000-0000-0000-0000-000000000002"), router="router", candidates=["small", "big"], selected_model="big", decision_source="strategy", fallback_enabled=True, score=0.5, metadata={"tag": "python"}, decision_ms=1.25)
    event = event.reconstruct_from(Event(id=UUID("00000000-0000-0000-0000-000000000001"), type=event.type, attributes=event.attributes))
    path = Path(__file__).resolve().parents[5] / "e2e-test/cross-language-event-snapshots/python/model_routing_event.json"
    assert json.loads(event.model_dump_json()) == json.loads(path.read_text())


@pytest.mark.parametrize("request_model", ["shared", "child_router", "inherited_router"])
def test_scoped_model_requests_and_tool_followups(request_model, monkeypatch):
    ctx, events, models, memory = setup()

    def provider(resource) -> Mock:
        value = Mock(spec=ResourceProvider)
        value.provide.return_value = resource
        return value

    def router(candidate) -> ModelRouter:
        return ModelRouter(
            **ModelRouter.of(candidate).strategy(Strategies.rules({})).build().arguments
        )

    parent_router = provider(router("big"))
    inherited_router = provider(router("big"))
    root = ResourceCache(
        {
            ResourceType.MODEL_ROUTER: {
                "shared": parent_router,
                "inherited_router": inherited_router,
            },
            ResourceType.CHAT_MODEL: {"big": provider(models["big"])},
        }
    )
    child = ResourceCache(
        {
            ResourceType.CHAT_MODEL: {"shared": provider(models["small"])},
            ResourceType.MODEL_ROUTER: {"child_router": provider(router("shared"))},
        },
        parent=root,
    )
    ctx.has_resource.side_effect = child.has_resource
    ctx.get_resource.side_effect = child.get_resource
    resolver = AsyncMock(wraps=chat_model_action.resolve)
    monkeypatch.setattr(chat_model_action, "resolve", resolver)
    selected = "big" if request_model == "inherited_router" else "small"
    concrete_name = "big" if selected == "big" else "shared"
    models[selected].chat.side_effect = [
        result([ToolCallBlock(call_id="c", name="tool", input={})]),
        result("pinned final"),
    ]
    request = ChatRequestEvent(model=request_model, messages=[ChatMessage.user("hi")])
    asyncio.run(process_chat_request_or_tool_response(request, ctx))
    tool_request = events[-1]
    assert isinstance(tool_request, ToolRequestEvent)
    assert tool_request.model == concrete_name
    metadata_before = dict(memory.get("_ROUTING_METADATA_CONTEXT") or {})
    response = ToolResponseEvent(
        request_id=tool_request.id, responses={"c": ToolResponse.text("done")}
    )
    asyncio.run(process_chat_request_or_tool_response(response, ctx))

    resolver.assert_awaited_once()
    parent_router.provide.assert_not_called()
    assert models[selected].chat.call_count == 2
    for name, model in models.items():
        if name != selected:
            model.chat.assert_not_called()
    completed = events[-1]
    assert completed.request_id == request.id
    assert completed.status == ChatResponseEvent.SUCCESS
    assert completed.response.text == "pinned final"
    routing_events = [event for event in events if isinstance(event, ModelRoutingEvent)]
    if request_model == "shared":
        assert not routing_events
        assert "model_routing" not in completed.attributes
    else:
        assert len(routing_events) == 1
        assert completed.attributes["model_routing"] == metadata_before[str(request.id)]
        assert completed.attributes["model_routing"]["router"] == request_model
        assert completed.attributes["model_routing"]["final_model"] == concrete_name
    assert not memory.get("_ROUTING_METADATA_CONTEXT")
    assert not memory.get("_TOOL_CALL_CONTEXT")
    child.close()
    root.close()


def test_fallback_tool_failure_reports_current_round_without_routing_metadata():
    ctx, events, models, memory = setup(fallback=True)
    models["big"].chat.side_effect = ValueError("big initial failure")
    models["small"].chat.side_effect = [
        result([ToolCallBlock(call_id="c", name="tool", input={})]),
        ValueError("small tool-round failure"),
    ]
    request_id = uuid4()
    run(ctx, request_id=request_id)
    tool_request = events[-1]
    assert isinstance(tool_request, ToolRequestEvent)
    assert memory.get("_ROUTING_METADATA_CONTEXT")[str(request_id)]["final_model"] == "small"
    asyncio.run(
        process_chat_request_or_tool_response(
            ToolResponseEvent(
                request_id=tool_request.id, responses={"c": ToolResponse.text("done")}
            ),
            ctx,
        )
    )
    completed = events[-1]
    assert completed.status == ChatResponseEvent.FAILED
    assert "small tool-round failure" in completed.error
    assert "big initial failure" not in completed.error
    assert "model_routing" not in completed.attributes
    assert not memory.get("_ROUTING_METADATA_CONTEXT")
    assert models["big"].chat.call_count == 1
    assert models["small"].chat.call_count == 2
    models["last"].chat.assert_not_called()
