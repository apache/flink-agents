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

from ollama import Client, Message
from pydantic import Field

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.tools.tool import Tool
from flink_agents.integrations.chat_models.chat_model_utils import to_openai_tool

DEFAULT_CONTEXT_WINDOW = 2048
DEFAULT_REQUEST_TIMEOUT = 30.0


class OllamaChatModelConnection(BaseChatModelConnection):
    """Ollama ChatModelServer which manage the connection to the Ollama server.

    Visit https://ollama.com/ to download and install Ollama.

    Run `ollama serve` to start a server.

    Run `ollama pull <name>` to download a model to run.

    Attributes:
    ----------
    base_url : str
        Base url the model is hosted under.
    request_timeout : float
        The timeout for making http request to Ollama API server.
    """

    base_url: str = Field(
        default="http://localhost:11434",
        description="Base url the model is hosted under.",
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout for making http request to Ollama API server.",
    )

    __client: Client = None

    def __init__(
        self,
        base_url: str = "http://localhost:11434",
        request_timeout: float | None = DEFAULT_REQUEST_TIMEOUT,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        super().__init__(
            base_url=base_url,
            request_timeout=request_timeout,
            **kwargs,
        )

    @property
    def client(self) -> Client:
        """Return ollama client."""
        if self.__client is None:
            self.__client = Client(host=self.base_url, timeout=self.request_timeout)
        return self.__client

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Process a sequence of messages, and return a response."""
        ollama_messages = self.__convert_to_ollama_messages(messages)

        # Convert tool format
        ollama_tools = None
        if tools is not None:
            ollama_tools = [to_openai_tool(metadata=tool.metadata) for tool in tools]

        model_name = kwargs.pop("model")
        response = self.client.chat(
            model=model_name,
            messages=ollama_messages,
            stream=False,
            tools=ollama_tools,
            options=kwargs,
            keep_alive=kwargs.get("keep_alive", False),
        )

        ollama_tool_calls = response.message.tool_calls
        if ollama_tool_calls is None:
            ollama_tool_calls = []
        tool_calls = []
        for ollama_tool_call in ollama_tool_calls:
            tool_call = {
                "id": uuid.uuid4(),
                "type": "function",
                "function": {
                    "name": ollama_tool_call.function.name,
                    "arguments": ollama_tool_call.function.arguments,
                },
            }
            tool_calls.append(tool_call)

        content = response.message.content
        extra_args = {}

        # Process reasoning if extract_reasoning is enabled
        if kwargs.get("extract_reasoning") and content:
            content, reasoning = self._extract_reasoning(content)
            if reasoning:
                extra_args["reasoning"] = reasoning

        # Record token metrics if model name and usage are available
        if (
            model_name
            and response.prompt_eval_count is not None
            and response.eval_count is not None
        ):
            self._record_token_metrics(
                model_name, response.prompt_eval_count, response.eval_count
            )

        return ChatMessage(
            role=MessageRole(response.message.role),
            content=content,
            tool_calls=tool_calls,
            extra_args=extra_args,
        )

    @staticmethod
    def __convert_to_ollama_messages(messages: Sequence[ChatMessage]) -> List[Message]:
        ollama_messages = []
        for message in messages:
            ollama_message = Message(role=message.role.value, content=message.content)
            if len(message.tool_calls) > 0:
                ollama_tool_calls = []
                for tool_call in message.tool_calls:
                    name = tool_call["function"]["name"]
                    arguments = tool_call["function"]["arguments"]
                    ollama_tool_call = Message.ToolCall(
                        function=Message.ToolCall.Function(
                            name=name, arguments=arguments
                        )
                    )
                    ollama_tool_calls.append(ollama_tool_call)
                ollama_message.tool_calls = ollama_tool_calls
            ollama_messages.append(ollama_message)
        return ollama_messages


class OllamaChatModelSetup(BaseChatModelSetup):
    """Ollama chat model setup which manages chat configuration and will internally
    call ollama chat model connection to do chat.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseChatModelSetup)
    prompt : Optional[Union[Prompt, str]
        Prompt template or string for the model. (Inherited from BaseChatModelSetup)
    tools : Optional[List[str]]
        List of available tools to use in the chat. (Inherited from BaseChatModelSetup)
    model : str
        Model name to use.
    temperature : float
        The temperature to use for sampling.
    num_ctx : int
        The maximum number of context tokens for the model.
    additional_kwargs : Dict[str, Any]
        Additional model parameters for the Ollama API.
    keep_alive : Optional[Union[float, str]]
        Controls how long the model will stay loaded into memory following the
        request(default: 5m)
    extract_reasoning : bool
        If True, extracts content within <think></think> tags from the response and
        stores it in additional_kwargs.
    """

    model: str = Field(description="Model name to use.")

    temperature: float = Field(
        default=0.75,
        description="The temperature to use for sampling.",
        ge=0.0,
        le=1.0,
    )

    num_ctx: int = Field(
        default=DEFAULT_CONTEXT_WINDOW,
        description="The maximum number of context tokens for the model.",
        gt=0,
    )
    additional_kwargs: Dict[str, Any] = Field(
        default_factory=dict,
        description="Additional model parameters for the Ollama API.",
    )
    keep_alive: float | str | None = Field(
        default="5m",
        description="Controls how long the model will stay loaded into memory following the "
        "request(default: 5m)",
    )
    extract_reasoning: bool = Field(
        default=True,
        description="If True, extracts content within <think></think> tags from the response and "
        "stores it in additional_kwargs.",
    )

    def __init__(
        self,
        connection: str,
        model: str,
        temperature: float = 0.75,
        num_ctx: int = DEFAULT_CONTEXT_WINDOW,
        request_timeout: float | None = DEFAULT_REQUEST_TIMEOUT,
        additional_kwargs: Dict[str, Any] | None = None,
        keep_alive: float | str | None = None,
        extract_reasoning: bool | None = True,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        if additional_kwargs is None:
            additional_kwargs = {}
        super().__init__(
            connection=connection,
            model=model,
            temperature=temperature,
            num_ctx=num_ctx,
            request_timeout=request_timeout,
            additional_kwargs=additional_kwargs,
            keep_alive=keep_alive,
            extract_reasoning=extract_reasoning,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return ollama model configuration."""
        base_kwargs = {
            "model": self.model,
            "temperature": self.temperature,
            "num_ctx": self.num_ctx,
            "keep_alive": self.keep_alive,
            "extract_reasoning": self.extract_reasoning,
        }
        return {
            **base_kwargs,
            **self.additional_kwargs,
        }
