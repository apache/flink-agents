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
from collections.abc import Callable
from typing import Any, List, Optional, Union, cast

from pyflink.common import Row

from flink_agents.api.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
from flink_agents.api.decorators import action
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext

DEFAULT_CHAT_MODEL = "_default_chat_model"
DEFAULT_USER_PROMPT = "_default_user_prompt"
OUTPUT_PARSER = "_output_parser"


class ReActAgent(Agent):
    """Built-in implementation of ReAct agent which is based on the function
    call ability of llm.

    This implementation is not based on the foundational ReAct paper which uses
    prompt to force llm output contain <Thought>, <Action> and <Observation> and
    extract tool calls by text parsing. For a more robust and feature-rich
    implementation we use the tool/function call ability of current llm, and get
    the tool calls from response directly.
    """

    chat_model_connections: str
    chat_model_setup: BaseChatModelSetup
    prompt: Prompt
    tools: List[str]
    output_parser: Union[Callable, str]

    def __init__(
        self,
        chat_model_connection: Optional[str] = None,
        chat_model_setup: Optional[type[BaseChatModelSetup]] = None,
        prompt: Optional[Prompt] = None,
        tools: Optional[List[str]] = None,
        output_parser: Optional[Union[Callable, str]] = None,
        **kwargs: Any,
    ) -> None:
        """Init method.

        Parameters
        ----------
        chat_model_connection : Optional[str] = None
            The names of chat model connection could be used in this agent,
            which have been registered in execution environment.
        chat_model_setup: Type[BaseChatModelSetup]
            The type of chat model setup.
        prompt: Optional[Prompt] = None
            The prompt used to format input, instruct task and output format.
        tools: Optional[List[str]] = None
            The names of tools could be used in this agent, which have been
            registered in execution environment.
        output_parser: Optional[Union[Callable, str]] = None
            The output parser used to parse llm output.
        **kwargs: Any
            Initialize keyword arguments passed to the chat model setup.
        """
        super().__init__()
        if chat_model_connection:
            self._resource_names[ResourceType.CHAT_MODEL_CONNECTION] = [
                chat_model_connection
            ]
        if chat_model_setup:
            settings = {
                "name": DEFAULT_CHAT_MODEL,
                "connection": chat_model_connection,
                "tools": tools,
            }
            settings.update(kwargs)
            self._resources[ResourceType.CHAT_MODEL][DEFAULT_CHAT_MODEL] = (
                chat_model_setup,
                settings,
            )
        if prompt:
            self._resources[ResourceType.PROMPT][DEFAULT_USER_PROMPT] = prompt
        if tools:
            self._resource_names[ResourceType.TOOL] = tools
        if output_parser:
            if isinstance(output_parser, str):
                self._resource_names[ResourceType.TOOL].append(output_parser)
            else:
                self._resources[ResourceType.TOOL][OUTPUT_PARSER] = output_parser

    @action(InputEvent)
    @staticmethod
    def start_action(event: InputEvent, ctx: RunnerContext) -> None:
        """Start action to format user input and send chat request event."""
        usr_input = event.input

        try:
            prompt = cast(
                "Prompt", ctx.get_resource(DEFAULT_USER_PROMPT, ResourceType.PROMPT)
            )
        except KeyError:
            prompt = None

        if isinstance(usr_input, (bool, str, int, float, type(None))):
            usr_input = str(usr_input)
            if prompt:
                usr_msgs = prompt.format_messages(
                    role=MessageRole.USER, input=usr_input
                )
            else:
                usr_msgs = [ChatMessage(role=MessageRole.USER, content=usr_input)]
        else:
            if not prompt:
                err_msg = (
                    f"Input type is {usr_input.__class__}, which is not primitive types. "
                    f"User should provide prompt to help convert it to ChatMessage."
                )
                raise RuntimeError(err_msg)
            if isinstance(usr_input, Row):
                usr_input = usr_input.as_dict(recursive=True)
            else:  # regard as pojo
                usr_input = usr_input.__dict__
            usr_msgs = prompt.format_messages(role=MessageRole.USER, **usr_input)

        ctx.send_event(
            ChatRequestEvent(
                model=DEFAULT_CHAT_MODEL,
                messages=usr_msgs,
            )
        )

    @action(ChatResponseEvent)
    @staticmethod
    def stop_action(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """Stop action to output result."""
        output = event.response.content
        try:
            output_parser = ctx.get_resource(OUTPUT_PARSER, ResourceType.TOOL)
        except KeyError:
            output_parser = None

        if output_parser:
            output = output_parser.call(output)

        ctx.send_event(OutputEvent(output=output))
