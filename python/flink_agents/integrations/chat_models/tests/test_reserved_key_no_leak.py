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
from unittest.mock import MagicMock, PropertyMock, patch

from pydantic import BaseModel

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import STRUCTURED_OUTPUT_SCHEMA_KEY
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
)


class Person(BaseModel):
    """A representative BaseModel output schema."""

    name: str


def test_non_native_connection_does_not_forward_reserved_key() -> None:
    """A non-native connection given a schema must not pass the reserved key to its SDK."""
    conn = OllamaChatModelConnection(name="ollama")

    mock_client = MagicMock()
    mock_response = MagicMock()
    mock_response.message.role = "assistant"
    mock_response.message.content = "ok"
    mock_response.message.tool_calls = None
    mock_response.prompt_eval_count = None
    mock_response.eval_count = None
    mock_client.chat.return_value = mock_response

    with patch.object(
        OllamaChatModelConnection, "client", new_callable=PropertyMock
    ) as client_prop:
        client_prop.return_value = mock_client
        conn.chat(
            [ChatMessage(role=MessageRole.USER, content="hi")],
            model="qwen3:1.7b",
            **{STRUCTURED_OUTPUT_SCHEMA_KEY: OutputSchema(output_schema=Person)},
        )

    call_kwargs = mock_client.chat.call_args.kwargs
    # The reserved key must not appear anywhere the SDK receives it.
    assert STRUCTURED_OUTPUT_SCHEMA_KEY not in call_kwargs
    assert STRUCTURED_OUTPUT_SCHEMA_KEY not in call_kwargs.get("options", {})
