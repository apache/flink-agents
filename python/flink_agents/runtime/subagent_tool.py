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
"""Executable tool adapter for a model-bound subagent."""

import json
from typing import Any

from typing_extensions import override

from flink_agents.api.resource import ResourceType
from flink_agents.api.subagent import SubagentResult, SubagentSetup
from flink_agents.api.tools.tool import Tool, ToolMetadata, ToolType
from flink_agents.plan.actions.tool_result_utils import (
    normalize_agent_result,
    to_chat_message_content,
    to_tool_response,
)
from flink_agents.plan.subagent_constants import TOOL_NAME_PREFIX
from flink_agents.plan.tools.async_tool import AsyncTool, ToolCallContext
from flink_agents.runtime.async_future import map_future


class SubagentTool(Tool, AsyncTool):
    """Resolve and invoke a subagent through its own runtime-managed future."""

    agent_name: str

    @staticmethod
    def of(name: str, setup: SubagentSetup) -> "SubagentTool":
        """Build metadata and bind the runtime adapter to a registered subagent."""
        if not isinstance(setup, SubagentSetup):
            message = f"Sub-agent {name} must resolve to a SubagentSetup, but was {type(setup).__name__}"
            raise TypeError(message)
        if setup.input_schema is None:
            message = (
                f"Sub-agent {name} declares neither an input schema nor an input type"
            )
            raise ValueError(message)
        return SubagentTool(
            agent_name=name,
            metadata=ToolMetadata(
                name=TOOL_NAME_PREFIX + name,
                description=(
                    setup.description
                    or f"Delegate a standalone task to sub-agent {name}"
                )
                + " This is subagent.",
                args_schema=json.loads(setup.input_schema),
            ),
        )

    @classmethod
    @override
    def tool_type(cls) -> ToolType:
        return ToolType.FUNCTION

    @override
    def call(self, *args: Any, **kwargs: Any) -> Any:
        message = "SubagentTool requires the runtime async execution path"
        raise NotImplementedError(message)

    @override
    async def call_async(
        self, ctx: Any, call_context: ToolCallContext, /, **kwargs: Any
    ) -> Any:
        # Bind this action's metrics and lifecycle namespace to the setup.
        setup = ctx.get_resource(self.agent_name, ResourceType.AGENT)
        call_context.mark_started()
        try:
            future = await setup.submit(ctx, kwargs)
            call_context.on_cancel = future.cancel

            def convert(result: SubagentResult) -> Any:
                try:
                    if not result.success:
                        raise result.exception
                    return to_tool_response(
                        to_chat_message_content(
                            normalize_agent_result(result.result, setup.result_type())
                        )
                    )
                finally:
                    call_context.mark_finished()

            return map_future(future, convert)
        except BaseException:
            call_context.mark_finished()
            if call_context.on_cancel is not None:
                call_context.on_cancel()
            raise
