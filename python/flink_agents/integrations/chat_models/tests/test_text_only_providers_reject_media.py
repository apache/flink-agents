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
"""Check that watsonx rejects media before sending a text request."""

import pytest

from flink_agents.api.chat_message import (
    ChatMessage,
    ImageBlock,
    TextBlock,
    ToolResultBlock,
    UnsupportedContentBlockError,
)
from flink_agents.integrations.chat_models.watsonx.watsonx_chat_model import (
    WatsonxChatModelConnection,
)


@pytest.mark.parametrize("tool_result", [False, True])
def test_media_blocks_fail_explicitly(tool_result: bool) -> None:
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

    connection = WatsonxChatModelConnection(
        url="https://us-south.ml.cloud.ibm.com",
        api_key="fake-key",
        project_id="fake-project",
    )
    with pytest.raises(UnsupportedContentBlockError) as error:
        connection.chat([message], model="m")

    assert str(error.value) == (
        "IBM watsonx.ai cannot send an image block (image/png, base64 source): "
        "this integration sends text only."
    )
