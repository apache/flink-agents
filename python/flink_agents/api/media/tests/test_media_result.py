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
#  limitations under the License.
#################################################################################
import pytest
from pydantic import ValidationError

from flink_agents.api.chat_message import (
    AudioBlock,
    ImageBlock,
    TextBlock,
    VideoBlock,
)
from flink_agents.api.media import MediaResult


def test_media_result_json_roundtrip_keeps_block_types() -> None:
    result = MediaResult(
        content=[
            TextBlock(text="hello"),
            VideoBlock.from_url("video/mp4", "https://example.test/out.mp4"),
            ImageBlock.from_url("image/png", "https://example.test/out.png"),
            AudioBlock.from_url("audio/wav", "https://example.test/out.wav"),
        ],
        expires_at_ms=1_700_086_400_000,
        usage={"total_tokens": 12, "ratio": 0.5, "unit": "seconds"},
        request_id="req-1",
        metadata={"task_metrics": {"total": 1}},
    )

    restored = MediaResult.model_validate_json(result.model_dump_json())

    assert restored == result
    assert isinstance(restored.content[0], TextBlock)
    assert isinstance(restored.content[1], VideoBlock)
    assert isinstance(restored.content[2], ImageBlock)
    assert isinstance(restored.content[3], AudioBlock)


def test_typed_media_result_rejects_mismatched_block_type() -> None:
    with pytest.raises(ValueError):
        MediaResult[AudioBlock](content=[TextBlock(text="not audio")])


def test_media_result_is_immutable_and_rejects_extra_fields() -> None:
    result = MediaResult[TextBlock](content=[TextBlock(text="hello")])
    with pytest.raises(ValidationError):
        result.request_id = "req-2"  # type: ignore[misc]
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        MediaResult[TextBlock](content=[], extra_field="x")  # type: ignore[call-arg]
