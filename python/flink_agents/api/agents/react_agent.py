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
from typing import cast

from pydantic import (
    BaseModel,
)
from pyflink.common import Row
from pyflink.common.typeinfo import RowTypeInfo

from flink_agents.api.agents.agent import STRUCTURED_OUTPUT, Agent
from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import action
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext

_DEFAULT_CHAT_MODEL = "_default_chat_model"
_DEFAULT_SCHEMA_PROMPT = "_default_schema_prompt"
_DEFAULT_USER_PROMPT = "_default_user_prompt"
_OUTPUT_SCHEMA = "_output_schema"


class ReActAgent(Agent):
    """Built-in implementation of ReAct agent which is based on the function
    call ability of llm.

    This implementation is not based on the foundational ReAct paper which uses
    prompt to force llm output contain <Thought>, <Action> and <Observation> and
    extract tool calls by text parsing. For a more robust and feature-rich
    implementation we use the tool/function call ability of current llm, and get
    the tool calls from response directly.


    Example:
        ::

            class OutputData(BaseModel):
                result: int


            env = AgentsExecutionEnvironment.get_execution_environment()

            # register resource to execution environment
            (
                env.add_resource(
                    "ollama",
                    ResourceDescriptor(clazz=OllamaChatModelConnection, model=model),
                )
                .add_resource("add", add)
                .add_resource("multiply", multiply)
            )

            # prepare prompt
            prompt = Prompt.from_messages(
                messages=[
                    ChatMessage(
                        role=MessageRole.SYSTEM,
                        content='An example of output is {"result": 30.32}.',
                    ),
                    ChatMessage(
                        role=MessageRole.USER, content="What is ({a} + {b}) * {c}"
                    ),
                ],
            )

            # create ReAct agent.
            agent = ReActAgent(
                chat_model=ResourceDescriptor(
                    clazz=OllamaChatModelSetup,
                    connection="ollama_server",
                    tools=["notify_shipping_manager"],
                ),
                prompt=prompt,
                output_schema=OutputData,
            )
    """

    def __init__(
        self,
        *,
        chat_model: ResourceDescriptor,
        prompt: Prompt | None = None,
        output_schema: type[BaseModel] | RowTypeInfo | None = None,
    ) -> None:
        """Init method of ReActAgent.

        Parameters
        ----------
        chat_model : ResourceDescriptor
            The descriptor of the chat model used in this ReAct agent.
        prompt : Optional[Prompt] = None
            Prompt to instruct the llm, could include input and output example,
            task and so on.
        output_schema : Optional[Union[type[BaseModel], RowTypeInfo]] = None
            The schema should be RowTypeInfo or subclass of BaseModel. When user
            provide output schema, ReAct agent will add system prompt to instruct
            response format of llm, and add output parser according to the schema.
        """
        super().__init__()
        self.add_resource(_DEFAULT_CHAT_MODEL, chat_model)

        if output_schema:
            if isinstance(output_schema, type) and issubclass(output_schema, BaseModel):
                json_schema = output_schema.model_json_schema()
            elif isinstance(output_schema, RowTypeInfo):
                json_schema = str(output_schema)
            else:
                err_msg = f"Output schema {output_schema.__class__} is not supported."
                raise TypeError(err_msg)
            schema_prompt = f"The final response should be json format, and match the schema {json_schema}."
            self._resources[ResourceType.PROMPT][_DEFAULT_SCHEMA_PROMPT] = (
                Prompt.from_text(text=schema_prompt)
            )

        if prompt:
            self._resources[ResourceType.PROMPT][_DEFAULT_USER_PROMPT] = prompt

        self.add_action(
            name="start_action",
            events=[InputEvent],
            func=self.start_action,
            output_schema=OutputSchema(output_schema=output_schema),
        )

    @staticmethod
    def start_action(event: InputEvent, ctx: RunnerContext) -> None:
        """Start action to format user input and send chat request event."""
        usr_input = event.input

        try:
            prompt = cast(
                "Prompt", ctx.get_resource(_DEFAULT_USER_PROMPT, ResourceType.PROMPT)
            )
        except KeyError:
            prompt = None

        if isinstance(usr_input, bool | str | int | float | type(None)):
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
            # Convert Any values to str to match format_messages signature
            str_usr_input = {k: str(v) for k, v in usr_input.items()}
            usr_msgs = prompt.format_messages(role=MessageRole.USER, **str_usr_input)

        try:
            schema_prompt = cast(
                "Prompt", ctx.get_resource(_DEFAULT_SCHEMA_PROMPT, ResourceType.PROMPT)
            )
        except KeyError:
            schema_prompt = None

        if schema_prompt:
            instruct = schema_prompt.format_messages()
            usr_msgs = instruct + usr_msgs

        output_schema = ctx.get_action_config_value(key="output_schema")

        ctx.send_event(
            ChatRequestEvent(
                model=_DEFAULT_CHAT_MODEL,
                messages=usr_msgs,
                output_schema=output_schema,
            )
        )

    @action(ChatResponseEvent)
    @staticmethod
    def stop_action(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """Stop action to output result."""
        response = event.response

        if STRUCTURED_OUTPUT in response.extra_args:
            output = response.extra_args[STRUCTURED_OUTPUT]
        else:
            output = response.content

        ctx.send_event(OutputEvent(output=output))
