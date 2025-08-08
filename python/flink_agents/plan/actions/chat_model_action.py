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

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.actions.action import Action
from flink_agents.plan.function import PythonFunction


def process_chat_request_or_tool_response(event: Event, ctx: RunnerContext) -> None:
    """Built-in action for processing a chat request or tool response."""
    if isinstance(event, ChatRequestEvent):
        chat_model = ctx.get_resource(event.model, ResourceType.CHAT_MODEL)
        # TODO: support async execution of chat.
        response = chat_model.chat(event.messages)
        # call tool
        if len(response.tool_calls) > 0:
            for tool_call in response.tool_calls:
                # store the tool call context in short term memory
                state = ctx.get_short_term_memory()
                # TODO: Because memory doesn't support remove currently, so we use
                #  dict to store tool context in memory and remove the specific
                #  tool context from dict after consuming. This will cause some
                #  overhead for we need get the whole dict and overwrite it to memory
                #  each time we update a specific tool context.
                #  After memory supports remove, we can use
                #  "__tool_context/tool_call_id" to store and remove the specific tool
                #  context directly.
                if not state.is_exist("__tool_context"):
                    state.set("__tool_context", {})
                tool_context = state.get("__tool_context")
                tool_call_id = tool_call["id"]
                tool_context[tool_call_id] = event
                tool_context[tool_call_id].messages.append(response)
                state.set("__tool_context", tool_context)
                ctx.send_event(
                    ToolRequestEvent(
                        id=tool_call_id,
                        tool=tool_call["function"]["name"],
                        kwargs=tool_call["function"]["arguments"],
                    )
                )

        # send response
        else:
            ctx.send_event(ChatResponseEvent(request=event, response=response))
    elif isinstance(event, ToolResponseEvent):
        state = ctx.get_short_term_memory()

        if state.is_exist("__tool_context"):
            tool_context = state.get("__tool_context")
            tool_call_id = event.request.id
            if tool_context is not None and tool_call_id in tool_context:
                # get the specific tool call context from short term memory
                specific_tool_ctx = tool_context.pop(tool_call_id)
                specific_tool_ctx.messages.append(
                    ChatMessage(role=MessageRole.TOOL, content=str(event.response))
                )
                ctx.send_event(specific_tool_ctx)
                # update short term memory to remove the specific tool call context
                state.set("__tool_context", tool_context)


CHAT_MODEL_ACTION = Action(
    name="chat_model_action",
    exec=PythonFunction.from_callable(process_chat_request_or_tool_response),
    listen_event_types=[
        f"{ChatRequestEvent.__module__}.{ChatRequestEvent.__name__}",
        f"{ToolResponseEvent.__module__}.{ToolResponseEvent.__name__}",
    ],
)
