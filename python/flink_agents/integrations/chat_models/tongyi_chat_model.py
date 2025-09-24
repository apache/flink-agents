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
from pydantic import Field

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.tools.tool import BaseTool, ToolMetadata

DEFAULT_REQUEST_TIMEOUT = 60.0
DEFAULT_MODEL = "qwen-plus"


def to_dashscope_tool(metadata: ToolMetadata, skip_length_check: bool = False) -> Dict[str, Any]:  # noqa:FBT001
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
    model : str
        Model name to use.
    request_timeout : float
        The timeout for making http request to Tongyi API server.
    """

    api_key: str = Field(
        default_factory=lambda: os.environ.get("DASHSCOPE_API_KEY"),
        description="Your DashScope API key.",
    )
    model: str = Field(default=DEFAULT_MODEL, description="Model name to use.")
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout for making http request to Tongyi API server.",
    )

    def __init__(
        self,
        model: str = DEFAULT_MODEL,
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
            model=model,
            api_key=resolved_api_key,
            request_timeout=request_timeout,
            **kwargs,
        )

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[BaseTool] | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Process a sequence of messages, and return a response."""
        tongyi_messages = self.__convert_to_tongyi_messages(messages)

        tongyi_tools: List[Dict[str, Any]] | None = (
            [to_dashscope_tool(tool.metadata) for tool in tools] if tools else None
        )

        extract_reasoning = bool(kwargs.pop("extract_reasoning", False))

        req_api_key = kwargs.pop("api_key", self.api_key)

        response = Generation.call(
            model=self.model,
            messages=tongyi_messages,
            tools=tongyi_tools,
            result_format="message",
            timeout=self.request_timeout,
            api_key=req_api_key,
            **kwargs,
        )

        if getattr(response, "status_code", 200) != 200:
            msg = f"DashScope call failed: {getattr(response, 'message', 'unknown error')}"
            raise RuntimeError(
                msg
            )

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
                "function": {"name": fn.get("name"),"arguments": args,},
                "additional_kwargs": {"original_tool_call_id": tc.get("id")},
            }
            tool_calls.append(tool_call_dict)

        content = response_message.get("content") or ""
        extra_args: Dict[str, Any] = {}

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
            temperature=temperature,
            additional_kwargs=additional_kwargs,
            extract_reasoning=extract_reasoning,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return Tongyi model configuration."""
        base_kwargs = {
            "temperature": self.temperature,
            "extract_reasoning": self.extract_reasoning,
        }
        return {
            **base_kwargs,
            **self.additional_kwargs,
        }
