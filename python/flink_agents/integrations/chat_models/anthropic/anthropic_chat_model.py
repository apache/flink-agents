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
import uuid
from typing import Any, Dict, List, Sequence

from anthropic import Anthropic, transform_schema
from anthropic._types import NOT_GIVEN
from anthropic.types import MessageParam, TextBlockParam, ToolParam
from pydantic import BaseModel, Field, PrivateAttr
from typing_extensions import override

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.tools.tool import Tool, ToolMetadata


def to_anthropic_tool(
    *, metadata: ToolMetadata, skip_length_check: bool = False
) -> ToolParam:
    """Convert to Anthropic tool: https://docs.anthropic.com/en/api/messages#body-tools."""
    if not skip_length_check and len(metadata.description) > 1024:
        msg = (
            "Tool description exceeds maximum length of 1024 characters. "
            "Please shorten your description or move it to the prompt."
        )
        raise ValueError(msg)
    return {
        "name": metadata.name,
        "description": metadata.description,
        "input_schema": metadata.get_parameters_dict(),
    }


def convert_to_anthropic_message(message: ChatMessage) -> MessageParam:
    """Convert ChatMessage to Anthropic MessageParam format."""
    if message.role == MessageRole.TOOL:
        return {
            "role": MessageRole.USER.value,
            "content": [
                {
                    "type": "tool_result",
                    "tool_use_id": message.extra_args.get("external_id"),
                    "content": message.content,
                }
            ],
        }
    elif message.role == MessageRole.ASSISTANT:
        # Use original Anthropic content blocks if available for context
        anthropic_content_blocks = message.extra_args.get("anthropic_content_blocks")
        content = (
            anthropic_content_blocks
            if anthropic_content_blocks is not None
            else message.content
        )
        return {
            "role": message.role.value,
            "content": content,  # type: ignore
        }
    else:
        return {
            "role": message.role.value,
            "content": message.content,
        }


def convert_to_anthropic_messages(
    messages: Sequence[ChatMessage],
) -> List[MessageParam]:
    """Convert user/assistant messages to Anthropic input messages.

    See: https://docs.anthropic.com/en/api/messages#body-messages
    """
    return [
        convert_to_anthropic_message(message)
        for message in messages
        if message.role in [MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL]
    ]


def convert_to_anthropic_system_prompts(
    messages: Sequence[ChatMessage],
) -> List[TextBlockParam]:
    """Convert system messages to Anthropic system prompts.

    See: https://docs.anthropic.com/en/api/messages#body-system
    """
    system_messages = [
        message for message in messages if message.role == MessageRole.SYSTEM
    ]
    return [
        TextBlockParam(type="text", text=message.content) for message in system_messages
    ]


# Models Anthropic documents native structured-output support for. Source of truth:
# https://platform.claude.com/docs/en/build-with-claude/structured-outputs
#
# The documented rule is generational rather than a per-snapshot list: structured
# outputs are generally available for Claude 4.5 and later models, and for Claude Mythos
# Preview. Names from the 4.6 generation onward carry no date and are pinned, so the
# name is itself the snapshot and is matched exactly.
#
# The three 4.5-generation names are aliases that front a dated snapshot, so a request
# may carry either the alias or the snapshot behind it and both have to match. Those are
# matched by prefix instead, and the prefix has to retain the minor version:
# "claude-opus-4" would also capture claude-opus-4-1-20250805, which predates the cutoff
# and is not capable.
#
# A name outside both sets reports not-capable and degrades to the prompt-engineering
# fallback rather than failing at the provider.
_NATIVE_STRUCTURED_OUTPUT_MODELS = frozenset(
    {
        "claude-opus-4-6",
        "claude-opus-4-7",
        "claude-opus-4-8",
        "claude-opus-5",
        "claude-sonnet-4-6",
        "claude-sonnet-5",
        "claude-fable-5",
        "claude-mythos-5",
        "claude-mythos-preview",
    }
)

_NATIVE_STRUCTURED_OUTPUT_ALIAS_PREFIXES = (
    "claude-opus-4-5",
    "claude-sonnet-4-5",
    "claude-haiku-4-5",
)


# Models Anthropic documents as rejecting assistant-message prefilling. Source of truth:
# https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prefill-claudes-response
#
# Prefilling is not supported from the Claude 4.6 generation onward, nor on Claude
# Mythos Preview, Claude Fable 5 or Claude Mythos 5; a request that prefills one of
# them is answered with a 400 rather than a completion. Anthropic publishes no
# programmatic signal for prefill support the way it does for structured outputs, so
# the rule has to be a maintained list of names. Those names carry no date and are
# pinned, so the name is itself the snapshot and is matched exactly, and a name outside
# the list is treated as accepting the prefill.
#
# Kept in its own storage rather than derived from the structured-output allowlists
# above, whose contents it currently coincides with. The two encode different
# documented boundaries: structured output starts at the 4.5 generation while prefill
# rejection starts at 4.6, so the three 4.5-generation names are structured-output
# capable and still accept a prefill. Sharing one list would hold only until a model
# moves one boundary without moving the other.
_PREFILL_UNSUPPORTED_MODELS = frozenset(
    {
        "claude-opus-4-6",
        "claude-opus-4-7",
        "claude-opus-4-8",
        "claude-opus-5",
        "claude-sonnet-4-6",
        "claude-sonnet-5",
        "claude-fable-5",
        "claude-mythos-5",
        "claude-mythos-preview",
    }
)


def _supports_json_prefill(effective_model: str | None) -> bool:
    """Whether ``effective_model`` accepts the prefilled assistant ``"{"`` message.

    See the list above for the source of truth and for why it is matched exactly and
    kept apart from the structured-output allowlists. An unrecognized name reports
    ``True``, which matches the documented rule: prefilling is the long-standing
    behaviour and only the listed names withdraw it. The cost of that default runs the
    opposite way to ``supports_native_structured_output``: a rejecting model this list
    has not caught up with is prefilled and answered with a 400, where an unrecognized
    name on the structured-output path degrades silently to the prompt-engineering
    fallback instead.
    """
    return effective_model not in _PREFILL_UNSUPPORTED_MODELS


def _native_output_config(output_schema: Any) -> Dict[str, Any] | None:
    """Build the Anthropic ``output_config`` for a native structured-output request.

    Returns ``None`` (leaving the request unchanged) unless the schema is a
    ``BaseModel`` subclass. A ``RowTypeInfo`` schema is skipped so it keeps the
    prompt-engineering fallback.

    Anthropic's format object carries only the schema and its type, so it shares no
    shape with the providers that nest the schema under a named, strict
    ``json_schema`` object and is built here rather than in a shared helper.
    """
    if output_schema is None:
        return None
    model = (
        output_schema.output_schema if isinstance(output_schema, OutputSchema) else None
    )
    if not (isinstance(model, type) and issubclass(model, BaseModel)):
        return None
    return {"format": {"type": "json_schema", "schema": transform_schema(model)}}


class AnthropicChatModelConnection(BaseChatModelConnection):
    """Manages the connection to the Anthropic AI models for chat interactions.

    Attributes:
    ----------
    api_key : str
        The Anthropic API key.
    max_retries : int
        The number of times to retry the API call upon failure.
    timeout : float
        The number of seconds to wait for an API call before it times out.
    reuse_client : bool
        Whether to reuse the Anthropic client between requests.
    """

    api_key: str = Field(default=None, description="The Anthropic API key.")

    max_retries: int = Field(
        default=3,
        description="The number of times to retry the API call upon failure.",
        ge=0,
    )
    timeout: float = Field(
        default=60.0,
        description="The number of seconds to wait for an API call before it times out.",
        ge=0,
    )

    def __init__(
        self,
        api_key: str | None = None,
        max_retries: int = 3,
        timeout: float = 60.0,
        **kwargs: Any,
    ) -> None:
        """Initialize the Anthropic chat model connection."""
        super().__init__(
            api_key=api_key,
            max_retries=max_retries,
            timeout=timeout,
            **kwargs,
        )

    _client: Anthropic | None = PrivateAttr(default=None)

    @property
    def client(self) -> Anthropic:
        """Get or create the Anthropic client instance."""
        if self._client is None:
            self._client = Anthropic(
                api_key=self.api_key, max_retries=self.max_retries, timeout=self.timeout
            )
        return self._client

    @override
    def supports_native_structured_output(self, effective_model: str | None) -> bool:
        """Whether Anthropic documents structured output for ``effective_model``.

        See the module-level allowlists for the source of truth and for why the
        4.5-generation aliases are matched by prefix while every other name is matched
        exactly. A name outside both reports ``False`` so it degrades to the
        prompt-engineering fallback rather than failing at the provider.

        Reads no instance state, so capability stays answerable independently of how
        the connection was configured.
        """
        if not effective_model:
            return False
        return effective_model in _NATIVE_STRUCTURED_OUTPUT_MODELS or (
            effective_model.startswith(_NATIVE_STRUCTURED_OUTPUT_ALIAS_PREFIXES)
        )

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Direct communication with Anthropic model service for chat conversation.

        Parameters
        ----------
        messages : Sequence[ChatMessage]
            Input message sequence
        tools : Optional[List]
            List of tools that can be called by the model
        output_schema : OutputSchema | None
            The schema the response should conform to, or ``None`` for an unconstrained
            response. Native structured output is applied only for a ``BaseModel``
            schema on a model the provider documents as capable, and only when the
            caller has not already supplied ``output_config``. Any other combination
            sends no derived schema and keeps the prompt-engineering fallback.
        **kwargs : Any
            Additional parameters passed to the model service (e.g., temperature,
            max_tokens, etc.). ``json_prefill`` is consumed here rather than
            forwarded: it selects the prefilled assistant ``"{"`` message described
            below and is not a request field the provider accepts.

        Returns:
        -------
        ChatMessage
            Model response message
        """
        anthropic_tools = None
        if tools is not None:
            anthropic_tools = [
                to_anthropic_tool(metadata=tool.metadata) for tool in tools
            ]

        anthropic_system = convert_to_anthropic_system_prompts(messages)
        anthropic_messages = convert_to_anthropic_messages(messages)

        # Removed from kwargs unconditionally: it is a framework parameter, and leaving
        # it in place would reach messages.create as an unknown request field.
        json_prefill = kwargs.pop("json_prefill", False)

        # TODO(#912): the requested strategy is not visible here, so this check
        # cannot tell an explicit NATIVE request apart from one that merely
        # resolved to native. A caller asking for NATIVE on a model this
        # predicate rejects therefore degrades silently to the prompt-engineering
        # fallback instead of getting an error. Once strategy resolution is wired
        # up, NATIVE must either bypass this capability check or fail explicitly.
        if output_schema is not None and self.supports_native_structured_output(
            kwargs.get("model")
        ):
            output_config = _native_output_config(output_schema)
            # An output_config already in kwargs is the caller being explicit about the
            # exact parameter this branch writes, so it is left alone and the schema
            # keeps the prompt-engineering fallback. Writing over it would drop the
            # caller's value with no error and no other trace.
            if output_config is not None and "output_config" not in kwargs:
                kwargs["output_config"] = output_config

        # JSON prefill appends a prefilled assistant "{" message to steer the model
        # into emitting a JSON document. It applies only when the request carries none
        # of three features:
        #   - tool use, because the prefill forces JSON text instead of native tool_use
        #     blocks;
        #   - structured outputs, which Anthropic documents as incompatible with message
        #     prefilling - output_config already has the provider enforcing the very
        #     document the prefill exists to coax out of the model;
        #   - a model that rejects prefilling outright, which answers with a 400 rather
        #     than a completion.
        # Evaluated after the block above so the output_config test covers both ways one
        # can reach the request: derived from output_schema there, or supplied by the
        # caller. It keys on what the request ends up carrying rather than on what was
        # supplied, so a schema that could not be sent natively keeps the prefill its
        # prompt-engineering fallback depends on - unless the caller supplied an
        # output_config of its own.
        prefill_applied = (
            json_prefill is True
            and not anthropic_tools
            and "output_config" not in kwargs
            and _supports_json_prefill(kwargs.get("model"))
        )
        if prefill_applied:
            anthropic_messages = [
                *anthropic_messages,
                {"role": MessageRole.ASSISTANT.value, "content": "{"},
            ]

        message = self.client.messages.create(
            messages=anthropic_messages,
            tools=anthropic_tools or NOT_GIVEN,
            system=anthropic_system or NOT_GIVEN,
            **kwargs,
        )

        extra_args = {}
        # Record token metrics if model name and usage are available
        model_name = kwargs.get("model")
        if model_name and message.usage:
            extra_args["model_name"] = model_name
            extra_args["promptTokens"] = message.usage.input_tokens
            extra_args["completionTokens"] = message.usage.output_tokens

        # A response may lead with a non-text block (e.g. a tool_use block when
        # the model calls a tool without any preface), so pick the first text
        # block instead of assuming content[0] is text.
        text = next(
            (block.text for block in message.content if block.type == "text"), ""
        )

        # The response continues the prefilled "{" rather than repeating it, so the
        # document is only complete once it is put back. Keyed on the decision actually
        # applied above: reconstructing on any other signal either prepends a stray "{"
        # or drops a required one, and the response itself gives no sign of either.
        if prefill_applied:
            text = "{" + text

        if message.stop_reason == "tool_use":
            tool_calls = [
                {
                    "id": uuid.uuid4(),
                    "type": "function",
                    "function": {
                        "name": content_block.name,
                        "arguments": content_block.input,
                    },
                    "original_id": content_block.id,
                }
                for content_block in message.content
                if content_block.type == "tool_use"
            ]

            extra_args["anthropic_content_blocks"] = message.content
            return ChatMessage(
                role=MessageRole(message.role),
                content=text,
                tool_calls=tool_calls,
                extra_args=extra_args,
            )
        else:
            # TODO: handle other stop_reason values according to Anthropic API:
            #  https://docs.anthropic.com/en/api/messages#response-stop-reason
            return ChatMessage(
                role=MessageRole(message.role),
                content=text,
                extra_args=extra_args,
            )

    @override
    def close(self) -> None:
        if self._client is not None:
            try:
                self._client.close()
            finally:
                self._client = None


DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-20250514"
DEFAULT_MAX_TOKENS = 1024
DEFAULT_TEMPERATURE = 0.1
DEFAULT_JSON_PREFILL = False


class AnthropicChatModelSetup(BaseChatModelSetup):
    """The settings for Anthropic Chat Model.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseChatModelSetup)
    model : str
        Specifies the Anthropic model to use. Defaults to claude-sonnet-4-20250514
        when omitted via ``__init__``. (Inherited from BaseChatModelSetup)
    prompt : Optional[Union[Prompt, str]
        Prompt template or string for the model. (Inherited from BaseChatModelSetup)
    tools : Optional[List[str]]
        List of available tools to use in the chat. (Inherited from BaseChatModelSetup)
    max_tokens: int
        The maximum number of tokens to generate before stopping. Defaults to 1024.
    temperature : float
        Amount of randomness injected into the response.
    json_prefill : bool
        When True, prefills the assistant response with "{" to enforce JSON output.
        Applies only on models Anthropic documents as accepting assistant-message
        prefilling, and is automatically disabled when tools are passed, or when the
        request carries an output_config, whether that was derived from an output
        schema or supplied by the caller. Defaults to False.
    """

    max_tokens: int = Field(
        default=DEFAULT_MAX_TOKENS,
        description="The maximum number of tokens to generate before stopping. Defaults to 1024.",
        ge=1,
    )
    temperature: float = Field(
        default=DEFAULT_TEMPERATURE,
        description="Amount of randomness injected into the response. Defaults to 0.1",
        ge=0.0,
        le=1.0,
    )
    json_prefill: bool = Field(
        default=DEFAULT_JSON_PREFILL,
        description=(
            'When True, prefills the assistant response with "{" to enforce JSON '
            "output. Defaults to False."
        ),
    )

    def __init__(
        self,
        connection: str,
        model: str = DEFAULT_ANTHROPIC_MODEL,
        max_tokens: int = DEFAULT_MAX_TOKENS,
        temperature: float = DEFAULT_TEMPERATURE,
        *,
        json_prefill: bool = DEFAULT_JSON_PREFILL,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        super().__init__(
            connection=connection,
            model=model,
            max_tokens=max_tokens,
            temperature=temperature,
            json_prefill=json_prefill,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Get model-specific keyword arguments."""
        return {
            "model": self.model,
            "max_tokens": self.max_tokens,
            "temperature": self.temperature,
            "json_prefill": self.json_prefill,
        }
