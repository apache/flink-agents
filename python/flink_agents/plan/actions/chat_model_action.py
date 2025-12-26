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
import copy
from typing import TYPE_CHECKING, List, cast
from uuid import UUID

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.actions.action import Action
from flink_agents.plan.function import PythonFunction

if TYPE_CHECKING:
    from flink_agents.api.chat_models.chat_model import BaseChatModelSetup

_TOOL_CALL_CONTEXT = "_TOOL_CALL_CONTEXT"
_TOOL_REQUEST_EVENT_CONTEXT = "_TOOL_REQUEST_EVENT_CONTEXT"


def chat(
    initial_request_id: UUID,
    model: str,
    messages: List[ChatMessage],
    ctx: RunnerContext,
):
    """Chat with llm.

    If there is no tool call generated, we return the chat response event directly,
    otherwise, we generate tool request event according to the tool calls in chat model
    response, and save the request and response messages in tool call context.
    
    This function uses async execution for the chat model call to avoid blocking.
    """
    # 1. Get resource BEFORE async execution (resource access must happen before async)
    chat_model = cast(
        "BaseChatModelSetup", ctx.get_resource(model, ResourceType.CHAT_MODEL)
    )

    # 2. Read memory BEFORE async execution (memory access must happen before async)
    short_term_memory = ctx.short_term_memory
    tool_call_context = short_term_memory.get(_TOOL_CALL_CONTEXT)
    if not tool_call_context:
        tool_call_context = {}

    # 3. Execute chat asynchronously (no memory access inside async function)
    # The lambda captures chat_model and messages, but cannot access memory
    response = yield from ctx.execute_async(
        lambda: chat_model.chat(messages)
    )

    # 4. Process response and update memory AFTER async execution
    # generate tool request event according tool calls in response
    if len(response.tool_calls) > 0:
        # TODO: Because memory doesn't support remove currently, so we use
        #  dict to store tool context in memory and remove the specific
        #  tool context from dict after consuming. This will cause write and
        #  read amplification for we need get the whole dict and overwrite it
        #  to memory each time we update a specific tool context.
        #  After memory supports remove, we can use "TOOL_CALL_CONTEXT/request_id"
        #  to store and remove the specific tool context directly.

        # save tool call context
        if initial_request_id not in tool_call_context:
            tool_call_context[initial_request_id] = copy.deepcopy(messages)
        # append response to tool call context
        tool_call_context[initial_request_id].append(response)
        # update tool call context
        short_term_memory.set(_TOOL_CALL_CONTEXT, tool_call_context)

        tool_request_event = ToolRequestEvent(
            model=model,
            tool_calls=response.tool_calls,
        )

        # save tool request event context
        tool_request_event_context = tool_call_context.get(_TOOL_REQUEST_EVENT_CONTEXT)
        if not tool_request_event_context:
            tool_request_event_context = {}
        tool_request_event_context[tool_request_event.id] = {
            "initial_request_id": initial_request_id,
            "model": model,
        }
        short_term_memory.set(_TOOL_REQUEST_EVENT_CONTEXT, tool_request_event_context)

        ctx.send_event(tool_request_event)
    # if there is no tool call generated, return chat response directly
    else:
        # clear tool call context related to specific request id
        if tool_call_context and initial_request_id in tool_call_context:
            tool_call_context.pop(initial_request_id)
            short_term_memory.set(_TOOL_CALL_CONTEXT, tool_call_context)
        ctx.send_event(
            ChatResponseEvent(
                request_id=initial_request_id,
                response=response,
            )
        )


def process_chat_request_or_tool_response(event: Event, ctx: RunnerContext):
    """Built-in action for processing a chat request or tool response.

    Internally, this action will use short term memory to save the tool call context,
    which is a dict mapping request id to chat messages.
    
    This function uses async execution for chat operations.
    """
    short_term_memory = ctx.short_term_memory
    if isinstance(event, ChatRequestEvent):
        yield from chat(
            initial_request_id=event.id,
            model=event.model,
            messages=event.messages,
            ctx=ctx,
        )

    elif isinstance(event, ToolResponseEvent):
        request_id = event.request_id

        # get correspond tool request event context
        tool_request_event_context = short_term_memory.get(_TOOL_REQUEST_EVENT_CONTEXT)
        initial_request_id = tool_request_event_context[request_id][
            "initial_request_id"
        ]
        model = tool_request_event_context[request_id]["model"]
        # clear tool request event context
        tool_request_event_context.pop(request_id)
        short_term_memory.set(_TOOL_REQUEST_EVENT_CONTEXT, tool_request_event_context)

        responses = event.responses
        # update tool call context
        tool_call_context = short_term_memory.get(_TOOL_CALL_CONTEXT)
        for id, response in responses.items():
            tool_call_context[initial_request_id].append(
                ChatMessage(
                    role=MessageRole.TOOL,
                    content=str(response),
                    extra_args={"external_id": event.external_ids[id]}
                    if event.external_ids[id]
                    else {},
                )
            )
        short_term_memory.set(_TOOL_CALL_CONTEXT, tool_call_context)

        yield from chat(
            initial_request_id=initial_request_id,
            model=model,
            messages=tool_call_context[initial_request_id],
            ctx=ctx,
        )


CHAT_MODEL_ACTION = Action(
    name="chat_model_action",
    exec=PythonFunction.from_callable(process_chat_request_or_tool_response),
    listen_event_types=[
        f"{ChatRequestEvent.__module__}.{ChatRequestEvent.__name__}",
        f"{ToolResponseEvent.__module__}.{ToolResponseEvent.__name__}",
    ],
)
