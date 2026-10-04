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
"""Real multimodal requests through the OpenAI Chat Completions connection.

Checks that the provider accepts the content parts test_openai_multimodal.py
pins. Skipped unless TEST_API_KEY is set; TEST_API_BASE_URL,
TEST_MULTIMODAL_MODEL (default gpt-4o-mini) and TEST_AUDIO_MODEL (default
gpt-4o-audio-preview) override the endpoint and models. Mirrors the Java
OpenAIChatCompletionsMultimodalLiveTest.
"""

import base64
import io
import os
import struct
import wave
import zlib

import pytest

from flink_agents.api.chat_message import (
    AudioBlock,
    Base64Source,
    ChatMessage,
    ContentBlock,
    DocumentBlock,
    ImageBlock,
    TextBlock,
)
from flink_agents.integrations.chat_models.openai.openai_chat_model import (
    OpenAIChatModelConnection,
)

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        not os.environ.get("TEST_API_KEY"), reason="TEST_API_KEY is not set"
    ),
]

MULTIMODAL_MODEL = os.environ.get("TEST_MULTIMODAL_MODEL") or "gpt-4o-mini"
AUDIO_MODEL = os.environ.get("TEST_AUDIO_MODEL") or "gpt-4o-audio-preview"


def _chat(model: str, *blocks: ContentBlock) -> str:
    connection = OpenAIChatModelConnection(
        api_key=os.environ["TEST_API_KEY"],
        api_base_url=os.environ.get("TEST_API_BASE_URL"),
    )
    return connection.chat([ChatMessage.user(list(blocks))], model=model).text


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


def _silent_wav() -> str:
    """Half a second of 16 kHz mono 16-bit PCM silence."""
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16_000)
        wav.writeframes(b"\x00\x00" * 8_000)
    return base64.b64encode(buffer.getvalue()).decode()


def _hello_pdf() -> str:
    """A one-page PDF reading "Hello PDF", with a valid cross-reference table."""
    stream = "BT /F1 24 Tf 20 40 Td (Hello PDF) Tj ET"
    objects = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 100] /Contents 4 0 R"
        " /Resources << /Font << /F1 5 0 R >> >> >>",
        f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    pdf = "%PDF-1.4\n"
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf += f"{number} 0 obj\n{body}\nendobj\n"
    xref = len(pdf)
    pdf += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n"
    pdf += "".join(f"{offset:010d} 00000 n \n" for offset in offsets)
    pdf += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
    pdf += f"startxref\n{xref}\n%%EOF\n"
    return base64.b64encode(pdf.encode("ascii")).decode()


def test_image() -> None:
    """A base64 image is accepted."""
    answer = _chat(
        MULTIMODAL_MODEL,
        TextBlock(text="What color is this image? Answer in one word."),
        ImageBlock.from_base64("image/png", _red_square_png()),
    )
    assert answer.strip()


def test_audio() -> None:
    """WAV audio is accepted."""
    answer = _chat(
        AUDIO_MODEL,
        TextBlock(text="Describe this audio clip in one sentence."),
        AudioBlock.from_base64("audio/wav", _silent_wav()),
    )
    assert answer.strip()


def test_named_pdf() -> None:
    """A named PDF is accepted."""
    answer = _chat(
        MULTIMODAL_MODEL,
        TextBlock(text="What text does this PDF contain?"),
        DocumentBlock(
            media_type="application/pdf",
            source=Base64Source(data=_hello_pdf()),
            name="hello.pdf",
        ),
    )
    assert answer.strip()


def test_unnamed_pdf() -> None:
    """A PDF without a name is accepted under the default file name."""
    answer = _chat(
        MULTIMODAL_MODEL,
        TextBlock(text="What text does this PDF contain?"),
        DocumentBlock.from_base64("application/pdf", _hello_pdf()),
    )
    assert answer.strip()
