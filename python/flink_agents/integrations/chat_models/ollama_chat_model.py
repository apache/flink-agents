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
from typing import Any, Dict, List, Mapping, Optional, Sequence, Union

from ollama import Client, Message
from pydantic import Field

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import BaseChatModel
from flink_agents.api.resource import ResourceType

DEFAULT_CONTEXT_WINDOW = 2048
DEFAULT_REQUEST_TIMEOUT = 30.0


class OllamaChatModel(BaseChatModel):
    """Ollama ChatModel.

    Visit https://ollama.com/ to download and install Ollama.

    Run `ollama serve` to start a server.

    Run `ollama pull <name>` to download a model to run.
    """

    base_url: str = Field(
        default="http://localhost:11434",
        description="Base url the model is hosted under.",
    )
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
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout for making http request to Ollama API server",
    )
    additional_kwargs: Dict[str, Any] = Field(
        default_factory=dict,
        description="Additional model parameters for the Ollama API.",
    )
    keep_alive: Optional[Union[float, str]] = Field(
        default="5m",
        description="controls how long the model will stay loaded into memory following the request(default: 5m)",
    )

    __client: Client = None
    __tools: Sequence[Mapping[str, Any]] = []

    def __init__(
        self,
        model: str,
        base_url: str = "http://localhost:11434",
        temperature: float = 0.75,
        num_ctx: int = DEFAULT_CONTEXT_WINDOW,
        request_timeout: Optional[float] = DEFAULT_REQUEST_TIMEOUT,
        additional_kwargs: Optional[Dict[str, Any]] = None,
        keep_alive: Optional[Union[float, str]] = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        if additional_kwargs is None:
            additional_kwargs = {}
        super().__init__(
            model=model,
            base_url=base_url,
            temperature=temperature,
            num_ctx=num_ctx,
            request_timeout=request_timeout,
            additional_kwargs=additional_kwargs,
            keep_alive=keep_alive,
            **kwargs,
        )
        # bind tools
        if self.tools is not None:
            tools = [
                self.get_resource(tool_name, ResourceType.TOOL)
                for tool_name in self.tools
            ]
            self.__tools = [tool.metadata.to_openai_tool() for tool in tools]
        # bind prompt
        if self.prompt is not None and isinstance(self.prompt, str):
            self.prompt = self.get_resource(self.prompt, ResourceType.PROMPT)

    @property
    def client(self) -> Client:
        """Return ollama client."""
        if self.__client is None:
            self.__client = Client(host=self.base_url, timeout=self.request_timeout)
        return self.__client

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return ollama model configuration."""
        base_kwargs = {
            "temperature": self.temperature,
            "num_ctx": self.num_ctx,
        }
        return {
            **base_kwargs,
            **self.additional_kwargs,
        }

    def chat(self, messages: Sequence[ChatMessage]) -> ChatMessage:
        """Process a sequence of messages, and return a response."""
        if self.prompt is not None:
            input_variable = {}
            for msg in messages:
                input_variable.update(msg.additional_kwargs)
            messages = self.prompt.format_messages(**input_variable)
        ollama_messages = self.__convert_to_ollama_messages(messages)
        response = self.client.chat(
            model=self.model,
            messages=ollama_messages,
            stream=False,
            tools=self.__tools,
            options=self.model_kwargs,
            keep_alive=self.keep_alive,
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
        return ChatMessage(
            role=MessageRole(response.message.role),
            content=response.message.content,
            tool_calls=tool_calls,
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
