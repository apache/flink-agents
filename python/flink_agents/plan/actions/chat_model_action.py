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
import json
import logging
from typing import TYPE_CHECKING, Dict, List, cast
from uuid import UUID

from pydantic import BaseModel
from pyflink.common import Row
from pyflink.common.typeinfo import RowTypeInfo

from flink_agents.api.agents.agent import STRUCTURED_OUTPUT
from flink_agents.api.agents.react_agent import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.java_chat_model import JavaChatModelSetup
from flink_agents.api.core_options import (
    AgentConfigOptions,
    AgentExecutionOptions,
    ErrorHandlingStrategy,
)
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

_logger = logging.getLogger(__name__)


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


def _save_tool_request_event_context(
    sensory_memory: MemoryObject,
    tool_request_event_id: UUID,
    initial_request_id: UUID,
    model: str,
    output_schema: OutputSchema | None,
) -> None:
    """Save the context for a specific tool request event."""
    context = sensory_memory.get(_TOOL_REQUEST_EVENT_CONTEXT) or {}
    context[tool_request_event_id] = {
        "initial_request_id": initial_request_id,
        "model": model,
        "output_schema": output_schema,
    }
    sensory_memory.set(_TOOL_REQUEST_EVENT_CONTEXT, context)


def _get_tool_request_event_context(
    sensory_memory: MemoryObject, request_id: UUID
) -> Dict:
    """Get and remove the context for a specific tool request event."""
    context = sensory_memory.get(_TOOL_REQUEST_EVENT_CONTEXT) or {}
    removed_context = context.pop(request_id, {})
    return removed_context


def _handle_tool_calls(
    response: ChatMessage,
    initial_request_id: UUID,
    model: str,
    messages: List[ChatMessage],
    output_schema: OutputSchema | None,
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
        ctx.sensory_memory,
        tool_request_event.id,
        initial_request_id,
        model,
        output_schema,
    )

    ctx.send_event(tool_request_event)


def _generate_structured_output(
    response: ChatMessage, output_schema: OutputSchema
) -> ChatMessage:
    """Deserialize output to expected output schema."""
    output_schema = output_schema.output_schema
    output = json.loads(response.content.strip())

    if isinstance(output_schema, type) and issubclass(output_schema, BaseModel):
        output = output_schema.model_validate(output)
    elif isinstance(output_schema, RowTypeInfo):
        field_names = output_schema.get_field_names()
        values = {}
        for field_name in field_names:
            values[field_name] = output[field_name]
        output = Row(**values)
    response.extra_args[STRUCTURED_OUTPUT] = output

    return response


async def chat(
    initial_request_id: UUID,
    model: str,
    messages: List[ChatMessage],
    output_schema: OutputSchema | None,
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

    chat_async = ctx.config.get(AgentExecutionOptions.CHAT_ASYNC)
    # java chat model doesn't support async execution,
    # see https://github.com/apache/flink-agents/issues/448 for details.
    chat_async = chat_async and not isinstance(chat_model, JavaChatModelSetup)

    error_handling_strategy = ctx.config.get(AgentConfigOptions.ERROR_HANDLING_STRATEGY)
    num_retries = 0
    if error_handling_strategy == ErrorHandlingStrategy.RETRY:
        num_retries = max(0, ctx.config.get(AgentConfigOptions.MAX_RETRIES))

    response = None
    for attempt in range(num_retries + 1):
        try:
            if chat_async:
                response = await ctx.durable_execute_async(chat_model.chat, messages)
            else:
                response = ctx.durable_execute(chat_model.chat, messages)

            if response.extra_args.get("model_name") and response.extra_args.get("promptTokens") and response.extra_args.get("completionTokens"):
                chat_model._record_token_metrics(response.extra_args["model_name"], response.extra_args["promptTokens"], response.extra_args["completionTokens"])
            if output_schema is not None and len(response.tool_calls) == 0:
                response = _generate_structured_output(response, output_schema)
            break
        except Exception as e:
            if error_handling_strategy == ErrorHandlingStrategy.IGNORE:
                _logger.warning(
                    f"Chat request {initial_request_id} failed with error: {e}, ignored."
                )
                return
            elif error_handling_strategy == ErrorHandlingStrategy.RETRY:
                if attempt == num_retries:
                    raise
                _logger.warning(
                    f"Chat request {initial_request_id} failed with error: {e}, retrying {attempt} / {num_retries}."
                )
            else:
                _logger.debug(
                    f"Chat request {initial_request_id} failed, the input chat messages are {messages}."
                )
                raise

    if (
        len(response.tool_calls) > 0
    ):  # generate tool request event according tool calls in response
        _handle_tool_calls(
            response, initial_request_id, model, messages, output_schema, ctx
        )
    else:  # if there is no tool call generated, return chat response directly
        ctx.send_event(
            ChatResponseEvent(
                request_id=initial_request_id,
                response=response,
            )
        )


async def _process_chat_request(event: ChatRequestEvent, ctx: RunnerContext) -> None:
    """Process chat request event."""
    await chat(
        initial_request_id=event.id,
        model=event.model,
        messages=event.messages,
        output_schema=event.output_schema,
        ctx=ctx,
    )


async def _process_tool_response(event: ToolResponseEvent, ctx: RunnerContext) -> None:
    """Organize the tool call context and return it to the LLM."""
    sensory_memory = ctx.sensory_memory
    request_id = event.request_id

    # get correspond tool request event context
    tool_request_event_context = _get_tool_request_event_context(
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

    await chat(
        initial_request_id=initial_request_id,
        model=tool_request_event_context["model"],
        messages=messages,
        output_schema=tool_request_event_context["output_schema"],
        ctx=ctx,
    )


async def process_chat_request_or_tool_response(
    event: Event, ctx: RunnerContext
) -> None:
    """Built-in action for processing a chat request or tool response.

    This action listens to ChatRequestEvent and ToolResponseEvent, and handles
    the complete chat flow including tool calls. It uses sensory memory to save
    the tool call context, which is a dict mapping request id to chat messages.
    """
    # To avoid https://github.com/alibaba/pemja/issues/88, we log a message here.
    logging.debug("Processing chat request asynchronously.")
    if isinstance(event, ChatRequestEvent):
        await _process_chat_request(event, ctx)
    elif isinstance(event, ToolResponseEvent):
        await _process_tool_response(event, ctx)


CHAT_MODEL_ACTION = Action(
    name="chat_model_action",
    exec=PythonFunction.from_callable(process_chat_request_or_tool_response),
    listen_event_types=[
        f"{ChatRequestEvent.__module__}.{ChatRequestEvent.__name__}",
        f"{ToolResponseEvent.__module__}.{ToolResponseEvent.__name__}",
    ],
)
