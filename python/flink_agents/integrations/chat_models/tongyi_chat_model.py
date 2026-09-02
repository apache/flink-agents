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
import contextlib
import json
import os
import uuid
from typing import Any, Dict, List, Sequence, cast

from dashscope import Generation
from pydantic import BaseModel, Field
from typing_extensions import override

from flink_agents.api.agents.types import OutputSchema, render_output_schema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.tools.tool import Tool, ToolMetadata

DEFAULT_REQUEST_TIMEOUT = 60.0
DEFAULT_MODEL = "qwen-plus"

# Models with documented json_schema support that are also served on the
# text-generation endpoint this connection calls. That intersection is the
# Qwen3.7-Max family: the other four json_schema families (Qwen3.7-Plus,
# Qwen3.7-Flash, Qwen3.8-Flash, Qwen3.8-Max) route to the multimodal endpoint and
# answer Generation.call with "url error".
# json_schema model list and mode semantics:
#   https://help.aliyun.com/zh/model-studio/json-mode
# text- vs multimodal-interface routing:
#   https://help.aliyun.com/zh/model-studio/text-generation
#
# Capability is documented per family, meaning the base name plus the dated
# snapshots behind it, so a name matches the prefix itself or a name continuing it
# after a "-" separator. That expresses the documented unit instead of a snapshot
# census that goes stale, and it keeps out a different family that merely extends
# the prefix, such as qwen3.7-maximum. The one snapshot the rule admits without
# json_schema reaching it, qwen3.7-max-2026-06-08, is multimodal-routed and answers
# this connection with "url error" whether or not a response_format rides along.
#
# A name outside the rule reports not-capable and degrades to the prompt-engineering
# fallback rather than failing at the provider.
_NATIVE_STRUCTURED_OUTPUT_ALIAS_PREFIXES = ("qwen3.7-max",)


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


class TongyiChatModelConnection(BaseChatModelConnection):
    """Tongyi ChatModelConnection which manages the connection to the Tongyi API server.

    Attributes:
    ----------
    api_key : str
        Your DashScope API key.
    request_timeout : float
        The timeout for making http request to Tongyi API server.
    """

    api_key: str = Field(
        default_factory=lambda: os.environ.get("DASHSCOPE_API_KEY"),
        description="Your DashScope API key.",
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout for making http request to Tongyi API server.",
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
    def supports_native_structured_output(self, effective_model: str | None) -> bool:
        """Whether DashScope documents structured output for ``effective_model``.

        See the module-level allowlist for the source of truth and for why capability
        is matched by family prefix. A name outside it reports ``False`` so it
        degrades to the prompt-engineering fallback rather than failing at the
        provider.

        Args:
            effective_model: The model the request will be issued against, may be
                ``None``.

        Returns:
            ``True`` if a schema can be applied natively for ``effective_model``.
        """
        if not effective_model:
            return False
        return any(
            effective_model == prefix or effective_model.startswith(prefix + "-")
            for prefix in _NATIVE_STRUCTURED_OUTPUT_ALIAS_PREFIXES
        )

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
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
        ChatMessage
            Model response message.
        """
        tongyi_messages = self.__convert_to_tongyi_messages(messages)

        tongyi_tools: List[Dict[str, Any]] | None = (
            [to_dashscope_tool(tool.metadata) for tool in tools] if tools else None
        )

        extract_reasoning = bool(kwargs.pop("extract_reasoning", False))

        req_api_key = kwargs.pop("api_key", self.api_key)

        model_name = kwargs.pop("model", DEFAULT_MODEL)

        # The predicate reads model_name rather than kwargs.get("model"): the key was
        # popped on the line above, so a kwargs lookup would yield None on every call
        # and report every model incapable.
        #
        # TODO(#912): the requested strategy is not visible here, so this check
        # cannot tell an explicit NATIVE request apart from one that merely
        # resolved to native. A caller asking for NATIVE on a model this predicate
        # rejects therefore gets an unconstrained response instead of an error.
        # Once strategy resolution is wired up, NATIVE must either bypass this
        # capability check or fail explicitly.
        if output_schema is not None and self.supports_native_structured_output(
            model_name
        ):
            # Resolved before the conflict test, so a payload with no native
            # translation does not raise over a response_format this branch was
            # never going to write. Tested before the schema is rendered, because a
            # caller who supplies both a schema and a response_format has a conflict
            # to resolve whatever the schema turns out to render to, and reporting a
            # render failure instead would describe the wrong problem. The name is
            # read off the model class, so this needs no rendered document.
            native_model = _native_output_model(output_schema)
            if native_model is not None and "response_format" in kwargs:
                msg = (
                    f"The {native_model.__name__} output schema is sent as "
                    f"response_format to model '{model_name}', so response_format "
                    f"must not also be passed as a kwarg. Remove that value, or "
                    f"omit output_schema to set response_format directly."
                )
                raise ValueError(msg)
            response_format = _native_response_format(output_schema)
            if response_format is not None:
                kwargs["response_format"] = response_format

        response = Generation.call(
            model=model_name,
            messages=tongyi_messages,
            tools=tongyi_tools,
            result_format="message",
            timeout=self.request_timeout,
            api_key=req_api_key,
            **kwargs,
        )

        if response.status_code != 200:
            msg = f"DashScope call failed: {response.message}"
            raise RuntimeError(msg)

        extra_args: Dict[str, Any] = {}

        # Record token metrics if model name and usage are available
        if model_name and response.usage:
            extra_args["model_name"] = model_name
            extra_args["promptTokens"] = response.usage.input_tokens
            extra_args["completionTokens"] = response.usage.output_tokens

        choice = response.output["choices"][0]
        response_message: Dict[str, Any] = choice["message"]

        tool_calls: List[Dict[str, Any]] = []
        for tc in response_message.get("tool_calls", []) or []:
            fn = tc.get("function", {}) or {}
            args = fn.get("arguments")
            if isinstance(args, str):
                with contextlib.suppress(Exception):
                    args = json.loads(args)
            tool_call_dict = {
                "id": uuid.uuid4(),
                "type": "function",
                "function": {
                    "name": fn.get("name"),
                    "arguments": args,
                },
                "additional_kwargs": {"original_tool_call_id": tc.get("id")},
            }
            tool_calls.append(tool_call_dict)

        content = response_message.get("content") or ""

        reasoning_content = response_message.get("reasoning_content") or ""
        if extract_reasoning and reasoning_content:
            extra_args["reasoning"] = reasoning_content

        return ChatMessage(
            role=MessageRole(response_message.get("role", "assistant")),
            content=content,
            tool_calls=tool_calls,
            extra_args=extra_args,
        )

    @staticmethod
    def __convert_to_tongyi_messages(
        messages: Sequence[ChatMessage],
    ) -> List[Dict[str, Any]]:
        tongyi_messages: List[Dict[str, Any]] = []
        for message in messages:
            msg_dict: Dict[str, Any] = {
                "role": message.role.value,
                "content": message.content,
            }

            if message.tool_calls:
                if message.role == MessageRole.ASSISTANT:
                    msg_dict["tool_calls"] = [
                        {
                            "id": tc.get("additional_kwargs", {}).get(
                                "original_tool_call_id", str(tc.get("id", ""))
                            ),
                            "type": "function",
                            "function": {
                                "name": tc["function"]["name"],
                                "arguments": json.dumps(tc["function"]["arguments"]),
                            },
                        }
                        for tc in message.tool_calls
                    ]
                elif message.role == MessageRole.TOOL:
                    tool_call_info = message.tool_calls[0]
                    original_id = tool_call_info.get("additional_kwargs", {}).get(
                        "original_tool_call_id"
                    )
                    if original_id:
                        msg_dict["tool_call_id"] = original_id
                    elif "id" in tool_call_info:
                        msg_dict["tool_call_id"] = str(tool_call_info["id"])

            tongyi_messages.append(msg_dict)
        return cast("List[Dict[str, Any]]", tongyi_messages)


class TongyiChatModelSetup(BaseChatModelSetup):
    """Tongyi chat model setup which manages chat configuration and will internally
    call Tongyi chat model connection to do chat.

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
        Additional model parameters for the Tongyi API.
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
        description="Additional model parameters for the Tongyi API.",
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
        """Return Tongyi model configuration."""
        base_kwargs = {
            "model": self.model,
            "temperature": self.temperature,
            "extract_reasoning": self.extract_reasoning,
        }
        return {
            **base_kwargs,
            **self.additional_kwargs,
        }
