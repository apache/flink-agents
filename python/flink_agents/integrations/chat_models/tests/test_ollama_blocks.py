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
from unittest.mock import MagicMock

import pytest
from ollama import ChatResponse as OllamaResponse

from flink_agents.api.chat_message import (
    ChatMessage,
    ImageBlock,
    ReasoningBlock,
    UnsupportedContentBlockError,
)
from flink_agents.api.tools.tool_response import ToolResponse
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
)


def connection(payload):
    conn = OllamaChatModelConnection()
    client = MagicMock()
    client.chat.return_value = OllamaResponse(**payload)
    conn._OllamaChatModelConnection__client = client
    return conn


def test_tool_roundtrip_does_not_replay_reasoning_or_execution_data():
    conn = connection(
        {
            "message": {
                "role": "assistant",
                "content": "calling",
                "thinking": "private",
                "tool_calls": [{"function": {"name": "add", "arguments": {"x": 1}}}],
            },
            "done_reason": "stop",
            "prompt_eval_count": 0,
        }
    )
    response = conn.chat(
        [ChatMessage.user("hi")], model="local", extract_reasoning=True
    )
    call = response.tool_calls[0]
    assert call.call_id
    assert call.input == {"x": 1}
    assert response.finish_reason == "stop"
    assert response.metadata == {}
    assert response.usage.prompt_tokens == 0
    assert response.usage.completion_tokens is None
    assert isinstance(response.message.blocks[0], ReasoningBlock)
    assert response.text == "calling"
    response.message.blocks[0].metadata["custom"] = "retained in history"
    result = ToolResponse.success({"answer": 2}).to_result_block(call.call_id)
    conn.chat(
        [response.message, ChatMessage.tool(result)],
        model="local",
    )
    wire = conn.client.chat.call_args.kwargs["messages"]
    assert wire[0].content == "calling"
    assert wire[0].thinking is None
    assert wire[0].tool_calls[0].function.arguments == {"x": 1}
    assert wire[1].content == '{"answer": 2}'


@pytest.mark.parametrize(
    "raw", [None, "", "stop", "tool_calls", "length", "content_filter", "unexpected"]
)
@pytest.mark.parametrize("with_tools", [False, True])
def test_finish_reason_preserves_unknown_and_missing(raw, with_tools):
    message = {"role": "assistant", "content": "answer"}
    if with_tools:
        message["tool_calls"] = [{"function": {"name": "add", "arguments": {"x": 1}}}]
    conn = connection({"message": message, "done_reason": raw})
    response = conn.chat([ChatMessage.user("hi")], model="local")
    assert response.finish_reason == raw
    assert response.metadata == {}
    assert len(response.tool_calls) == int(with_tools)


def test_unsupported_media_fails_before_sending():
    conn = connection({"message": {"role": "assistant", "content": "answer"}})
    with pytest.raises(UnsupportedContentBlockError):
        conn.chat(
            [
                ChatMessage.user(
                    [ImageBlock.from_url("image/png", "https://example.com/image.png")]
                )
            ],
            model="local",
        )
    conn.client.chat.assert_not_called()
