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
import json
from typing import cast

from pydantic import (
    BaseModel,
)
from pyflink.common import Row
from pyflink.common.typeinfo import RowTypeInfo

from flink_agents.api.agents.agent import STRUCTURED_OUTPUT, Agent
from flink_agents.api.agents.types import OutputSchema, render_output_schema
from flink_agents.api.chat_message import (
    ChatMessage,
    MessageRole,
    find_first_system_message,
)
from flink_agents.api.decorators import action
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import SubagentMetadata

_DEFAULT_CHAT_MODEL = "_default_chat_model"
_DEFAULT_SCHEMA_PROMPT = "_default_schema_prompt"
_DEFAULT_USER_PROMPT = "_default_user_prompt"
_OUTPUT_SCHEMA = "_output_schema"
_SUBAGENT_INPUT = "_subagent_input"
_SUBAGENT_INSTRUCTIONS = "_subagent_instructions"


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


            env = StreamExecutionEnvironment.get_execution_environment()
            agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

            # register resource to execution environment
            (
                agents_env.add_resource(
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
                    model="qwen3:8b",
                    tools=["notify_shipping_manager"],
                ),
                prompt=prompt,
                output_schema=OutputData,
            )
    """

    @classmethod
    def for_subagent(
        cls,
        *,
        chat_model: ResourceDescriptor,
        description: str,
        instructions: str | None = None,
        output_schema: type[BaseModel] | None = None,
    ) -> "ReActAgent":
        """Create a general-purpose child accepting ``{"prompt": "task"}``.

        Register the returned agent with ``add_resource(name, AGENT, child)``.
        Instructions are literal system-message text. The routing description
        is advertised to the parent model, separately from child instructions.
        Results use the internal sub-agent's list of output payloads.
        """
        if output_schema is not None and not (
            isinstance(output_schema, type) and issubclass(output_schema, BaseModel)
        ):
            msg = "ReAct sub-agent output schema must be a BaseModel subclass."
            raise TypeError(msg)
        child = cls(
            chat_model=chat_model,
            prompt=Prompt.from_text("{prompt}"),
            output_schema=output_schema,
        )
        child.with_subagent_metadata(
            SubagentMetadata(
                description=description,
                input_schema=json.dumps(
                    {
                        "type": "object",
                        "properties": {"prompt": {"type": "string"}},
                        "required": ["prompt"],
                        "additionalProperties": False,
                    }
                ),
            )
        )
        config = child.actions["start_action"][2]
        config[_SUBAGENT_INPUT] = True
        if instructions is not None:
            config[_SUBAGENT_INSTRUCTIONS] = instructions
        return child

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

        Raises:
        ------
        TypeError
            If the schema is neither a RowTypeInfo nor a BaseModel subclass, or if a
            BaseModel schema cannot be rendered as a JSON Schema.
        """
        super().__init__()
        self.add_resource(_DEFAULT_CHAT_MODEL, ResourceType.CHAT_MODEL, chat_model)

        if output_schema:
            if isinstance(output_schema, type) and issubclass(output_schema, BaseModel):
                json_schema = render_output_schema(
                    output_schema, lambda model: model.model_json_schema()
                )
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
            trigger_conditions=[InputEvent.EVENT_TYPE],
            func=self.start_action,
            output_schema=OutputSchema(output_schema=output_schema)
            if output_schema
            else None,
        )

    @staticmethod
    def start_action(event: Event, ctx: RunnerContext) -> None:
        """Start action to format user input and send chat request event."""
        usr_input = InputEvent.from_event(event).input
        if ctx.get_action_config_value(key=_SUBAGENT_INPUT) is True and (
            not isinstance(usr_input, dict)
            or set(usr_input) != {"prompt"}
            or not isinstance(usr_input["prompt"], str)
        ):
            msg = "ReAct sub-agent input must be an object containing only a string 'prompt'."
            raise ValueError(msg)

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
            elif isinstance(usr_input, dict):
                pass
            else:  # regard as pojo
                usr_input = usr_input.__dict__
            # Convert Any values to str to match format_messages signature
            str_usr_input = {k: str(v) for k, v in usr_input.items()}
            usr_msgs = prompt.format_messages(role=MessageRole.USER, **str_usr_input)

        output_schema = ctx.get_action_config_value(key="output_schema")
        try:
            schema_prompt = (
                None
                if output_schema is None
                else cast(
                    "Prompt",
                    ctx.get_resource(_DEFAULT_SCHEMA_PROMPT, ResourceType.PROMPT),
                )
            )
        except KeyError:
            schema_prompt = None

        instructions = ctx.get_action_config_value(key=_SUBAGENT_INSTRUCTIONS)
        if instructions is not None:
            usr_msgs.insert(
                0, ChatMessage(role=MessageRole.SYSTEM, content=instructions)
            )

        if schema_prompt:
            instruct = schema_prompt.format_messages()
            index = find_first_system_message(usr_msgs)
            usr_msgs = usr_msgs[: index + 1] + instruct + usr_msgs[index + 1 :]

        ctx.send_event(
            ChatRequestEvent(
                model=_DEFAULT_CHAT_MODEL,
                messages=usr_msgs,
                output_schema=output_schema,
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def stop_action(event: Event, ctx: RunnerContext) -> None:
        """Stop action to output result."""
        response = ChatResponseEvent.from_event(event).response

        if STRUCTURED_OUTPUT in response.extra_args:
            output = response.extra_args[STRUCTURED_OUTPUT]
        else:
            output = response.content

        ctx.send_event(OutputEvent(output=output))
