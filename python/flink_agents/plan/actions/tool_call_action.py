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


def process_tool_request(event: ToolRequestEvent, ctx: RunnerContext):
    """Built-in action for processing tool call requests.
    
    This function uses async execution for tool calls to avoid blocking.
    """
    # 1. Get all resources BEFORE async execution (resource access must happen before async)
    tools = {}
    tool_call_info = []
    for tool_call in event.tool_calls:
        id = tool_call["id"]
        name = tool_call["function"]["name"]
        kwargs = tool_call["function"]["arguments"]
        external_id = tool_call.get("original_id")
        
        # Get tool resource before async execution
        tool = ctx.get_resource(name, ResourceType.TOOL)
        tools[id] = tool
        tool_call_info.append({
            "id": id,
            "name": name,
            "kwargs": kwargs,
            "external_id": external_id,
            "tool": tool,
        })
    
    # 2. Execute tools asynchronously (no memory access inside async function)
    responses = {}
    external_ids = {}
    for info in tool_call_info:
        if not info["tool"]:
            # Tool doesn't exist - handle synchronously
            responses[info["id"]] = f"Tool `{info['name']}` does not exist."
        else:
            # Execute tool call asynchronously
            # The lambda captures tool and kwargs, but cannot access memory
            response = yield from ctx.execute_async(
                lambda t=info["tool"], k=info["kwargs"]: t.call(**k)
            )
            responses[info["id"]] = response
        external_ids[info["id"]] = info["external_id"]
    
    # 3. Send event AFTER async execution
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
