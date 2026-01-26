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
from typing import Any, Dict, List, Sequence

from openai import NOT_GIVEN, AzureOpenAI
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
)


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
        description="Supported Azure OpenAI endpoints. Example: https://{your-resource-name}.openai.azure.com"
    )
    timeout: float = Field(
        default=60.0,
        description="The number of seconds to wait for an API call before it times out.",
        ge=0,
    )
    max_retries: int = Field(
        default=3,
        description="The number of times to retry the API call upon failure.",
        ge=0,
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
                timeout=self.timeout,
                max_retries=self.max_retries,
            )
        return self._client

    def chat(self, messages: Sequence[ChatMessage], tools: List[Tool] | None = None, **kwargs: Any,) -> ChatMessage:
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

        # Extract model (azure_deployment) and model_of_azure_deployment from kwargs
        azure_deployment = kwargs.pop("model", "")
        if not azure_deployment:
            msg = "model is required for Azure OpenAI API calls"
            raise ValueError(msg)
        model_of_azure_deployment = kwargs.pop("model_of_azure_deployment", None)

        response = self.client.chat.completions.create(
            # Azure OpenAI APIs use Azure deployment name as the model parameter
            model=azure_deployment,
            messages=convert_to_openai_messages(messages),
            tools=tool_specs or NOT_GIVEN,
            **kwargs,
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
    prompt : Optional[Union[Prompt, str]
        Prompt template or string for the model. (Inherited from BaseChatModelSetup)
    tools : Optional[List[str]]
        List of available tools to use in the chat. (Inherited from BaseChatModelSetup)
    model : str
        Name of OpenAI model deployment on Azure.
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

    model: str = Field(
        description="Name of OpenAI model deployment on Azure.",
    )
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

        all_kwargs = {**base_kwargs, **self.additional_kwargs}
        return all_kwargs
