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
import re
from abc import ABC, abstractmethod
from enum import Enum
from typing import Any, ClassVar, Dict, List, Mapping, Sequence, Tuple, cast

from pydantic import ConfigDict, Field, PrivateAttr, field_validator
from typing_extensions import override

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import (
    ChatMessage,
    MessageRole,
    find_first_system_message,
)
from flink_agents.api.chat_models.subagent_tool import SubagentTool
from flink_agents.api.metric_group import MetricGroup
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.skills import BASH_TOOL, LOAD_SKILL_TOOL
from flink_agents.api.subagent import CALLABLE_NAME_PREFIX, SubagentSetup
from flink_agents.api.tools.tool import Tool


class NativeStructuredOutputSupport(str, Enum):
    """How far a connection can apply an output schema natively to one request.

    Attributes:
    ----------
    INFEASIBLE : str
        No request this connection builds from these arguments can carry the schema
        natively. Binding: no structured-output policy can overrule it.
    FEASIBLE : str
        The request can carry the schema natively, but the effective model is not known
        to honor it. Advisory: a policy may still choose the native path, and a request
        built with the schema carries it exactly as under ``NATIVE_RECOMMENDED``.
    NATIVE_RECOMMENDED : str
        The request can carry the schema natively and the effective model is known to
        honor it.
    """

    INFEASIBLE = "infeasible"
    FEASIBLE = "feasible"
    NATIVE_RECOMMENDED = "native_recommended"


class StructuredOutputStrategy(str, Enum):
    """User intent about how an output schema should be applied to a chat request.

    This expresses *policy* only. Whether a connection *can* apply the provider's
    native structured-output API to a request is the connection's own answer, a
    ``NativeStructuredOutputSupport``. ``resolves_to_native`` combines the two.

    Inherits from ``str`` so the value survives the JSON-carried bridge to Java.
    Java serializes this enum as its *name* ("NATIVE") while the value here is
    lowercase, so ``_missing_`` accepts either form in any case — matching the
    case-insensitive resolver on the Java side.

    Attributes:
    ----------
    AUTO : str
        Use the provider's native structured-output API when the connection answers
        ``NATIVE_RECOMMENDED``, and fall back to prompt engineering otherwise. The
        default.
    NATIVE : str
        Use the provider's native structured-output API whenever the request can carry
        the schema, without consulting the model's capability, and fail when it cannot.
    PROMPT : str
        Never use the provider's native structured-output API; rely on prompt
        engineering alone. Matches the behavior of connections that have no native
        translation.
    """

    AUTO = "auto"
    NATIVE = "native"
    PROMPT = "prompt"

    def resolves_to_native(self, support: NativeStructuredOutputSupport) -> bool:
        """Resolve this policy against a connection's answer into whether to go native.

        ``AUTO`` goes native only on ``NATIVE_RECOMMENDED`` and keeps the
        prompt-engineering fallback otherwise. ``NATIVE`` ignores the advisory model
        capability and goes native on ``FEASIBLE`` as well, so an explicit user intent
        surfaces a provider error rather than silently degrading. ``PROMPT`` never goes
        native.

        Parameters
        ----------
        support : NativeStructuredOutputSupport
            The connection's answer for the request being resolved.

        Returns:
        -------
        bool
            ``True`` if native structured output should be applied.

        Raises:
        ------
        TypeError
            If ``support`` is ``None``, whatever this policy is.
        ValueError
            If this is ``NATIVE`` and ``support`` is ``INFEASIBLE``: the request has
            no native form to send, so a forced native request fails here, before any
            model call, instead of silently falling back.
        """
        if support is None:
            msg = "support must be a NativeStructuredOutputSupport, not None."
            raise TypeError(msg)
        if self == StructuredOutputStrategy.PROMPT:
            return False
        if self == StructuredOutputStrategy.AUTO:
            return support == NativeStructuredOutputSupport.NATIVE_RECOMMENDED
        if support == NativeStructuredOutputSupport.INFEASIBLE:
            msg = (
                "Structured output strategy NATIVE was requested, but the connection"
                " cannot apply this output schema natively to the request."
            )
            raise ValueError(msg)
        return True

    @classmethod
    def _missing_(cls, value: object) -> "StructuredOutputStrategy | None":
        if isinstance(value, str):
            normalized = value.lower()
            for member in cls:
                if normalized in (member.value, member.name.lower()):
                    return member
        return None


class BaseChatModelConnection(Resource, ABC):
    """Base abstract class for chat model connection.

    Responsible for managing model service connection configurations, such as:
    - Service address (base_url)
    - API key (api_key)
    - Connection timeout (timeout)
    - Model name (model_name)
    - Authentication information, etc.

    Provides the basic chat interface for direct communication with model services.

    One connection can be shared in multiple chat model setup.
    """

    # Reject unrecognized constructor arguments instead of silently ignoring them
    # (pydantic's default extra="ignore"), so a misspelled or unsupported config
    # key fails loudly at construction time instead of appearing to apply and
    # then having no effect. Java-backed subclasses (JavaChatModelConnection,
    # JavaChatModelSetup) override this back to "ignore", since their descriptor
    # arguments intentionally carry implementation-specific, provider-facing keys
    # (e.g. extract_reasoning) that this base has no field for.
    model_config = ConfigDict(arbitrary_types_allowed=True, extra="forbid")

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.CHAT_MODEL_CONNECTION

    def supports_native_structured_output(
        self,
        output_schema: OutputSchema | None,
        tools: List[Tool] | None,
        model_kwargs: Mapping[str, Any] | None,
    ) -> NativeStructuredOutputSupport:
        """Whether a request built from these arguments can carry ``output_schema``
        natively, and whether its effective model is known to honor it.

        The answer combines two questions of different weight. Feasibility is binding:
        an ``INFEASIBLE`` request has no native form to send, so no policy can overrule
        it. Capability is advisory: ``FEASIBLE`` and ``NATIVE_RECOMMENDED`` differ only
        in whether the effective model is known to honor a native schema, which a
        configured policy may overrule. A connection that classifies by model name
        answers ``FEASIBLE`` for a name it does not recognize.

        The effective model is the one the request would actually run on, which need
        not be the ``model`` parameter verbatim: a connection with a configured default
        model applies that fallback, and a deployment-based provider judges the model
        backing the deployment rather than the deployment name the request targets.

        An override must answer from the same logic its own request path uses to decide
        the native branch, so that the answer cannot drift from what the request ends up
        carrying. An override's native branch carries the schema whenever this answer
        is not ``INFEASIBLE``. Capability only separates ``FEASIBLE`` from
        ``NATIVE_RECOMMENDED`` and does not change the request; whether to pass a schema
        at all is the caller's strategy to decide.

        ``FEASIBLE`` or better is not a promise that the call succeeds: a connection may
        still raise once its native branch has decided to apply the schema, as happens
        where the caller supplied a conflicting response format.

        The default ``INFEASIBLE`` is correct only for a connection that translates no
        schema at all. A connection whose request path has a native branch but which
        leaves this unoverridden reports every request infeasible, so a caller never
        reaches that branch.

        Answers about the request rather than validating it. A ``None``
        ``output_schema`` is an unconstrained request, a ``None`` ``tools`` is a request
        binding no tools, and a ``None`` ``model_kwargs`` is accepted; none of the three
        may raise. The parameters must be read without being consumed, so that the same
        mapping still builds the request the answer was about.

        Parameters
        ----------
        output_schema : OutputSchema | None
            The schema the request would carry, or ``None`` for an unconstrained
            request.
        tools : List[Tool] | None
            The tools the request would bind, may be ``None`` or empty for none.
        model_kwargs : Mapping[str, Any] | None
            The parameters the request would be built from, may be ``None``.

        Returns:
        -------
        NativeStructuredOutputSupport
            How far this connection can apply ``output_schema`` natively.
        """
        return NativeStructuredOutputSupport.INFEASIBLE

    def _reject_unsupported_output_schema(
        self, output_schema: OutputSchema | None
    ) -> None:
        """Refuse an output schema this connection cannot translate natively.

        ``chat`` is abstract here, so there is no inherited body that could absorb a
        schema loudly. A connection without a native structured-output translation
        calls this as the first statement of its ``chat`` instead, which turns a
        schema it could only drop into an error rather than an unconstrained response
        that the caller would mistake for a schema-conforming one.

        Args:
            output_schema: The schema the response should conform to. ``None`` returns
                without effect.

        Raises:
            NotImplementedError: If ``output_schema`` is not ``None``.
        """
        if output_schema is None:
            return
        cls = type(self)
        msg = (
            f"{cls.__module__}.{cls.__qualname__} has no native structured-output"
            " translation, so it cannot honor the given output schema. Override chat()"
            " to translate the schema natively, or pass no schema so the caller applies"
            " the prompt-engineering fallback."
        )
        raise NotImplementedError(msg)

    DEFAULT_REASONING_PATTERNS: ClassVar[Tuple[re.Pattern[str], ...]] = (
        re.compile(r"<think>(.*?)</think>", re.DOTALL | re.IGNORECASE),
        re.compile(r"<analysis>(.*?)</analysis>", re.DOTALL | re.IGNORECASE),
        re.compile(r"<reasoning>(.*?)</reasoning>", re.DOTALL | re.IGNORECASE),
        re.compile(
            r"```(?:think|reasoning|thought)\s*\n(.*?)\n```", re.DOTALL | re.IGNORECASE
        ),
        re.compile(
            r"(?:^|\n)Reasoning:\s*(.*?)(?:\n{2,}|$)", re.DOTALL | re.IGNORECASE
        ),
    )

    @staticmethod
    def _extract_reasoning(
        content: str,
        patterns: List[re.Pattern[str]] = DEFAULT_REASONING_PATTERNS,
    ) -> Tuple[str, str | None]:
        """Extract content within <think></think> tags and clean the remaining content.

        Parameters
        ----------
        content: str
          Original content text

        Returns:
        -------
        Tuple[str, Optional[str]]
          The cleaned content and the reasoning part.
        """
        if not content:
            return "", None

        reasoning_chunks: List[str] = []
        cleaned = content

        for pat in patterns:
            matches = pat.findall(cleaned)
            if matches:
                reasoning_chunks.extend(m.strip() for m in matches if m.strip())
                cleaned = pat.sub("", cleaned)

        if not reasoning_chunks:
            return cleaned, None

        reasoning = "\n\n".join(reasoning_chunks)
        cleaned = re.sub(r"\n{3,}", "\n\n", cleaned)
        cleaned = re.sub(r" {2,}", " ", cleaned)
        cleaned = cleaned.strip()
        return cleaned, reasoning

    @abstractmethod
    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Direct communication with model service for chat conversation.

        Parameters
        ----------
        messages : Sequence[ChatMessage]
            Input message sequence
        tools : Optional[List]
            List of tools that can be called by the model
        output_schema : OutputSchema | None
            The schema the response should conform to, or ``None`` for an
            unconstrained response. This is framework-level execution metadata, and
            every implementation must declare it as a named parameter rather than let
            it fall into ``**kwargs``: ``**kwargs`` is forwarded to the provider SDK,
            so a schema landing there would reach the request body.

            An ``OutputSchema`` wraps either a ``BaseModel`` subclass or a
            ``RowTypeInfo``. No implementation translates a ``RowTypeInfo`` natively,
            and what follows differs by implementation: one that translates a
            ``BaseModel`` natively skips the ``RowTypeInfo`` and leaves the request
            unchanged, so the caller keeps the prompt-engineering fallback; one with
            no native translation at all rejects it, as described below. The skip is a
            deliberate, permanent fallback, not a translation still to be written.

            A ``BaseModel`` subclass is refused with a ``TypeError`` naming the schema
            class and chaining the underlying error as its cause, both when it has no
            JSON Schema at all and when it has one that this provider's renderer will
            not accept. The second outcome is per-provider: a model with an untyped
            member renders under Pydantic, and one provider's renderer takes it while
            another refuses it. Neither is raised unless the request was going to
            carry a native schema, since an implementation renders only once it has
            decided to send one, so an unrenderable schema reports nothing when some
            other condition has already ruled the native branch out. An implementation
            with a native translation sends the schema natively whenever
            ``supports_native_structured_output`` would answer other than
            ``INFEASIBLE``, whatever the effective model's capability, since the
            caller has already decided to send it.

            A ``BaseModel`` subclass that renders but declares no fields is sent as
            rendered, leaving the receiving provider to accept or refuse it.

            An implementation with no native structured-output translation at all is a
            separate case, not the ``RowTypeInfo`` skip above: it rejects *every*
            non-``None`` schema, ``RowTypeInfo`` included, via
            ``_reject_unsupported_output_schema``, because it could otherwise only
            drop the schema silently. A caller that wants the prompt-engineering
            fallback from such an implementation must pass ``None``.
        **kwargs : Any
            Additional parameters passed to the model service (e.g., temperature,
            max_tokens, etc.)

        Returns:
        -------
        ChatMessage
            Model response message
        """


def _describe_output_schema(output_schema: OutputSchema) -> str:
    inner = output_schema.output_schema
    if isinstance(inner, type):
        return f"{inner.__module__}.{inner.__qualname__}"
    return str(inner)


class BaseChatModelSetup(Resource):
    """Base abstract class for chat model setup.

    Responsible for managing chat configurations, such as:
    - Connection to chat model service (connection)
    - Model name (model)
    - Prompt templates (prompt)
    - Available tools (tools)
    - Generation parameters (temperature, max_tokens, etc.)
    - Context management

    Internally calls ChatModelConnection to perform actual communication with llm.

    Different chat model setups can share the same chat model connection and contains
    different chat configurations.
    """

    # See BaseChatModelConnection.model_config for rationale.
    model_config = ConfigDict(arbitrary_types_allowed=True, extra="forbid")

    connection: str = Field(description="The referenced connection name.")
    model: str = Field(description="Name of the chat model to use.")
    _resolved_connection: BaseChatModelConnection | None = PrivateAttr(default=None)
    prompt: Prompt | str | None = None
    tools: List[str] | List[Tool] = Field(default_factory=list)
    subagents: List[str] = Field(
        default_factory=list,
        description="Names of the AGENT resources this setup may delegate to.",
    )
    skills: List[str] | None = None
    skill_discovery_prompt: str | None = None
    allowed_commands: List[str] = Field(default_factory=list)
    allowed_script_dirs: List[str] = Field(default_factory=list)
    structured_output_strategy: StructuredOutputStrategy = Field(
        default=StructuredOutputStrategy.AUTO,
        description=(
            "Intent about how an output schema should be applied. "
            "``resolves_to_native`` combines this policy with the "
            "connection's NativeStructuredOutputSupport answer. An explicitly null "
            "value is normalized to AUTO, so a validated setup always carries a real "
            "strategy."
        ),
    )

    @field_validator("structured_output_strategy", mode="before")
    @classmethod
    def _normalize_null_strategy(cls, value: Any) -> Any:
        """Normalize an explicitly null strategy to the ``AUTO`` default.

        A configuration source can carry the key with a null value instead of
        omitting it. Java cannot tell those two apart — its descriptor argument
        lookup returns null in both cases and resolves them to ``AUTO`` — so an
        explicit null resolves to ``AUTO`` here too rather than being rejected.
        Unknown non-null values still fail validation.
        """
        if value is None:
            return StructuredOutputStrategy.AUTO
        return value

    @property
    @abstractmethod
    def model_kwargs(self) -> Dict[str, Any]:
        """Return chat model settings."""

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.CHAT_MODEL

    @override
    def open(self) -> None:
        self._resolved_connection = cast(
            "BaseChatModelConnection",
            self.resource_context.get_resource(
                self.connection, ResourceType.CHAT_MODEL_CONNECTION
            ),
        )
        if self.prompt is not None:
            if isinstance(self.prompt, str):
                # Get prompt resource if it's a string
                self.prompt = cast(
                    "Prompt",
                    self.resource_context.get_resource(
                        self.prompt, ResourceType.PROMPT
                    ),
                )
        if self.skills is not None:
            self.skill_discovery_prompt = (
                self.resource_context.generate_available_skills_prompt(*self.skills)
                or None
            )

        # Rebuilt from scratch: open() may run again on the same instance, and the
        # callables must not accumulate. Sub-agent callables are derived from
        # ``subagents`` rather than declared under ``tools``, so a previous build of
        # them is dropped here instead of being read back as a declaration.
        declared: List[str | Tool] = [
            entry for entry in self.tools if not isinstance(entry, SubagentTool)
        ]
        declared_names = [
            entry if isinstance(entry, str) else entry.name for entry in declared
        ]
        if self.skills is not None:
            for skill_tool in (LOAD_SKILL_TOOL, BASH_TOOL):
                if skill_tool not in declared_names:
                    declared.append(skill_tool)
                    declared_names.append(skill_tool)

        callables: List[Tool] = []
        callable_names: set[str] = set()
        for entry, name in zip(declared, declared_names, strict=True):
            if name in callable_names:
                msg = f"Duplicate callable name: {name}"
                raise ValueError(msg)
            callable_names.add(name)
            callables.append(
                entry
                if isinstance(entry, Tool)
                else cast(
                    "Tool",
                    self.resource_context.get_resource(name, ResourceType.TOOL),
                )
            )
        for name in self.subagents:
            # Tools are forbidden to carry the reserved prefix at registration, so a
            # prefixed callable name can only come from this loop and a clash with a
            # tool is impossible. Checked before the schema below, because a name
            # declared twice is a mistake in the declaration whether or not it ends
            # up registered.
            callable_name = CALLABLE_NAME_PREFIX + name
            if callable_name in callable_names:
                msg = f"Duplicate callable name: {callable_name}"
                raise ValueError(msg)
            callable_names.add(callable_name)
            setup = self.resource_context.get_resource(name, ResourceType.AGENT)
            # A sub-agent owned by the other language resolves to a bridge handle
            # here, which carries no schema to declare, so it is rejected instead
            # of silently dropped.
            if not isinstance(setup, SubagentSetup):
                msg = (
                    f"Sub-agent {name} must resolve to a SubagentSetup, "
                    f"but was {type(setup).__name__}"
                )
                raise TypeError(msg)
            # A sub-agent that declares neither an input schema nor an input type
            # gives the model no arguments to build a call from, so the declaration
            # is a mistake rather than something to skip: fail the job at setup
            # time, consistent with the duplicate-name and bridge-handle checks
            # above.
            if setup.input_schema is None:
                msg = (
                    f"Sub-agent {name} declares neither an input schema nor an "
                    f"input type, so there are no arguments for the model to "
                    f"build a call from."
                )
                raise ValueError(msg)
            callables.append(
                SubagentTool.of(name, setup.description, setup.input_schema)
            )
        self.tools = callables

    def chat(
        self,
        messages: Sequence[ChatMessage],
        prompt_args: Mapping[str, Any] | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Execute chat conversation.

        1. Apply prompt template (if any), filled from ``prompt_args``
        2. Bind tools (if any)
        3. Call ChatModelConnection to perform actual communication
        4. Process response

        Parameters
        ----------
        messages : Sequence[ChatMessage]
            Input message sequence
        prompt_args : Mapping[str, Any] | None
            Variables used to fill the prompt template, if a prompt resource is
            configured. Values are stringified via ``str()`` to match the
            ``Prompt.format_messages`` contract.
        **kwargs : Any
            Additional parameters passed to the model service

        Returns:
        -------
        ChatMessage
            Model response message
        """
        # Apply prompt template
        if self.prompt is not None:
            str_prompt_args: Dict[str, str] = (
                {k: str(v) for k, v in prompt_args.items()} if prompt_args else {}
            )
            prompt_messages = self._get_prompt().format_messages(**str_prompt_args)

            # append meaningful messages
            # any block counts, so image-only messages survive
            for msg in messages:
                if len(msg.blocks) > 0 or msg.role == MessageRole.ASSISTANT:
                    prompt_messages.append(msg)
            messages = prompt_messages

        if self.skill_discovery_prompt:
            # Right after the first system message, or at the head when there is none.
            index = find_first_system_message(messages) + 1
            injected = [ChatMessage.system(self.skill_discovery_prompt)]
            messages = list(messages[:index]) + injected + list(messages[index:])

        return self.chat_explicit(messages, self._get_tools(), **kwargs)

    def chat_explicit(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool],
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Send the messages and tools exactly as given.

        The bound prompt, the skill-discovery message and the bound tools are not
        added. ``chat`` prepares the request and sends it through this method.

        Args:
            messages: The messages to send.
            tools: The tools to bind to the request.
            output_schema: The schema the provider applies natively, or ``None`` for
                an unconstrained request.
            **kwargs: Model parameters for this call, merged over ``model_kwargs``.

        Returns:
            The connection's response.

        Raises:
            TypeError: If ``open()`` has not resolved the connection yet.
        """
        connection = self._get_connection()
        merged_kwargs = self.model_kwargs.copy()
        merged_kwargs.update(kwargs)
        if output_schema is None:
            return connection.chat(messages, tools=tools, **merged_kwargs)
        return connection.chat(
            messages, tools=tools, output_schema=output_schema, **merged_kwargs
        )

    def will_apply_native_structured_output(
        self, output_schema: OutputSchema | None
    ) -> bool:
        """Whether ``output_schema`` should travel through the provider's native
        structured output on a call issued through ``chat_explicit`` with no tools,
        rather than be described to the model in the prompt.

        Framework-facing. A user configures the outcome through
        ``structured_output_strategy`` instead of calling this.

        The connection is asked about a request carrying this schema, no tools, and
        the parameters ``model_kwargs`` returns. Per-call keyword arguments passed to
        ``chat_explicit`` are not seen here, so a caller that adds parameters
        affecting feasibility must not rely on this answer.

        A ``True`` answer is not a promise that the call succeeds: a connection may
        still raise once its native branch applies the schema, for example on a
        conflicting caller-supplied response format.

        A setup with no resolved connection, such as one that overrides ``open`` and
        ``chat`` to answer by itself, answers ``False`` unless the strategy is
        ``NATIVE``.

        Args:
            output_schema: The schema the call would carry, or ``None`` for an
                unconstrained call.

        Returns:
            ``True`` if the schema should be applied natively; ``False`` for a
            ``None`` schema or when no connection is resolved.

        Raises:
            ValueError: If the strategy is ``NATIVE`` and no connection is resolved,
                or the connection cannot apply this schema to such a request.
        """
        if output_schema is None:
            return False
        connection = self._resolved_connection
        if connection is None:
            if self.structured_output_strategy == StructuredOutputStrategy.NATIVE:
                setup_cls = type(self)
                msg = (
                    f"Structured output strategy NATIVE was requested, but "
                    f"{setup_cls.__module__}.{setup_cls.__qualname__} has no "
                    "connection to apply the output schema natively."
                )
                raise ValueError(msg)
            return False
        support = connection.supports_native_structured_output(
            output_schema, [], self.model_kwargs
        )
        if (
            support == NativeStructuredOutputSupport.INFEASIBLE
            and self.structured_output_strategy == StructuredOutputStrategy.NATIVE
        ):
            cls = type(connection)
            msg = (
                f"Structured output strategy NATIVE was requested, but "
                f"{cls.__module__}.{cls.__qualname__} cannot apply the output schema "
                f"{_describe_output_schema(output_schema)} natively. Use AUTO or "
                "PROMPT to describe the schema in the prompt instead, or supply a "
                "schema this connection can translate."
            )
            raise ValueError(msg)
        return self.structured_output_strategy.resolves_to_native(support)

    def _record_token_metrics(
        self,
        model_name: str,
        prompt_tokens: int,
        completion_tokens: int,
        metric_group: MetricGroup | None,
    ) -> None:
        """Record token usage metrics for the given model.

        Parameters
        ----------
        model_name : str
            The name of the model used
        prompt_tokens : int
            The number of prompt tokens
        completion_tokens : int
            The number of completion tokens
        metric_group : MetricGroup | None
            The metric group captured when the request was initiated. If None, token
            metrics are skipped.
        """
        if metric_group is None:
            return

        model_group = metric_group.get_sub_group("model", model_name)
        model_group.get_counter("promptTokens").inc(prompt_tokens)
        model_group.get_counter("completionTokens").inc(completion_tokens)

    def _get_connection(self) -> BaseChatModelConnection:
        if self._resolved_connection is None:
            err_msg = (
                f"Connection '{self.connection}' has not been resolved. "
                "Ensure open() is called before using the connection."
            )
            raise TypeError(err_msg)
        return self._resolved_connection

    def _get_prompt(self) -> Prompt:
        if not isinstance(self.prompt, Prompt):
            err_msg = f"Expect Prompt, but is {self.prompt.__class__.__name__}"
            raise TypeError(err_msg)
        return self.prompt

    def _get_tools(self) -> List[Tool]:
        for tool in self.tools:
            if not isinstance(tool, Tool):
                err_msg = f"Expect Tool, but is {tool.__class__.__name__}"
                raise TypeError(err_msg)
        return self.tools
