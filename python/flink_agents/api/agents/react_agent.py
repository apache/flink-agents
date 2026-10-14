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
import logging
from collections.abc import Sequence
from concurrent.futures import CancelledError
from typing import TYPE_CHECKING, cast

from pydantic import (
    BaseModel,
)
from pyflink.common import Row
from pyflink.common.typeinfo import RowTypeInfo

from flink_agents.api.agents.agent import Agent
from flink_agents.api.agents.types import OutputSchema, render_output_schema
from flink_agents.api.chat_message import (
    ChatMessage,
    MessageRole,
    find_first_system_message,
)
from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
from flink_agents.api.decorators import action
from flink_agents.api.events.chat_event import ChatRequestEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.skills import Skills

if TYPE_CHECKING:
    from flink_agents.api.events.chat_event import ChatResponseEvent

_LOG = logging.getLogger(__name__)

_DEFAULT_CHAT_MODEL = "_default_chat_model"
_DEFAULT_SCHEMA_PROMPT = "_default_schema_prompt"
_DEFAULT_NATIVE_SCHEMA_PROMPT = "_default_native_schema_prompt"
_DEFAULT_USER_PROMPT = "_default_user_prompt"
# Action config flag recording that the agent registered _DEFAULT_USER_PROMPT,
# as output_schema does for _DEFAULT_SCHEMA_PROMPT. start_action looks up a
# built-in prompt only when the running scope's own action config declares it,
# keeping a sub-agent's prompts its own on a runtime whose child scope falls
# back to the root agent's resources.
_HAS_USER_PROMPT = "has_user_prompt"
_OUTPUT_SCHEMA = "_output_schema"
_SKILLS_ARGUMENT = "skills"
_SKILLS_SOURCES_ARGUMENT = "skills_sources"
_SKILLS_RESOURCE_NAME = "_react_agent_skills"


def _with_skills(
    chat_model: ResourceDescriptor, skills: Sequence[str] | None
) -> ResourceDescriptor:
    """Combine constructor skills into a copy of the chat model descriptor."""
    if isinstance(skills, str):
        # A bare string would otherwise be iterated into one-letter skill names.
        err_msg = f"skills must be a list of skill names, not the string {skills!r}."
        raise TypeError(err_msg)
    if not skills:
        return chat_model
    declared = chat_model.arguments.get(_SKILLS_ARGUMENT) or []
    merged = list(dict.fromkeys([*declared, *skills]))
    return chat_model.model_copy(
        update={"arguments": {**chat_model.arguments, _SKILLS_ARGUMENT: merged}}
    )


def _with_skills_sources(
    chat_model: ResourceDescriptor, skills: Skills
) -> ResourceDescriptor:
    """Record source declarations as a marker in the chat model descriptor."""
    if not skills.sources:
        return chat_model
    return chat_model.model_copy(
        update={
            "arguments": {
                **chat_model.arguments,
                _SKILLS_SOURCES_ARGUMENT: skills.sources,
            }
        }
    )

# Kept byte-identical to the Java NATIVE_SCHEMA_PROMPT_PREFIX.
_NATIVE_SCHEMA_PROMPT_PREFIX = (
    "The final response must include the information for every required field of"
    " the following schema. It does not need to be in JSON format. Schema: "
)


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
                    ChatMessage.system('An example of output is {"result": 30.32}.'),
                    ChatMessage.user("What is ({a} + {b}) * {c}"),
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

    def __init__(
        self,
        *,
        chat_model: ResourceDescriptor,
        prompt: Prompt | None = None,
        output_schema: type[BaseModel] | RowTypeInfo | None = None,
        skills: Sequence[str] | Skills | None = None,
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
            The schema should be RowTypeInfo or subclass of BaseModel. When given,
            the ReAct agent adds a system prompt describing it and parses the
            response according to it. If the chat model applies the schema
            natively, the prompt describes the schema's fields without requiring
            JSON, and the schema is applied on the final conversion. Otherwise it
            asks for a JSON response matching the schema.
        skills : Optional[Sequence[str] | Skills] = None
            Either the names of the skills to expose to the chat model, each
            matching the ``name`` of a ``SKILL.md`` available to the agent, or a
            :class:`Skills` object declaring one or more skill sources.

            With names, they are combined with the ``skills`` argument of
            ``chat_model``: the descriptor's skills come first, followed by these,
            keeping only the first occurrence of each name. ``None`` or an empty
            sequence adds nothing, so the descriptor is used exactly as given.

            With a :class:`Skills` object, its sources are registered on the agent
            and recorded as a marker on the chat model descriptor; the skill names
            are resolved from those sources at runtime and appended to any
            explicitly named skills. A single ``Skills`` may declare several
            sources, so the object is not limited to one source. To combine
            sources declared in separate objects, gather them into one ``Skills``,
            declare further ``@skills`` methods, or register them on the agent with
            ``add_resource``. An empty ``Skills`` adds nothing.

            In both forms the descriptor itself is never modified, and only skill
            names are combined; the ``bash`` tool's command policy, such as
            ``allowed_commands``, stays owned by the chat model descriptor and is
            left unchanged.

        Raises:
        ------
        TypeError
            If the schema is neither a RowTypeInfo nor a BaseModel subclass, if a
            BaseModel schema cannot be rendered as a JSON Schema, or if ``skills``
            is a bare string rather than a sequence of names.
        """
        super().__init__()

        # Handle skills: either a Skills object (register sources + marker) or
        # a list of skill names (merge into descriptor's "skills" argument).
        if isinstance(skills, Skills):
            if skills.sources:
                self.add_resource(
                    _SKILLS_RESOURCE_NAME, ResourceType.SKILLS, skills
                )
                chat_model = _with_skills_sources(chat_model, skills)
            # else: empty Skills — pass descriptor unchanged
        else:
            chat_model = _with_skills(chat_model, skills)

        self.add_resource(
            _DEFAULT_CHAT_MODEL,
            ResourceType.CHAT_MODEL,
            chat_model,
        )

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
            self._resources[ResourceType.PROMPT][_DEFAULT_NATIVE_SCHEMA_PROMPT] = (
                Prompt.from_text(text=f"{_NATIVE_SCHEMA_PROMPT_PREFIX}{json_schema}")
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
            has_user_prompt=bool(prompt),
        )

    @staticmethod
    def start_action(event: Event, ctx: RunnerContext) -> None:
        """Start action to format user input and send chat request event."""
        # Built-in events reach an action as their concrete subclass (restored
        # at the JSON boundary), so the dispatched InputEvent is used directly.
        usr_input = cast("InputEvent", event).input

        prompt = (
            _declared_prompt(ctx, _DEFAULT_USER_PROMPT)
            if ctx.get_action_config_value(key=_HAS_USER_PROMPT)
            else None
        )

        if isinstance(usr_input, bool | str | int | float | type(None)):
            usr_input = str(usr_input)
            if prompt:
                usr_msgs = prompt.format_messages(
                    role=MessageRole.USER, input=usr_input
                )
            else:
                usr_msgs = [ChatMessage.user(usr_input)]
        else:
            if not prompt:
                if isinstance(usr_input, dict) and set(usr_input.keys()) == {"input"}:
                    # The one structured shape that works without a user-supplied
                    # prompt: a single "input" entry, the arguments of a model-driven
                    # call against an input schema declaring only that field. The
                    # value becomes the user message directly, mirroring the
                    # prompt-less primitive branch.
                    usr_msgs = [ChatMessage.user(str(usr_input["input"]))]
                else:
                    err_msg = (
                        f"Input type is {usr_input.__class__}, which is not primitive types. "
                        f"User should provide prompt to help convert it to ChatMessage."
                    )
                    raise RuntimeError(err_msg)
            else:
                if isinstance(usr_input, Row):
                    usr_input = usr_input.as_dict(recursive=True)
                elif isinstance(usr_input, dict):
                    pass
                else:  # regard as pojo
                    usr_input = usr_input.__dict__
                # Convert Any values to str to match format_messages signature
                str_usr_input = {k: str(v) for k, v in usr_input.items()}
                usr_msgs = prompt.format_messages(
                    role=MessageRole.USER, **str_usr_input
                )

        output_schema = ctx.get_action_config_value(key="output_schema")

        if output_schema is not None:
            schema_prompt = _declared_prompt(ctx, _DEFAULT_SCHEMA_PROMPT)
            # A plan built before the native prompt existed has none, and then adds
            # no instruction on the native path.
            instruction = (
                ReActAgent._prompt_or_none(ctx, _DEFAULT_NATIVE_SCHEMA_PROMPT)
                if ReActAgent._schema_applied_natively(output_schema, ctx)
                else schema_prompt
            )
            if instruction is not None:
                instruct = instruction.format_messages()
                index = find_first_system_message(usr_msgs)
                usr_msgs = usr_msgs[: index + 1] + instruct + usr_msgs[index + 1 :]

        ctx.send_event(
            ChatRequestEvent(
                model=_DEFAULT_CHAT_MODEL,
                messages=usr_msgs,
                output_schema=output_schema,
            )
        )

    @staticmethod
    def _prompt_or_none(ctx: RunnerContext, name: str) -> Prompt | None:
        try:
            return cast("Prompt", ctx.get_resource(name, ResourceType.PROMPT))
        except KeyError:
            return None

    @staticmethod
    def _schema_applied_natively(
        output_schema: OutputSchema | None, ctx: RunnerContext
    ) -> bool:
        """Whether the default chat model enforces ``output_schema`` through the
        provider. If so, the loop is told the schema's fields without being asked
        for JSON, and the schema is applied on the final conversion.

        Any failure answers ``False`` and keeps the JSON instruction: the chat
        model action asks the same question and reports the failure itself.
        """
        try:
            chat_model = ctx.get_resource(_DEFAULT_CHAT_MODEL, ResourceType.CHAT_MODEL)
            return isinstance(
                chat_model, BaseChatModelSetup
            ) and chat_model.will_apply_native_structured_output(output_schema)
        except (CancelledError, InterruptedError):
            raise
        except Exception:
            _LOG.debug(
                "Keeping the JSON schema instruction: the chat model could not answer "
                "whether it applies the schema natively.",
                exc_info=True,
            )
            return False

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def stop_action(event: Event, ctx: RunnerContext) -> None:
        """Stop action to output result."""
        event = cast("ChatResponseEvent", event)
        response = event.response

        if event.structured_output is not None:
            output = event.structured_output
        else:
            output = response.text

        ctx.send_event(OutputEvent(output=output))


def _declared_prompt(ctx: RunnerContext, name: str) -> Prompt:
    """Resolve a built-in prompt the running scope's action config declares.

    The constructor registers the prompt together with its declaration, so a
    failed lookup is a broken plan and is reported rather than silently dropping
    the prompt.
    """
    try:
        return cast("Prompt", ctx.get_resource(name, ResourceType.PROMPT))
    except KeyError as e:
        err_msg = (
            f"Prompt {name} is declared by this agent's action config but cannot "
            f"be resolved."
        )
        raise RuntimeError(err_msg) from e
