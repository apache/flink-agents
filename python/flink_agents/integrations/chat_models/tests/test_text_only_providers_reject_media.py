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
"""Providers that send text only fail on media instead of dropping it.

Covers the connections not yet migrated to multimodal requests (#1059). Each
check runs before any client call, so no service is contacted.
"""

from typing import Callable, Dict

import pytest

from flink_agents.api.chat_message import (
    ChatMessage,
    ImageBlock,
    TextBlock,
    ToolResultBlock,
    UnsupportedContentBlockError,
)
from flink_agents.api.chat_models.chat_model import BaseChatModelConnection
from flink_agents.integrations.chat_models.anthropic.anthropic_chat_model import (
    AnthropicChatModelConnection,
)
from flink_agents.integrations.chat_models.dashscope_chat_model import (
    DashScopeChatModelConnection,
)
from flink_agents.integrations.chat_models.watsonx.watsonx_chat_model import (
    WatsonxChatModelConnection,
)

CONNECTIONS: Dict[str, Callable[[], BaseChatModelConnection]] = {
    "Anthropic": lambda: AnthropicChatModelConnection(api_key="fake-key"),
    "DashScope": lambda: DashScopeChatModelConnection(api_key="fake-key"),
    "IBM watsonx.ai": lambda: WatsonxChatModelConnection(
        url="https://us-south.ml.cloud.ibm.com",
        api_key="fake-key",
        project_id="fake-project",
    ),
}


@pytest.mark.parametrize("provider", list(CONNECTIONS))
@pytest.mark.parametrize("tool_result", [False, True])
def test_media_blocks_fail_explicitly(provider: str, tool_result: bool) -> None:
    """A media block raises instead of being dropped from the request."""
    message = ChatMessage.user(
        [
            TextBlock(text="Describe this"),
            ImageBlock.from_base64("image/png", "aGVsbG8="),
        ]
    )

    if tool_result:
        message = ChatMessage.tool(
            ToolResultBlock(call_id="call", blocks=message.blocks)
        )

    with pytest.raises(UnsupportedContentBlockError) as error:
        CONNECTIONS[provider]().chat([message], model="m")

    assert str(error.value) == (
        f"{provider} cannot send an image block (image/png, base64 source): "
        "this integration sends text only."
    )
