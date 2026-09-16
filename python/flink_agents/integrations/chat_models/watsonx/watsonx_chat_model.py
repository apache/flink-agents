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
import ast
import contextlib
import json
import logging
import os
import time
import uuid
from typing import Any, Dict, List, Mapping, Sequence

import httpx
from ibm_watsonx_ai import APIClient, Credentials
from ibm_watsonx_ai.foundation_models import ModelInference
from ibm_watsonx_ai.wml_client_error import ApiRequestFailure
from pydantic import BaseModel, Field, PrivateAttr
from typing_extensions import override

from flink_agents.api.agents.types import OutputSchema, render_output_schema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.tools.tool import Tool
from flink_agents.integrations.chat_models.chat_model_utils import to_openai_tool

logger = logging.getLogger(__name__)

DEFAULT_MODEL = "ibm/granite-4-h-small"
DEFAULT_REQUEST_TIMEOUT = 120.0
DEFAULT_MAX_RETRIES = 3
RETRYABLE_STATUS_CODES = frozenset({408, 429, 500, 502, 503, 504})
REQUEST_OWNED_PARAMS = frozenset(
    {"model_id", "messages", "tools", "project_id", "space_id"}
)
RESERVED_ADDITIONAL_KWARGS = frozenset(
    {
        "model",
        "temperature",
        "max_tokens",
        "extract_reasoning",
        "tool_choice",
        "tool_choice_option",
    }
) | REQUEST_OWNED_PARAMS


def _normalize(value: str | None) -> str | None:
    if value is None or not value.strip():
        return None
    return value.strip()


def _retry_delay_seconds(attempt: int, response: httpx.Response | None) -> float:
    """Return capped exponential backoff, honoring a numeric Retry-After header."""
    backoff = min(2**attempt, 10)
    if response is not None:
        retry_after = response.headers.get("Retry-After")
        if retry_after is not None:
            with contextlib.suppress(ValueError):
                return max(backoff, min(float(retry_after.strip()), 30))
    return backoff


def convert_to_watsonx_messages(
    messages: Sequence[ChatMessage],
) -> List[Dict[str, Any]]:
    """Convert framework messages to the watsonx.ai chat format."""
    watsonx_messages: List[Dict[str, Any]] = []
    for message in messages:
        role = message.role

        if role == MessageRole.ASSISTANT:
            assistant_message: Dict[str, Any] = {"role": "assistant"}
            if message.content:
                assistant_message["content"] = message.content
            if message.tool_calls:
                assistant_message["tool_calls"] = [
                    _convert_to_watsonx_tool_call(tool_call)
                    for tool_call in message.tool_calls
                ]
            watsonx_messages.append(assistant_message)
        elif role == MessageRole.TOOL:
            tool_call_id = message.extra_args.get("external_id")
            if not tool_call_id or not isinstance(tool_call_id, str):
                msg = "Tool message must have 'external_id' as a string in extra_args"
                raise ValueError(msg)
            watsonx_messages.append(
                {
                    "role": "tool",
                    "content": message.content,
                    "tool_call_id": tool_call_id,
                }
            )
        else:
            watsonx_messages.append({"role": role.value, "content": message.content})
    return watsonx_messages


def _parse_tool_arguments(args: Any) -> Dict[str, Any]:
    """Parse model-emitted tool arguments, including common malformed variants."""
    if args is None or args == "":
        return {}
    raw = args
    for _ in range(3):
        if not isinstance(args, str):
            break
        try:
            args = json.loads(args)
        except ValueError:
            with contextlib.suppress(Exception):
                literal = ast.literal_eval(args)
                if isinstance(literal, dict):
                    args = literal
            break
    if not isinstance(args, dict):
        msg = (
            "Failed to parse tool call arguments returned by watsonx.ai "
            f"as a JSON object: {raw!r}"
        )
        raise TypeError(msg)
    return args


def _convert_to_watsonx_tool_call(tool_call: Dict[str, Any]) -> Dict[str, Any]:
    """Convert a framework tool call to watsonx.ai format."""
    watsonx_tool_call_id = tool_call.get("original_id")
    if watsonx_tool_call_id is None:
        tool_call_id = tool_call.get("id")
        if tool_call_id is None:
            msg = "Tool call must have either 'original_id' or 'id' field"
            raise ValueError(msg)
        watsonx_tool_call_id = str(tool_call_id)

    arguments = tool_call["function"]["arguments"]
    return {
        "id": watsonx_tool_call_id,
        "type": "function",
        "function": {
            "name": tool_call["function"]["name"],
            "arguments": json.dumps(arguments)
            if isinstance(arguments, dict)
            else arguments,
        },
    }


def _native_output_model(
    output_schema: OutputSchema | None,
) -> type[BaseModel] | None:
    """The model a schema translates natively to, or ``None`` where none applies.

    ``None`` covers both no schema at all and a ``RowTypeInfo``, which has no native
    translation here and keeps the prompt-engineering fallback.

    Separate from the render below because the caller-conflict check needs to know
    whether a schema will be sent, and under what name, before anything is rendered.
    """
    model = (
        output_schema.output_schema if isinstance(output_schema, OutputSchema) else None
    )
    if not (isinstance(model, type) and issubclass(model, BaseModel)):
        return None
    return model


def _native_response_format(
    output_schema: OutputSchema | None,
) -> Dict[str, Any] | None:
    """Build the ``response_format`` for a native structured-output request.

    Returns ``None``, leaving the request unchanged, unless the schema is a
    ``BaseModel`` subclass.

    Rendering goes through the shared helper, which raises ``TypeError`` naming the
    schema class and the remedy when the model has no JSON Schema.
    """
    model = _native_output_model(output_schema)
    if model is None:
        return None
    return {
        "type": "json_schema",
        "json_schema": {
            "name": model.__name__,
            "schema": render_output_schema(model, lambda m: m.model_json_schema()),
            "strict": True,
        },
    }


class WatsonxChatModelConnection(BaseChatModelConnection):
    """Connection to the IBM watsonx.ai chat API."""

    url: str = Field(description="The watsonx.ai service endpoint.")
    api_key: str | None = Field(default=None, description="The IBM Cloud API key.")
    token: str | None = Field(
        default=None, description="A bearer token, as an alternative to api_key."
    )
    project_id: str | None = Field(
        default=None, description="The watsonx.ai project id."
    )
    space_id: str | None = Field(
        default=None, description="The watsonx.ai deployment space id."
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout, in seconds, for chat requests to watsonx.ai.",
        gt=0,
        allow_inf_nan=False,
    )
    max_retries: int = Field(
        default=DEFAULT_MAX_RETRIES,
        description="Maximum number of retries for transient failures.",
        ge=0,
    )

    _client: APIClient | None = PrivateAttr(default=None)
    _http_client: httpx.Client | None = PrivateAttr(default=None)
    _models: Dict[str, ModelInference] = PrivateAttr(default_factory=dict)

    def __init__(
        self,
        *,
        url: str | None = None,
        api_key: str | None = None,
        token: str | None = None,
        project_id: str | None = None,
        space_id: str | None = None,
        request_timeout: float = DEFAULT_REQUEST_TIMEOUT,
        max_retries: int = DEFAULT_MAX_RETRIES,
        **kwargs: Any,
    ) -> None:
        """Initialize the connection."""
        resolved_url = _normalize(url) or _normalize(os.environ.get("WATSONX_URL"))
        resolved_api_key = _normalize(api_key) or _normalize(
            os.environ.get("WATSONX_API_KEY")
        )
        resolved_token = _normalize(token) or _normalize(
            os.environ.get("WATSONX_TOKEN")
        )
        resolved_project_id = _normalize(project_id) or _normalize(
            os.environ.get("WATSONX_PROJECT_ID")
        )
        resolved_space_id = _normalize(space_id) or _normalize(
            os.environ.get("WATSONX_SPACE_ID")
        )

        if not resolved_url:
            msg = (
                "watsonx.ai url is not provided. Please pass it as an argument "
                "or set the 'WATSONX_URL' environment variable."
            )
            raise ValueError(msg)
        if not resolved_api_key and not resolved_token:
            msg = (
                "watsonx.ai credentials are not provided. Please pass 'api_key' "
                "or 'token' as an argument, or set the 'WATSONX_API_KEY' or "
                "'WATSONX_TOKEN' environment variable."
            )
            raise ValueError(msg)
        if resolved_api_key and resolved_token:
            msg = (
                "watsonx.ai api_key and token cannot both be provided. Please configure "
                "exactly one credential source."
            )
            raise ValueError(msg)
        if not resolved_project_id and not resolved_space_id:
            msg = (
                "watsonx.ai project or space is not provided. Please pass "
                "'project_id' or 'space_id' as an argument, or set the "
                "'WATSONX_PROJECT_ID' or 'WATSONX_SPACE_ID' environment variable."
            )
            raise ValueError(msg)
        if resolved_project_id and resolved_space_id:
            msg = (
                "watsonx.ai project and space cannot both be provided. Please configure "
                "exactly one of 'project_id' or 'space_id'."
            )
            raise ValueError(msg)

        super().__init__(
            url=resolved_url,
            api_key=resolved_api_key,
            token=resolved_token,
            project_id=resolved_project_id,
            space_id=resolved_space_id,
            request_timeout=request_timeout,
            max_retries=max_retries,
            **kwargs,
        )

    @property
    def client(self) -> APIClient:
        """Return the lazily initialized API client."""
        if self._client is None:
            credential_kwargs: Dict[str, Any] = {"url": self.url}
            if self.api_key:
                credential_kwargs["api_key"] = self.api_key
            if self.token:
                credential_kwargs["token"] = self.token
            self._http_client = httpx.Client(timeout=self.request_timeout)
            self._client = APIClient(
                credentials=Credentials(**credential_kwargs),
                project_id=self.project_id,
                space_id=self.space_id,
                httpx_client=self._http_client,
            )
        return self._client

    @override
    def close(self) -> None:
        """Close the underlying HTTP client."""
        self._models = {}
        self._client = None
        if self._http_client is not None:
            with contextlib.suppress(Exception):
                self._http_client.close()
            self._http_client = None

    def _get_model(self, model: str) -> ModelInference:
        if model not in self._models:
            self._models[model] = ModelInference(
                model_id=model,
                api_client=self.client,
                project_id=self.project_id,
                space_id=self.space_id,
                max_retries=0,
            )
        return self._models[model]

    def _chat_with_retry(self, model_name: str, **chat_kwargs: Any) -> Dict[str, Any]:
        """Call chat with retries for selected HTTP statuses and transport failures.

        Retries use capped exponential backoff and honor a numeric ``Retry-After``
        response header.
        """
        attempt = 0
        while True:
            try:
                return self._get_model(model_name).chat(**chat_kwargs)
            except (ApiRequestFailure, httpx.TransportError) as e:  # noqa: PERF203
                response = getattr(e, "response", None)
                status = getattr(response, "status_code", None)
                retryable = isinstance(e, httpx.TransportError) or (
                    status in RETRYABLE_STATUS_CODES
                )
                if attempt >= self.max_retries or not retryable:
                    raise
                delay = _retry_delay_seconds(attempt, response)
                logger.warning(
                    "watsonx.ai chat request for model %s failed with %s; "
                    "retry %d/%d in %ds",
                    model_name,
                    status if status is not None else type(e).__name__,
                    attempt + 1,
                    self.max_retries,
                    delay,
                )
                time.sleep(delay)
                attempt += 1

    @override
    def supports_native_structured_output(self, effective_model: str | None) -> bool:
        """Whether watsonx.ai can constrain generation to a schema for the given model.

        Always ``True``, and deliberately independent of the argument. The constraint
        is applied by the serving runtime, through vLLM guided decoding, rather than by
        a per-model capability. IBM states that chat API support requires the vLLM
        runtime, on its pages about inferencing *custom* foundation models; it does not
        state in so many words that its own provided models are served the same way.
        That last step rests instead on the chat request body exposing vLLM's
        guided-decoding parameters verbatim.

        There is no allowlist because IBM publishes no per-model structured-output
        signal to key on: the foundation-model comparison table has columns for chat
        and tool interaction and none for structured output, and the model-specs API
        exposes no such flag. A list written here would encode a gate nobody documents
        and would report not-capable for models that do work.

        This diverges from the base contract, which describes capability as
        model-dependent and requires an unrecognized model to report ``False``. That
        rule guards against failing at the provider for a model whose capability is
        unknown; here capability is a property of the endpoint rather than of the
        model, so there is no unknown to guard against.

        Reads no instance state, so capability stays answerable independently of how
        the connection was configured.
        """
        return True

    @override
    def effective_model_for(self, model_kwargs: Mapping[str, Any] | None) -> str | None:
        """The ``model`` parameter, falling back to ``DEFAULT_MODEL`` when absent.

        ``chat`` resolves the model it calls the same way, so reading the parameter
        alone would answer ``None`` where the request in fact goes to the default
        model.

        The fallback stands in for an absent parameter only, matching the request: a
        parameter that is present but empty is passed through, so the hook and the
        request agree on that input too.
        """
        if model_kwargs is None:
            return DEFAULT_MODEL
        return model_kwargs.get("model", DEFAULT_MODEL)

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Process a sequence of messages, and return a response.

        A ``BaseModel`` ``output_schema`` is sent as the ``response_format`` request
        parameter. Any other schema form, notably a ``RowTypeInfo``, has no native
        translation here and keeps the prompt-engineering fallback. Where the schema
        is sent natively, a caller-supplied ``response_format`` carrying a value
        conflicts with it and raises ``ValueError``. Declaring the parameter keeps a
        caller-supplied schema out of ``**kwargs``, which is forwarded to the
        provider SDK.

        When the response carries a finish reason, it is available verbatim as
        ``extra_args["finish_reason"]``; the key is absent when the provider
        reports none.
        """
        model_name = kwargs.pop("model", DEFAULT_MODEL)
        extract_reasoning = bool(kwargs.pop("extract_reasoning", False))
        tool_choice = kwargs.pop("tool_choice", None)
        tool_choice_option = kwargs.pop("tool_choice_option", None)
        additional_kwargs = kwargs.pop("additional_kwargs", None) or {}
        collisions = RESERVED_ADDITIONAL_KWARGS & additional_kwargs.keys()
        if collisions:
            msg = (
                "additional_kwargs must not contain reserved typed fields: "
                f"{sorted(collisions)}. Set these via the corresponding Setup field instead."
            )
            raise ValueError(msg)

        request_params = {**additional_kwargs, **kwargs}
        collisions = REQUEST_OWNED_PARAMS & request_params.keys()
        if collisions:
            msg = (
                "request parameters must not contain framework-owned fields: "
                f"{sorted(collisions)}."
            )
            raise ValueError(msg)

        # Native structured output applies only for a BaseModel schema; any other
        # schema form, such as a RowTypeInfo wrapped in OutputSchema, keeps the
        # prompt-engineering fallback.
        #
        # TODO(#912): the requested strategy is not visible here, so a request that
        # explicitly asked for NATIVE cannot be told apart from one that merely
        # resolved to it. Capability is unconditional on this connection, so the schema
        # form is the only way through to an unconstrained response: a caller who asked
        # for NATIVE and passed a schema this branch cannot translate gets one
        # silently. Once strategy resolution is wired up, NATIVE must either bypass
        # this re-check or fail explicitly.
        if output_schema is not None and self.supports_native_structured_output(
            model_name
        ):
            native_model = _native_output_model(output_schema)
            # A caller reaches the same request field through either channel, and both
            # have already merged into request_params. Only the branch that sends a
            # derived schema may reject the caller's value; every path that skips it
            # leaves that value untouched. A None is not a conflict, because the
            # assignment below replaces it before the request is built. Tested before
            # the schema is rendered, so a caller who supplied both is told about the
            # conflict rather than about a render failure; the name is read off the
            # model class and needs no rendered document.
            if (
                native_model is not None
                and request_params.get("response_format") is not None
            ):
                msg = (
                    f"The {native_model.__name__} output schema is sent as"
                    " response_format, so response_format must not also be passed as"
                    " a kwarg or in additional_kwargs. Remove that value, or omit"
                    " output_schema to set response_format directly."
                )
                raise ValueError(msg)
            response_format = _native_response_format(output_schema)
            if response_format is not None:
                # The SDK merges params into the request body at its root, so the
                # derived schema travels as the request field it is rather than as a
                # sampling option.
                request_params["response_format"] = response_format

        tool_specs: List[Dict[str, Any]] | None = (
            [to_openai_tool(metadata=tool.metadata) for tool in tools]
            if tools
            else None
        )

        response = self._chat_with_retry(
            model_name,
            messages=convert_to_watsonx_messages(messages),
            tools=tool_specs,
            tool_choice=tool_choice,
            tool_choice_option=tool_choice_option,
            params=request_params or None,
        )

        extra_args: Dict[str, Any] = {}

        usage = response.get("usage")
        if model_name and usage:
            extra_args["model_name"] = model_name
            extra_args["promptTokens"] = usage.get("prompt_tokens", 0)
            extra_args["completionTokens"] = usage.get("completion_tokens", 0)

        choice: Dict[str, Any] = response["choices"][0]
        finish_reason = choice.get("finish_reason")
        if finish_reason not in (None, "stop", "tool_calls"):
            logger.warning(
                "watsonx.ai chat for model %s finished with reason '%s'; "
                "the response may be truncated or incomplete",
                model_name,
                finish_reason,
            )
        if finish_reason is not None:
            extra_args["finish_reason"] = finish_reason

        response_message: Dict[str, Any] = choice["message"]

        tool_calls: List[Dict[str, Any]] = []
        for tc in response_message.get("tool_calls") or []:
            fn = tc.get("function", {}) or {}
            args = _parse_tool_arguments(fn.get("arguments"))
            tool_calls.append(
                {
                    "id": uuid.uuid4(),
                    "type": tc.get("type", "function"),
                    "function": {
                        "name": fn.get("name"),
                        "arguments": args,
                    },
                    "original_id": tc.get("id"),
                }
            )

        content = response_message.get("content") or ""

        if extract_reasoning and content:
            content, reasoning = self._extract_reasoning(content)
            if reasoning:
                extra_args["reasoning"] = reasoning

        return ChatMessage(
            role=MessageRole(response_message.get("role", "assistant")),
            content=content,
            tool_calls=tool_calls,
            extra_args=extra_args,
        )


DEFAULT_TEMPERATURE = 0.1


class WatsonxChatModelSetup(BaseChatModelSetup):
    """Chat model configuration for IBM watsonx.ai."""

    temperature: float = Field(
        default=DEFAULT_TEMPERATURE,
        description="The temperature to use for sampling.",
        ge=0.0,
        le=2.0,
    )
    max_tokens: int | None = Field(
        default=None,
        description="The maximum number of tokens to generate.",
        gt=0,
    )
    additional_kwargs: Dict[str, Any] = Field(
        default_factory=dict,
        description="Additional chat parameters for the watsonx.ai API.",
    )
    extract_reasoning: bool = Field(
        default=False,
        description="If True, extracts reasoning content from the response and stores it.",
    )

    def __init__(
        self,
        *,
        model: str = DEFAULT_MODEL,
        temperature: float = DEFAULT_TEMPERATURE,
        max_tokens: int | None = None,
        additional_kwargs: Dict[str, Any] | None = None,
        extract_reasoning: bool = False,
        **kwargs: Any,
    ) -> None:
        """Initialize the model configuration."""
        if additional_kwargs is None:
            additional_kwargs = {}
        super().__init__(
            model=model,
            temperature=temperature,
            max_tokens=max_tokens,
            additional_kwargs=additional_kwargs,
            extract_reasoning=extract_reasoning,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return watsonx.ai model configuration."""
        base_kwargs: Dict[str, Any] = {
            "model": self.model,
            "temperature": self.temperature,
            "extract_reasoning": self.extract_reasoning,
        }
        if self.max_tokens is not None:
            base_kwargs["max_tokens"] = self.max_tokens
        if self.additional_kwargs:
            base_kwargs["additional_kwargs"] = self.additional_kwargs
        return base_kwargs
