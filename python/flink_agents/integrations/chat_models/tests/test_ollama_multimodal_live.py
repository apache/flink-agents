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
"""A real image through the Ollama connection.

Checks that the server accepts what test_ollama_multimodal.py pins. Skipped
unless OLLAMA_VISION_MODEL names a vision model (for example qwen2.5vl:3b);
the model is pulled if the server lacks it. Mirrors the Java
OllamaMultimodalLiveTest.
"""

import base64
import os
import struct
import zlib

import pytest

from flink_agents.api.chat_message import ChatMessage, ImageBlock, TextBlock
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
)

pytestmark = pytest.mark.integration

VISION_MODEL = os.environ.get("OLLAMA_VISION_MODEL")


def _client_ready() -> bool:
    if not VISION_MODEL:
        return False
    from flink_agents.e2e_tests.test_utils import pull_model

    return pull_model(VISION_MODEL) is not None


def _red_square_png() -> str:
    """A 16x16 red PNG."""
    rows = b"".join(b"\x00" + b"\xff\x00\x00" * 16 for _ in range(16))

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )
    return base64.b64encode(png).decode()


@pytest.mark.skipif(not _client_ready(), reason="OLLAMA_VISION_MODEL is not set")
def test_image() -> None:
    """A base64 image is accepted."""
    connection = OllamaChatModelConnection(request_timeout=120.0)
    response = connection.chat(
        [
            ChatMessage.user(
                [
                    TextBlock(text="What color is this image? Answer in one word."),
                    ImageBlock.from_base64("image/png", _red_square_png()),
                ]
            )
        ],
        model=VISION_MODEL,
    )
    assert response.text.strip()
