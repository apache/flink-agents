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
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.actions.action import Action
from flink_agents.plan.function import PythonFunction


def process_tool_request(event: ToolRequestEvent, ctx: RunnerContext) -> None:
    """Built-in action for processing tool call requests."""
    responses = {}
    external_ids = {}
    for tool_call in event.tool_calls:
        id = tool_call["id"]
        name = tool_call["function"]["name"]
        kwargs = tool_call["function"]["arguments"]
        tool = ctx.get_resource(name, ResourceType.TOOL)
        external_id = tool_call.get("original_id")
        if not tool:
            response = f"Tool `{name}` does not exist."
        else:
            response = tool.call(**kwargs)
        responses[id] = response
        external_ids[id] = external_id
    ctx.send_event(
        ToolResponseEvent(
            request_id=event.id, responses=responses, external_ids=external_ids
        )
    )


TOOL_CALL_ACTION = Action(
    name="tool_call_action",
    exec=PythonFunction.from_callable(process_tool_request),
    listen_event_types=[f"{ToolRequestEvent.__module__}.{ToolRequestEvent.__name__}"],
)
