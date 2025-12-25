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
from typing import TYPE_CHECKING, Dict, List, cast
from uuid import UUID

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.actions.action import Action
from flink_agents.plan.function import PythonFunction

if TYPE_CHECKING:
    from flink_agents.api.chat_models.chat_model import BaseChatModelSetup

_TOOL_CALL_CONTEXT = "_TOOL_CALL_CONTEXT"
_TOOL_REQUEST_EVENT_CONTEXT = "_TOOL_REQUEST_EVENT_CONTEXT"


# ============================================================================
# Helper Functions for Tool Call Context Management
# ============================================================================
def _update_tool_call_context(
    sensory_memory: MemoryObject,
    initial_request_id: UUID,
    initial_messages: List[ChatMessage] | None,
    added_messages: List[ChatMessage],
) -> List[ChatMessage]:
    """Append messages to tool call context.

    The messages maybe chat model response with tool calls, or tool execute results. May
    initialize the context for initial_request_id if needed.
    """
    # TODO: Because memory doesn't support remove currently, so we use
    #  dict to store tool context in memory and remove the specific
    #  tool context from dict after consuming. This will cause write and
    #  read amplification for we need get the whole dict and overwrite it
    #  to memory each time we update a specific tool context.
    #  After memory supports remove, we can use "TOOL_CALL_CONTEXT/request_id"
    #  to store and remove the specific tool context directly.

    # init if not exists
    tool_call_context = sensory_memory.get(_TOOL_CALL_CONTEXT) or {}
    if initial_request_id not in tool_call_context and initial_messages is not None:
        tool_call_context[initial_request_id] = copy.deepcopy(initial_messages)

    tool_call_context[initial_request_id].extend(added_messages)

    # update tool call context
    sensory_memory.set(_TOOL_CALL_CONTEXT, tool_call_context)
    return tool_call_context[initial_request_id]


def _clear_tool_call_context(
    sensory_memory: MemoryObject, initial_request_id: UUID
) -> None:
    """Clear tool call context for a specific request ID."""
    context = sensory_memory.get(_TOOL_CALL_CONTEXT) or {}
    if initial_request_id in context:
        context.pop(initial_request_id)
        sensory_memory.set(_TOOL_CALL_CONTEXT, context)


def _save_tool_request_event_context(
    sensory_memory: MemoryObject,
    tool_request_event_id: UUID,
    initial_request_id: UUID,
    model: str,
) -> None:
    """Save the context for a specific tool request event."""
    context = sensory_memory.get(_TOOL_REQUEST_EVENT_CONTEXT) or {}
    context[tool_request_event_id] = {
        "initial_request_id": initial_request_id,
        "model": model,
    }
    sensory_memory.set(_TOOL_REQUEST_EVENT_CONTEXT, context)


def _remove_tool_request_event_context(
    sensory_memory: MemoryObject, request_id: UUID
) -> Dict:
    """Get and remove the context for a specific tool request event."""
    context = sensory_memory.get(_TOOL_REQUEST_EVENT_CONTEXT) or {}
    removed_context = context.pop(request_id, {})
    sensory_memory.set(_TOOL_REQUEST_EVENT_CONTEXT, removed_context)
    return removed_context


def _handle_tool_calls(
    response: ChatMessage,
    initial_request_id: UUID,
    model: str,
    messages: List[ChatMessage],
    ctx: RunnerContext,
) -> None:
    """Handle tool calls in chat response."""
    _update_tool_call_context(
        ctx.sensory_memory, initial_request_id, messages, [response]
    )

    tool_request_event = ToolRequestEvent(
        model=model,
        tool_calls=response.tool_calls,
    )

    # save tool request event context
    _save_tool_request_event_context(
        ctx.sensory_memory, tool_request_event.id, initial_request_id, model
    )

    ctx.send_event(tool_request_event)


def chat(
    initial_request_id: UUID,
    model: str,
    messages: List[ChatMessage],
    ctx: RunnerContext,
) -> None:
    """Chat with llm.

    If there is no tool call generated, we return the chat response event directly,
    otherwise, we generate tool request event according to the tool calls in chat model
    response, and save the request and response messages in tool call context.
    """
    chat_model = cast(
        "BaseChatModelSetup", ctx.get_resource(model, ResourceType.CHAT_MODEL)
    )

    # TODO: support async execution of chat.
    response = chat_model.chat(messages)

    if (
        len(response.tool_calls) > 0
    ):  # generate tool request event according tool calls in response
        _handle_tool_calls(response, initial_request_id, model, messages, ctx)
    else:  # if there is no tool call generated, return chat response directly
        _clear_tool_call_context(ctx.sensory_memory, initial_request_id)

        ctx.send_event(
            ChatResponseEvent(
                request_id=initial_request_id,
                response=response,
            )
        )


def _process_chat_request(event: ChatRequestEvent, ctx: RunnerContext) -> None:
    """Process chat request event."""
    chat(
        initial_request_id=event.id,
        model=event.model,
        messages=event.messages,
        ctx=ctx,
    )


def _process_tool_response(event: ToolResponseEvent, ctx: RunnerContext) -> None:
    """Organize the tool call context and return it to the LLM."""
    sensory_memory = ctx.sensory_memory
    request_id = event.request_id

    # get correspond tool request event context
    tool_request_event_context = _remove_tool_request_event_context(
        sensory_memory, request_id
    )
    initial_request_id = tool_request_event_context["initial_request_id"]

    # update tool call context, and get the entire chat messages.
    messages = _update_tool_call_context(
        sensory_memory,
        initial_request_id,
        None,
        [
            ChatMessage(
                role=MessageRole.TOOL,
                content=str(response),
                extra_args={"external_id": event.external_ids.get(tool_id)}
                if event.external_ids and event.external_ids.get(tool_id)
                else {},
            )
            for tool_id, response in event.responses.items()
        ],
    )

    chat(
        initial_request_id=initial_request_id,
        model=tool_request_event_context["model"],
        messages=messages,
        ctx=ctx,
    )


def process_chat_request_or_tool_response(event: Event, ctx: RunnerContext) -> None:
    """Built-in action for processing a chat request or tool response.

    This action listens to ChatRequestEvent and ToolResponseEvent, and handles
    the complete chat flow including tool calls. It uses sensory memory to save
    the tool call context, which is a dict mapping request id to chat messages.
    """
    if isinstance(event, ChatRequestEvent):
        _process_chat_request(event, ctx)
    elif isinstance(event, ToolResponseEvent):
        _process_tool_response(event, ctx)


CHAT_MODEL_ACTION = Action(
    name="chat_model_action",
    exec=PythonFunction.from_callable(process_chat_request_or_tool_response),
    listen_event_types=[
        f"{ChatRequestEvent.__module__}.{ChatRequestEvent.__name__}",
        f"{ToolResponseEvent.__module__}.{ToolResponseEvent.__name__}",
    ],
)
