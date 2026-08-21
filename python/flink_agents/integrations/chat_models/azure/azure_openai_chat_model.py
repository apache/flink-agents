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
import re
from typing import Any, Dict, List, Sequence

from openai import NOT_GIVEN, AzureOpenAI

# Private SDK module (leading underscore): the openai client itself uses this helper to
# build the strict json_schema for response_format, and there is no public re-export. It
# has existed at this path since the structured-output support in openai 1.66.3 (the
# pinned minimum). A future openai bump that moves it will fail loudly on import here.
from openai.lib._pydantic import to_strict_json_schema
from pydantic import BaseModel, Field, PrivateAttr
from typing_extensions import override

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.tools.tool import Tool
from flink_agents.integrations.chat_models.chat_model_utils import to_openai_tool
from flink_agents.integrations.chat_models.openai.openai_utils import (
    convert_from_openai_message,
    convert_to_openai_messages,
)

logger = logging.getLogger(__name__)
MAX_OPENAI_TIMEOUT_SECONDS = 2_147_483.647
MAX_OPENAI_RETRIES = 2_147_483_647

_RESERVED_KWARG_KEYS = frozenset(
    {"model", "model_of_azure_deployment", "temperature", "max_tokens", "logprobs"}
)

# Models that both have documented json_schema strict Structured Outputs support and are
# served on the Chat Completions API, which is the API this connection calls. The set is
# that intersection, taken from two sources:
# https://learn.microsoft.com/en-us/azure/ai-foundry/openai/how-to/structured-outputs
# lists the models supporting Structured Outputs on any API, and
# https://learn.microsoft.com/en-us/azure/ai-foundry/openai/how-to/reasoning carries the
# per-model feature table whose "Chat Completions API" row excludes the models Azure
# serves only on the Responses API.
#
# Matching is exact, never by prefix: Azure exposes a deployment's model name and model
# version as separate properties, so a name carries no version to discriminate on. The
# documented list includes gpt-4o only at versions 2024-08-06 and 2024-11-20 while
# version 2024-05-13 is unsupported, so a bare "gpt-4o" is ambiguous and is deliberately
# absent from the set below. An unrecognized name reports not-capable and degrades to
# the prompt fallback rather than failing at the provider.
_NATIVE_STRUCTURED_OUTPUT_MODELS = frozenset(
    {
        "gpt-5.1",
        "gpt-5.1-chat",
        "gpt-5",
        "gpt-5-mini",
        "gpt-5-nano",
        "o3-mini",
        "o1",
        "gpt-4o-mini",
        "gpt-4.1",
        "gpt-4.1-nano",
        "gpt-4.1-mini",
        "o4-mini",
        "o3",
    }
)

# Date prefix of 2024-08-01-preview, the earliest api-version Azure documents as
# supporting structured outputs.
_MIN_STRUCTURED_OUTPUT_API_VERSION = "2024-08-01"

# Leading zero-padded YYYY-MM-DD date of the api-version form Azure documents, which
# is a date optionally carrying a suffix such as -preview. The ASCII flag restricts \d
# to 0-9, so a value written with other Unicode decimal digits is not read as a date.
_API_VERSION_DATE_PREFIX = re.compile(r"^\d{4}-\d{2}-\d{2}", re.ASCII)


def _native_response_format(output_schema: Any) -> Dict[str, Any] | None:
    """Build the ``response_format`` for a native structured-output request.

    Returns ``None`` (leaving behavior unchanged) unless the schema is a ``BaseModel``
    subclass. A ``RowTypeInfo`` schema is skipped so it keeps the prompt-engineering
    fallback.
    """
    if output_schema is None:
        return None
    model = (
        output_schema.output_schema if isinstance(output_schema, OutputSchema) else None
    )
    if not (isinstance(model, type) and issubclass(model, BaseModel)):
        return None
    return {
        "type": "json_schema",
        "json_schema": {
            "name": model.__name__,
            "schema": to_strict_json_schema(model),
            "strict": True,
        },
    }


class AzureOpenAIChatModelConnection(BaseChatModelConnection):
    """The connection to the Azure OpenAI LLM.

    Attributes:
    ----------
    api_key : str
        The Azure OpenAI API key.
    api_version : str
        Azure OpenAI REST API version to use.
        See more: https://learn.microsoft.com/en-us/azure/ai-services/openai/reference#rest-api-versioning
    azure_endpoint : str
        Supported Azure OpenAI endpoints. Example: https://{your-resource-name}.openai.azure.com
    timeout : float
        The number of seconds to wait for an API call before it times out.
    max_retries : int
        The number of times to retry the API call upon failure.
    """

    api_key: str = Field(default=None, description="The Azure OpenAI API key.")
    api_version: str = Field(
        default=None,
        description="Azure OpenAI REST API version to use.",
    )
    azure_endpoint: str = Field(
        default=None,
        description="Supported Azure OpenAI endpoints. Example: https://{your-resource-name}.openai.azure.com",
    )
    timeout: float = Field(
        default=60.0,
        description="The number of seconds to wait for an API call before it times out. Set to 0 to disable timeouts.",
        ge=0,
        le=MAX_OPENAI_TIMEOUT_SECONDS,
        allow_inf_nan=False,
    )
    max_retries: int = Field(
        default=3,
        description="The number of times to retry the API call upon failure.",
        ge=0,
        le=MAX_OPENAI_RETRIES,
    )

    def __init__(
        self,
        *,
        api_key: str | None = None,
        api_version: str | None = None,
        azure_endpoint: str | None = None,
        timeout: float = 60.0,
        max_retries: int = 3,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        super().__init__(
            api_key=api_key,
            api_version=api_version,
            azure_endpoint=azure_endpoint,
            timeout=timeout,
            max_retries=max_retries,
            **kwargs,
        )

    _client: AzureOpenAI | None = PrivateAttr(default=None)

    @property
    def client(self) -> AzureOpenAI:
        """Get Azure OpenAI client."""
        if self._client is None:
            self._client = AzureOpenAI(
                azure_endpoint=self.azure_endpoint,
                api_key=self.api_key,
                api_version=self.api_version,
                # Match Java's Duration.ZERO: None avoids an immediate timeout in httpx.
                timeout=None if self.timeout == 0 else self.timeout,
                max_retries=self.max_retries,
            )
        return self._client

    @override
    def supports_native_structured_output(self, effective_model: str | None) -> bool:
        """Whether Azure documents json_schema strict support for ``effective_model``.

        ``effective_model`` is the model backing an Azure deployment, not the deployment
        name. See the module-level allowlist for the source of truth and for why the
        match is exact. An unrecognized model reports ``False`` so it degrades to the
        prompt-engineering fallback rather than failing at the provider.

        Reads no instance state, so it stays answerable on an instance that was never
        initialized, where any field access would raise.
        """
        if not effective_model:
            return False
        return effective_model in _NATIVE_STRUCTURED_OUTPUT_MODELS

    def _api_version_supports_structured_output(self) -> bool:
        """Whether the configured api-version reaches the structured-output floor.

        Azure documents ``2024-08-01-preview`` as the first api-version supporting
        structured outputs, and whether an older version rejects ``response_format`` or
        silently ignores it is not documented. The request therefore never carries
        ``response_format`` below the floor, which is safe under either behavior.

        Only the documented api-version form is classified, a zero-padded
        ``YYYY-MM-DD`` date optionally suffixed ``-preview``; over that form comparing
        the leading date lexicographically is exact. A value of any other shape,
        including the GA ``v1`` literal, reports ``False`` and keeps the prompt
        fallback. That is the accurate answer for ``v1``: ``AzureOpenAI`` reaches the
        service through the deployment-scoped path
        ``/openai/deployments/{deployment}/chat/completions`` with the api-version
        carried as a query parameter, so the ``v1`` literal is sent as
        ``?api-version=v1`` rather than selecting Azure's ``/openai/v1`` endpoint.
        """
        if not self.api_version:
            return False
        if not _API_VERSION_DATE_PREFIX.match(self.api_version):
            return False
        return self.api_version[:10] >= _MIN_STRUCTURED_OUTPUT_API_VERSION

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
            The schema the response should conform to, or ``None`` for an unconstrained
            response. Native structured output is applied only for a ``BaseModel``
            schema, on a deployment whose backing model the provider documents as
            capable, and with an api-version that supports it; a ``RowTypeInfo`` schema,
            an incapable model, or an older api-version keeps the prompt-engineering
            fallback. Where native output applies, a caller-supplied
            ``response_format`` conflicts with it and raises ``ValueError``.
        **kwargs : Any
            Additional parameters passed to the model service (e.g., temperature,
            max_tokens, etc.)

        Returns:
        -------
        ChatMessage
            Model response message
        """
        tool_specs = None
        if tools is not None:
            tool_specs = [to_openai_tool(metadata=tool.metadata) for tool in tools]

        # Extract model (azure_deployment) and model_of_azure_deployment from kwargs
        azure_deployment = kwargs.pop("model", "")
        if not azure_deployment:
            msg = "model is required for Azure OpenAI API calls"
            raise ValueError(msg)
        model_of_azure_deployment = kwargs.pop("model_of_azure_deployment", None)
        additional_kwargs = kwargs.pop("additional_kwargs", None) or {}

        collisions = _RESERVED_KWARG_KEYS & additional_kwargs.keys()
        if collisions:
            msg = (
                f"additional_kwargs must not contain reserved typed fields: "
                f"{sorted(collisions)}. Set these via the corresponding "
                f"Setup field instead."
            )
            raise ValueError(msg)

        # Capability belongs to the model backing the deployment, so it is the input to
        # the check. The deployment name is chosen by the user and carries none.
        #
        # TODO(#912): the requested strategy is not visible here, so this check cannot
        # tell an explicit NATIVE request apart from one that merely resolved to native.
        # A caller asking for NATIVE therefore gets an unconstrained response instead of
        # an error whenever this branch is skipped, which on Azure also happens when the
        # api-version is below the floor or when model_of_azure_deployment is unset and
        # capability cannot be resolved at all. Once strategy resolution is wired up,
        # NATIVE must either bypass this check or fail explicitly.
        if (
            output_schema is not None
            and self.supports_native_structured_output(model_of_azure_deployment)
            and self._api_version_supports_structured_output()
        ):
            response_format = _native_response_format(output_schema)
            if response_format is not None:
                caller_response_format = (
                    "response_format" in kwargs
                    or "response_format" in additional_kwargs
                )
                if caller_response_format:
                    msg = (
                        f"The {response_format['json_schema']['name']} output schema "
                        f"is sent as response_format on deployment "
                        f"'{azure_deployment}', so response_format must not also be "
                        f"passed as a kwarg or in additional_kwargs. Remove that "
                        f"value, or omit output_schema to set response_format "
                        f"directly."
                    )
                    raise ValueError(msg)
                kwargs["response_format"] = response_format

        response = self.client.chat.completions.create(
            # Azure OpenAI APIs use Azure deployment name as the model parameter
            model=azure_deployment,
            messages=convert_to_openai_messages(messages),
            tools=tool_specs or NOT_GIVEN,
            **kwargs,
            **additional_kwargs,
        )

        extra_args = {}
        # Record token metrics only if model_of_azure_deployment is provided
        if model_of_azure_deployment and response.usage:
            extra_args["model_name"] = model_of_azure_deployment
            extra_args["promptTokens"] = response.usage.prompt_tokens
            extra_args["completionTokens"] = response.usage.completion_tokens

        message = response.choices[0].message

        return convert_from_openai_message(message, extra_args)


class AzureOpenAIChatModelSetup(BaseChatModelSetup):
    """The settings for the Azure OpenAI LLM.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseChatModelSetup)
    model : str
        Name of OpenAI model deployment on Azure. (Inherited from BaseChatModelSetup)
    prompt : Optional[Union[Prompt, str]
        Prompt template or string for the model. (Inherited from BaseChatModelSetup)
    tools : Optional[List[str]]
        List of available tools to use in the chat. (Inherited from BaseChatModelSetup)
    model_of_azure_deployment : Optional[str]
        The underlying model name of the Azure deployment (e.g., 'gpt-4').
        Used for token counting and cost calculation.
    temperature : Optional[float]
        What sampling temperature to use, between 0 and 2. Higher values like 0.8
        will make the output more random, while lower values like 0.2 will make it
        more focused and deterministic.
        Not supported by reasoning models (e.g. gpt-5, o-series).
    max_tokens : Optional[int]
        The maximum number of tokens that can be generated in the chat completion.
        The total length of input tokens and generated tokens is limited by the
        model's context length.
    logprobs : Optional[bool]
        Whether to return log probabilities of the output tokens or not. If true,
        returns the log probabilities of each output token returned in the content
        of message.
    additional_kwargs : Dict[str, Any]
        Additional kwargs for the Azure OpenAI API.
    """

    model_of_azure_deployment: str | None = Field(
        default=None,
        description="The underlying model name of the Azure deployment (e.g., 'gpt-4', "
        "'gpt-35-turbo'). Used for token counting and cost calculation. "
        "Required for token metrics tracking.",
    )
    temperature: float | None = Field(
        default=None,
        description="What sampling temperature to use, between 0 and 2. Higher values like 0.8 will make the output "
        "more random, while lower values like 0.2 will make it more focused and deterministic. "
        "Not supported by reasoning models (e.g. gpt-5, o-series).",
        ge=0.0,
        le=2.0,
    )
    max_tokens: int | None = Field(
        default=None,
        description="The maximum number of tokens that can be generated in the chat completion. The total length of "
        "input tokens and generated tokens is limited by the model's context length.",
        gt=0,
    )
    logprobs: bool | None = Field(
        description="Whether to return log probabilities of the output tokens or not. If true, returns the log "
        "probabilities of each output token returned in the content of message.",
        default=False,
    )
    additional_kwargs: Dict[str, Any] = Field(
        default_factory=dict, description="Additional kwargs for the Azure OpenAI API."
    )

    def __init__(
        self,
        *,
        model: str,
        model_of_azure_deployment: str | None = None,
        temperature: float | None = None,
        max_tokens: int | None = None,
        logprobs: bool | None = False,
        additional_kwargs: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        additional_kwargs = additional_kwargs or {}
        if not model_of_azure_deployment:
            logger.warning(
                "model_of_azure_deployment is not set; token usage metrics will "
                "not be recorded for this Azure OpenAI deployment '%s'.",
                model,
            )
        super().__init__(
            model=model,
            model_of_azure_deployment=model_of_azure_deployment,
            temperature=temperature,
            max_tokens=max_tokens,
            logprobs=logprobs,
            additional_kwargs=additional_kwargs,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return chat model settings."""
        base_kwargs = {
            "model": self.model,
            "model_of_azure_deployment": self.model_of_azure_deployment,
            "logprobs": self.logprobs,
        }
        if self.temperature is not None:
            base_kwargs["temperature"] = self.temperature
        if self.max_tokens is not None:
            base_kwargs["max_tokens"] = self.max_tokens
        if self.additional_kwargs:
            base_kwargs["additional_kwargs"] = self.additional_kwargs
        return base_kwargs
