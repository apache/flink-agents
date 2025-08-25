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

TOOL_CALL_CONTEXT = "_TOOL_CALL_CONTEXT"


def process_chat_request_or_tool_response(event: Event, ctx: RunnerContext) -> None:
    """Built-in action for processing a chat request or tool response."""
    short_term_memory = ctx.get_short_term_memory()
    if isinstance(event, ChatRequestEvent):
        chat_model = ctx.get_resource(event.model, ResourceType.CHAT_MODEL)
        # TODO: support async execution of chat.
        response = chat_model.chat(event.messages)

        request_id = event.id
        # call tool
        if len(response.tool_calls) > 0:
            # TODO: Because memory doesn't support remove currently, so we use
            #  dict to store tool context in memory and remove the specific
            #  tool context from dict after consuming. This will cause write and
            #  read amplification for we need get the whole dict and overwrite it
            #  to memory each time we update a specific tool context.
            #  After memory supports remove, we can use "TOOL_CALL_CONTEXT/request_id"
            #  to store and remove the specific tool context directly.
            # save tool call context
            tool_call_context = short_term_memory.get(TOOL_CALL_CONTEXT)
            if not tool_call_context:
                tool_call_context = {}
            if request_id not in tool_call_context:
                tool_call_context[request_id] = event
            # append response to request event messages
            tool_call_context[request_id].messages.append(response)
            # update short term memory
            short_term_memory.set(TOOL_CALL_CONTEXT, tool_call_context)
            ctx.send_event(
                ToolRequestEvent(
                    id=event.id,
                    tool_calls=response.tool_calls,
                )
            )
        # send response
        else:
            # clear tool call context related to specific request id
            tool_call_context = short_term_memory.get(TOOL_CALL_CONTEXT)
            if tool_call_context and request_id in tool_call_context:
                tool_call_context.pop(request_id)
                short_term_memory.set(TOOL_CALL_CONTEXT, tool_call_context)
            ctx.send_event(ChatResponseEvent(request=event, response=response))
    elif isinstance(event, ToolResponseEvent):
        request_id = event.request.id
        responses = event.responses
        # get tool call context
        tool_call_context = short_term_memory.get(TOOL_CALL_CONTEXT)
        for response in responses.values():
            tool_call_context[request_id].messages.append(
                ChatMessage(role=MessageRole.TOOL, content=str(response))
            )
        # update tool call context
        short_term_memory.set(TOOL_CALL_CONTEXT, tool_call_context)
        process_chat_request_or_tool_response(
            event=tool_call_context[request_id], ctx=ctx
        )


CHAT_MODEL_ACTION = Action(
    name="chat_model_action",
    exec=PythonFunction.from_callable(process_chat_request_or_tool_response),
    listen_event_types=[
        f"{ChatRequestEvent.__module__}.{ChatRequestEvent.__name__}",
        f"{ToolResponseEvent.__module__}.{ToolResponseEvent.__name__}",
    ],
)
