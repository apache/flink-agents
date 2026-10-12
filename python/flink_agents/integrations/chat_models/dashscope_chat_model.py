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
import os
import uuid
from typing import Any, Dict, List, Mapping, Sequence

from dashscope import Generation
from pydantic import BaseModel, Field
from typing_extensions import override

from flink_agents.api.agents.types import OutputSchema, render_output_schema
from flink_agents.api.chat_message import (
    ChatMessage,
    MessageRole,
    ReasoningBlock,
    TextBlock,
    ToolCallBlock,
    UnsupportedContentBlockError,
)
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
    NativeStructuredOutputSupport,
)
from flink_agents.api.chat_result import ChatResult, TokenUsage
from flink_agents.api.tools.tool import Tool, ToolMetadata

DEFAULT_REQUEST_TIMEOUT = 60.0
DEFAULT_MODEL = "qwen-plus"

# Models with documented json_schema support that are also served on the
# text-generation interface this connection calls. json_schema support is documented
# per family, but the interface is documented per model and differs inside a family:
# the Qwen3.7-Plus, Qwen3.7-Flash, Qwen3.8-Flash and Qwen3.8-Max families and the
# qwen3.7-max-2026-06-08 snapshot are served on the multimodal interface and answer
# Generation.call with "url error". Names are therefore matched exactly against the
# Qwen3.7-Max members served on the text interface.
# json_schema model list and mode semantics:
#   https://help.aliyun.com/zh/model-studio/json-mode
# text- vs multimodal-interface routing:
#   https://help.aliyun.com/zh/model-studio/text-generation
#
# A name outside the set reports not-capable and degrades to the prompt-engineering
# fallback rather than failing at the provider.
_NATIVE_STRUCTURED_OUTPUT_MODELS = frozenset(
    {
        "qwen3.7-max",
        "qwen3.7-max-preview",
        "qwen3.7-max-2026-05-17",
        "qwen3.7-max-2026-05-20",
    }
)


def _native_output_model(
    output_schema: OutputSchema | None,
) -> type[BaseModel] | None:
    """The model a schema translates natively to, or ``None`` where none applies.

    ``None`` covers both no schema at all and a ``RowTypeInfo``, which has no native
    translation and keeps the prompt-engineering fallback.

    Separate from the render below because the caller-conflict check needs to know
    whether a schema will be sent, and under what name, before anything is rendered.
    """
    schema = getattr(output_schema, "output_schema", None)
    if not (isinstance(schema, type) and issubclass(schema, BaseModel)):
        return None
    return schema


def _native_response_format(
    output_schema: OutputSchema | None,
) -> Dict[str, Any] | None:
    """Build the DashScope ``response_format`` for a native structured-output request.

    Returns ``None`` (leaving behavior unchanged) unless the schema is a ``BaseModel``
    subclass. A ``RowTypeInfo`` schema is skipped so it keeps the prompt-engineering
    fallback.

    Raises ``TypeError`` if a ``BaseModel`` schema cannot be rendered, naming the
    schema class rather than letting Pydantic's own error, which names only its
    internals, surface from a request the provider never sees. A schema that renders
    but declares no fields is sent as it is, leaving the provider to accept or refuse
    the document it receives.
    """
    model = _native_output_model(output_schema)
    if model is None:
        return None
    return {
        "type": "json_schema",
        "json_schema": {
            "name": model.__name__,
            "strict": True,
            "schema": render_output_schema(model, lambda m: m.model_json_schema()),
        },
    }


def to_dashscope_tool(
    metadata: ToolMetadata,
    skip_length_check: bool = False,  # noqa:FBT001
) -> Dict[str, Any]:
    """To DashScope tool."""
    if not skip_length_check and len(metadata.description) > 1024:
        msg = (
            "Tool description exceeds maximum length of 1024 characters. "
            "Please shorten your description or move it to the prompt."
        )
        raise ValueError(msg)
    return {
        "type": "function",
        "function": {
            "name": metadata.name,
            "description": metadata.description,
            "parameters": metadata.get_parameters_dict(),
        },
    }


class DashScopeChatModelConnection(BaseChatModelConnection):
    """Manage the connection to the DashScope API server.

    Attributes:
    ----------
    api_key : str
        Your DashScope API key.
    request_timeout : float
        The timeout for making http request to DashScope API server.
    """

    api_key: str = Field(
        default_factory=lambda: os.environ.get("DASHSCOPE_API_KEY"),
        description="Your DashScope API key.",
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout for making http request to DashScope API server.",
    )

    def __init__(
        self,
        api_key: str | None = None,
        request_timeout: float | None = DEFAULT_REQUEST_TIMEOUT,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        resolved_api_key = api_key or os.environ.get("DASHSCOPE_API_KEY")
        if not resolved_api_key:
            msg = (
                "DashScope API key is not provided. "
                "Please pass it as an argument or set the 'DASHSCOPE_API_KEY' environment variable."
            )
            raise ValueError(msg)

        super().__init__(
            api_key=resolved_api_key,
            request_timeout=request_timeout,
            **kwargs,
        )

    @override
    def supports_native_structured_output(
        self,
        output_schema: OutputSchema | None,
        tools: List[Tool] | None,
        model_kwargs: Mapping[str, Any] | None,
    ) -> NativeStructuredOutputSupport:
        """Answers from the same two helpers ``chat`` uses to decide its native branch.

        The effective model is the ``model`` parameter, or ``DEFAULT_MODEL`` when it
        is absent.
        """
        if not self._can_apply_native_structured_output(
            output_schema, tools, model_kwargs
        ):
            return NativeStructuredOutputSupport.INFEASIBLE
        if self._model_supports_native_structured_output(
            self._effective_model_for(model_kwargs)
        ):
            return NativeStructuredOutputSupport.NATIVE_RECOMMENDED
        return NativeStructuredOutputSupport.FEASIBLE

    def _model_supports_native_structured_output(
        self, effective_model: str | None
    ) -> bool:
        """Whether DashScope documents structured output for ``effective_model``.

        See the module-level allowlist for the source of truth and for why names are
        matched exactly. A name outside it reports ``False`` so it degrades to the
        prompt-engineering fallback rather than failing at the provider.

        Args:
            effective_model: The model the request will be issued against, may be
                ``None``.

        Returns:
            ``True`` if a schema can be applied natively for ``effective_model``.
        """
        return effective_model in _NATIVE_STRUCTURED_OUTPUT_MODELS

    def _effective_model_for(
        self, model_kwargs: Mapping[str, Any] | None
    ) -> str | None:
        """The ``model`` parameter, falling back to ``DEFAULT_MODEL`` when absent.

        ``chat`` resolves the model it calls the same way, so reading the parameter
        alone would answer ``None`` for every call that names no model, and report the
        default model incapable without ever asking about it.

        The fallback stands in for an absent parameter only, matching the request: a
        parameter that is present but empty is passed through, so the helper and the
        request agree on that input too.
        """
        if model_kwargs is None:
            return DEFAULT_MODEL
        return model_kwargs.get("model", DEFAULT_MODEL)

    def _can_apply_native_structured_output(
        self,
        output_schema: OutputSchema | None,
        tools: List[Tool] | None,
        model_kwargs: Mapping[str, Any] | None,
    ) -> bool:
        """Whether a request built from these inputs would carry a native
        ``response_format``, leaving the effective model's capability out of the answer.

        Only a ``BaseModel`` subclass has a native translation here; a ``RowTypeInfo``
        wrapped in ``OutputSchema``, or no schema at all, has none and keeps the
        prompt-engineering fallback.

        A caller-supplied ``response_format`` is deliberately not a condition: the
        branch answers that conflict by raising rather than by skipping, so a caller
        that asked here first can still be met with an exception. Reporting the
        conflict infeasible instead would turn a documented error into a silently
        unconstrained request. Rendering raises likewise, on a ``BaseModel`` that
        carries no JSON Schema, so a ``True`` is not a promise the call succeeds.

        Neither the tools nor the parameters are read: this connection sends a native
        schema alongside bound tools, and the one parameter that would bear on the
        answer is the model, which is the capability question this excludes.

        Parameters
        ----------
        output_schema : OutputSchema | None
            The schema the request would carry, or ``None`` for an unconstrained
            request.
        tools : List[Tool] | None
            Not read; bound tools do not stop this connection sending a native schema.
        model_kwargs : Mapping[str, Any] | None
            Not read.

        Returns:
        -------
        bool
            ``True`` if ``output_schema`` wraps a ``BaseModel`` subclass.
        """
        return _native_output_model(output_schema) is not None

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        """Process a sequence of messages, and return a response.

        Parameters
        ----------
        messages : Sequence[ChatMessage]
            Input message sequence
        tools : Optional[List]
            List of tools that can be called by the model
        output_schema : OutputSchema | None
            The schema the response should conform to, or ``None`` for an
            unconstrained response. Native structured output is applied only for a
            ``BaseModel`` schema on a model the provider documents as capable; a
            ``RowTypeInfo`` schema or an incapable model keeps the prompt-engineering
            fallback. A ``response_format`` supplied alongside a schema is refused
            rather than resolved.
        **kwargs : Any
            Additional parameters passed to the model service (e.g., temperature,
            max_tokens, etc.)

        Returns:
        -------
        ChatResult
            Model response message.
        """
        # Media blocks are not sent yet; fail rather than drop them (#1059).
        UnsupportedContentBlockError.reject_media("DashScope", messages)
        # Snapshotted before the pops below, so the feasibility check is asked with
        # the parameters as they arrived rather than with a mapping this path has
        # already stripped. No term of today's answer reads them; the shape is what
        # keeps a term added later from answering about a request other than the one
        # being built.
        raw_kwargs = dict(kwargs)

        dashscope_messages = self.__convert_to_dashscope_messages(messages)

        dashscope_tools: List[Dict[str, Any]] | None = (
            [to_dashscope_tool(tool.metadata) for tool in tools] if tools else None
        )

        extract_reasoning = bool(kwargs.pop("extract_reasoning", False))

        req_api_key = kwargs.pop("api_key", self.api_key)

        model_name = kwargs.pop("model", DEFAULT_MODEL)

        # The predicate reads model_name rather than kwargs.get("model"): the key was
        # popped on the line above, so a kwargs lookup would yield None on every call
        # and report every model incapable.
        #
        # The feasibility half is asked rather than restated, so a caller asking the
        # same question gets the answer this branch acts on. A payload with no native
        # translation is reported infeasible there, so it never reaches the conflict
        # test below and cannot raise over a response_format this branch was never
        # going to write.
        if self._can_apply_native_structured_output(
            output_schema, tools, raw_kwargs
        ) and self._model_supports_native_structured_output(model_name):
            # Tested before the schema is rendered, because a caller who supplies both
            # a schema and a response_format has a conflict to resolve whatever the
            # schema turns out to render to, and reporting a render failure instead
            # would describe the wrong problem. The name is read off the model class,
            # so this needs no rendered document.
            native_model = _native_output_model(output_schema)
            if "response_format" in kwargs:
                msg = (
                    f"The {native_model.__name__} output schema is sent as "
                    f"response_format to model '{model_name}', so response_format "
                    f"must not also be passed as a kwarg. Remove that value, or "
                    f"omit output_schema to set response_format directly."
                )
                raise ValueError(msg)
            kwargs["response_format"] = _native_response_format(output_schema)

        response = Generation.call(
            model=model_name,
            messages=dashscope_messages,
            tools=dashscope_tools,
            result_format="message",
            timeout=self.request_timeout,
            api_key=req_api_key,
            **kwargs,
        )

        if response.status_code != 200:
            msg = f"DashScope call failed: {response.message}"
            raise RuntimeError(msg)

        choice = response.output["choices"][0]
        message = choice["message"]
        blocks = []
        if extract_reasoning and message.get("reasoning_content"):
            blocks.append(ReasoningBlock(text=message["reasoning_content"]))
        if message.get("content"):
            blocks.append(TextBlock(text=message["content"]))
        for call in message.get("tool_calls") or []:
            function = call["function"]
            arguments = function.get("arguments") or {}
            if isinstance(arguments, str):
                arguments = json.loads(arguments)
            blocks.append(
                ToolCallBlock(
                    call_id=call.get("id") or str(uuid.uuid4()),
                    name=function["name"],
                    input=arguments,
                )
            )
        return ChatResult(
            message=ChatMessage.assistant(blocks),
            model=str(model_name) if model_name is not None else None,
            usage=TokenUsage(
                prompt_tokens=response.usage.input_tokens,
                completion_tokens=response.usage.output_tokens,
            )
            if response.usage
            else None,
            finish_reason=choice.get("finish_reason"),
        )

    @staticmethod
    def __convert_to_dashscope_messages(
        messages: Sequence[ChatMessage],
    ) -> List[Dict[str, Any]]:
        result = []
        for message in messages:
            blocks = (
                message.blocks[0].blocks
                if message.role == MessageRole.TOOL
                else message.blocks
            )
            for block in blocks:
                if not isinstance(block, TextBlock | ReasoningBlock | ToolCallBlock):
                    provider = "DashScope"
                    raise UnsupportedContentBlockError.for_block(
                        provider, block, "unsupported message content"
                    )
            native = {
                "role": message.role.value,
                "content": "".join(b.text for b in blocks if isinstance(b, TextBlock)),
            }
            if message.tool_calls:
                native["tool_calls"] = [
                    {
                        "id": call.call_id,
                        "type": "function",
                        "function": {
                            "name": call.name,
                            "arguments": json.dumps(call.input),
                        },
                    }
                    for call in message.tool_calls
                ]
            if message.role == MessageRole.TOOL:
                native["tool_call_id"] = message.blocks[0].call_id
            result.append(native)
        return result


class DashScopeChatModelSetup(BaseChatModelSetup):
    """DashScope chat model setup which manages chat configuration and will internally
    call DashScope chat model connection to do chat.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseChatModelSetup)
    model : str
        Model name to use. Defaults to ``DEFAULT_MODEL`` when omitted via
        ``__init__``. (Inherited from BaseChatModelSetup)
    prompt : Optional[Union[Prompt, str]
        Prompt template or string for the model. (Inherited from BaseChatModelSetup)
    tools : Optional[List[str]]
        List of available tools to use in the chat. (Inherited from BaseChatModelSetup)
    temperature : float
        The temperature to use for sampling.
    additional_kwargs : Dict[str, Any]
        Additional model parameters for the DashScope API.
    extract_reasoning : bool
        If True, extracts reasoning content from the response and stores it
        in additional_kwargs.
    """

    temperature: float = Field(
        default=0.7,
        description="The temperature to use for sampling.",
        ge=0.0,
        le=2.0,
    )
    additional_kwargs: Dict[str, Any] = Field(
        default_factory=dict,
        description="Additional model parameters for the DashScope API.",
    )
    extract_reasoning: bool = Field(
        default=False,
        description="If True, extracts reasoning content from the response and stores it.",
    )

    def __init__(
        self,
        connection: str,
        model: str = DEFAULT_MODEL,
        temperature: float = 0.7,
        additional_kwargs: Dict[str, Any] | None = None,
        extract_reasoning: bool | None = False,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        if additional_kwargs is None:
            additional_kwargs = {}
        super().__init__(
            connection=connection,
            model=model,
            temperature=temperature,
            additional_kwargs=additional_kwargs,
            extract_reasoning=extract_reasoning,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return DashScope model configuration."""
        base_kwargs = {
            "model": self.model,
            "temperature": self.temperature,
            "extract_reasoning": self.extract_reasoning,
        }
        return {
            **base_kwargs,
            **self.additional_kwargs,
        }
