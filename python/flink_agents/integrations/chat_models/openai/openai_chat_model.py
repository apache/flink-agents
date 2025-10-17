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
from typing import Any, Dict, List, Literal, Sequence

import httpx
from openai import NOT_GIVEN, OpenAI
from pydantic import Field, PrivateAttr

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
    resolve_openai_credentials,
)

DEFAULT_OPENAI_MODEL = "gpt-3.5-turbo"


class OpenAIChatModelConnection(BaseChatModelConnection):
    """The connection to the OpenAI LLM.

    Attributes:
    ----------
    api_key : str
        The OpenAI API key.
    api_base_url : str
        The base URL for OpenAI API.
    max_retries : int
        The maximum number of API retries.
    timeout : float
        How long to wait, in seconds, for an API call before failing.
    default_headers : Optional[Dict[str, str]]
        The default headers for API requests.
    reuse_client : bool
        Whether to reuse the OpenAI client between requests.
    """

    api_key: str = Field(default=None, description="The OpenAI API key.")
    api_base_url: str = Field(description="The base URL for OpenAI API.")
    max_retries: int = Field(
        default=3,
        description="The maximum number of API retries.",
        ge=0,
    )
    timeout: float = Field(
        default=60.0,
        description="The timeout, in seconds, for API requests.",
        ge=0,
    )
    default_headers: Dict[str, str] | None = Field(
        default=None, description="The default headers for API requests."
    )
    reuse_client: bool = Field(
        default=True,
        description=(
            "Reuse the OpenAI client between requests. When doing anything with large "
            "volumes of async API calls, setting this to false can improve stability."
        ),
    )

    _client: OpenAI | None = PrivateAttr(default=None)
    _http_client: httpx.Client | None = PrivateAttr()

    def __init__(
        self,
        *,
        api_key: str | None = None,
        api_base_url: str | None = None,
        max_retries: int = 3,
        timeout: float = 60.0,
        reuse_client: bool = True,
        http_client: httpx.Client | None = None,
        async_http_client: httpx.AsyncClient | None = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        api_key, api_base_url = resolve_openai_credentials(
            api_key=api_key,
            api_base_url=api_base_url,
        )
        super().__init__(
            api_key=api_key,
            api_base_url=api_base_url,
            max_retries=max_retries,
            timeout=timeout,
            reuse_client=reuse_client,
            **kwargs,
        )

        self._http_client = http_client
        self._async_http_client = async_http_client

    @property
    def client(self) -> OpenAI:
        """Get OpenAI client."""
        config = self.__get_client_kwargs()

        if not self.reuse_client:
            return OpenAI(**config)

        if self._client is None:
            self._client = OpenAI(**config)
        return self._client

    def __get_client_kwargs(self) -> Dict[str, Any]:
        return {
            "api_key": self.api_key,
            "base_url": self.api_base_url,
            "max_retries": self.max_retries,
            "timeout": self.timeout,
            "default_headers": self.default_headers,
            "http_client": self._http_client,
        }

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Direct communication with model service for chat conversation.

        Parameters
        ----------
        messages : Sequence[ChatMessage]
            Input message sequence
        tools : Optional[List]
            List of tools that can be called by the model
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
            strict = kwargs.get("strict", False)
            for tool_spec in tool_specs:
                if tool_spec["type"] == "function":
                    tool_spec["function"]["strict"] = strict
                    tool_spec["function"]["parameters"]["additionalProperties"] = False

        response = self.client.chat.completions.create(
            messages=convert_to_openai_messages(messages),
            tools=tool_specs or NOT_GIVEN,
            **kwargs,
        )

        response = response.choices[0].message

        return convert_from_openai_message(response)


DEFAULT_TEMPERATURE = 0.1


class OpenAIChatModelSetup(BaseChatModelSetup):
    """The settings for the OpenAI LLM.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseChatModelSetup)
    prompt : Optional[Union[Prompt, str]
        Prompt template or string for the model. (Inherited from BaseChatModelSetup)
    tools : Optional[List[str]]
        List of available tools to use in the chat. (Inherited from BaseChatModelSetup)
    model : str
        The OpenAI model to use.
    temperature : float
        The temperature to use during generation.
    max_tokens : Optional[int]
        The maximum number of tokens to generate.
    logprobs : Optional[bool]
        Whether to return logprobs per token.
    top_logprobs : int
        The number of top token log probs to return.
    additional_kwargs : Dict[str, Any]
        Additional kwargs for the OpenAI API.
    strict : bool
        Whether to use strict mode for invoking tools/using schemas.
    reasoning_effort : Optional[Literal["low", "medium", "high"]]
        The effort to use for reasoning models.
    """

    model: str = Field(
        default=DEFAULT_OPENAI_MODEL, description="The OpenAI model to use."
    )
    temperature: float = Field(
        default=DEFAULT_TEMPERATURE,
        description="The temperature to use during generation.",
        ge=0.0,
        le=2.0,
    )
    max_tokens: int | None = Field(
        description="The maximum number of tokens to generate.",
        gt=0,
    )
    logprobs: bool | None = Field(
        description="Whether to return logprobs per token.",
        default=None,
    )
    top_logprobs: int = Field(
        description="The number of top token log probs to return.",
        default=0,
        ge=0,
        le=20,
    )
    additional_kwargs: Dict[str, Any] = Field(
        default_factory=dict, description="Additional kwargs for the OpenAI API."
    )
    strict: bool = Field(
        default=False,
        description="Whether to use strict mode for invoking tools/using schemas.",
    )
    reasoning_effort: Literal["low", "medium", "high"] | None = Field(
        default=None,
        description="The effort to use for reasoning models.",
    )

    def __init__(
        self,
        *,
        model: str = DEFAULT_OPENAI_MODEL,
        temperature: float = DEFAULT_TEMPERATURE,
        max_tokens: int | None = None,
        additional_kwargs: Dict[str, Any] | None = None,
        strict: bool = False,
        reasoning_effort: Literal["low", "medium", "high"] | None = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        additional_kwargs = additional_kwargs or {}
        super().__init__(
            model=model,
            temperature=temperature,
            max_tokens=max_tokens,
            additional_kwargs=additional_kwargs,
            strict=strict,
            reasoning_effort=reasoning_effort,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return chat model settings."""
        base_kwargs = {"model": self.model, "temperature": self.temperature}
        if self.max_tokens is not None:
            base_kwargs["max_tokens"] = self.max_tokens
        if self.logprobs is not None and self.logprobs is True:
            base_kwargs["logprobs"] = self.logprobs
            base_kwargs["top_logprobs"] = self.top_logprobs

        all_kwargs = {**base_kwargs, **self.additional_kwargs}
        return all_kwargs
