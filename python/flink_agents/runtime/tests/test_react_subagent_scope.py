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
"""Child ReAct actions must resolve their own configuration and resources."""

from concurrent.futures import ThreadPoolExecutor
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel

from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.events.event import InputEvent
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext
from flink_agents.runtime.resource_cache import ResourceCache


class ParentAnswer(BaseModel):
    """An output shape belonging only to the parent."""

    parent_only_answer: str


@pytest.mark.parametrize("structured_parent", [False, True])
def test_child_configuration_and_resources_are_used_then_parent_is_restored(
    structured_parent: bool,
) -> None:
    """Same-named actions and resources must not use the parent's settings."""
    descriptor = ResourceDescriptor(
        clazz="flink_agents.integrations.chat_models.ollama_chat_model.OllamaChatModelSetup"
    )
    root = ReActAgent(
        chat_model=descriptor, output_schema=ParentAnswer if structured_parent else None
    )
    root.add_resource("shared", ResourceType.PROMPT, Prompt.from_text("shared value"))
    child = ReActAgent.for_subagent(
        chat_model=descriptor, description="Research", instructions="child instructions"
    )
    root_plan = AgentPlan.from_agent(root, AgentConfiguration())
    child_plan = AgentPlan.from_agent(child, AgentConfiguration())
    bridge = MagicMock()
    bridge.getActionName.return_value = "start_action"
    bridge.getActiveScopePlanJson.return_value = child_plan.model_dump_json(
        serialize_as_any=True
    )
    with ThreadPoolExecutor() as executor:
        ctx = FlinkRunnerContext(
            bridge, root_plan.model_dump_json(serialize_as_any=True), executor, None
        )
        try:
            assert ctx.action_config["_subagent_instructions"] == "child instructions"
            assert ctx.get_action_config_value("_subagent_input") is True
            assert (
                ctx.get_resource("shared", ResourceType.PROMPT).format_string()
                == "shared value"
            )
            # Exercise the production ReAct action and real scoped resource lookup.
            emitted = []
            ctx.send_event = emitted.append
            ReActAgent.start_action(InputEvent(input={"prompt": "investigate"}), ctx)
            assert [message.content for message in emitted[0].messages] == [
                "child instructions",
                "investigate",
            ]
            bridge.getActiveScopePlanJson.return_value = None
            assert ctx.get_action_config_value("_subagent_input") is None
        finally:
            ctx.close()


def test_child_cache_owns_only_its_local_resources() -> None:
    """Closing a child must leave shared parent resources open."""
    shared, local = MagicMock(), MagicMock()
    parent = ResourceCache({})
    child = ResourceCache({}, parent=parent)
    parent._cache = {ResourceType.PROMPT: {"shared": shared}}
    child._cache = {ResourceType.PROMPT: {"local": local}}
    assert child.get_resource("shared", ResourceType.PROMPT) is shared
    child.close()
    local.close.assert_called_once()
    shared.close.assert_not_called()
    parent.close()
    shared.close.assert_called_once()


def test_internal_failure_uses_recorded_summary_instead_of_bridge_wrapper() -> None:
    """Original and replayed bridge exceptions yield the same caller-visible error."""
    from flink_agents.runtime.internal_subagent import (
        InternalSubagentCallFactory,
        InternalSubagentSetup,
    )

    setup = InternalSubagentSetup(scope="child", child_plan=AgentPlan(actions={}))
    ctx = MagicMock(spec=InternalSubagentCallFactory)
    ctx.subagent_failure_message.return_value = "ValueError: invalid prompt"
    for wrapper in [
        RuntimeError("original bridge failure"),
        RuntimeError("replayed IllegalStateException"),
    ]:
        ctx.await_subagent_call.side_effect = wrapper
        _, call, _ = setup.prepare(ctx, {}, "session", "call")
        result = call()
        assert not result.success
        assert result.error_message == "ValueError: invalid prompt"
