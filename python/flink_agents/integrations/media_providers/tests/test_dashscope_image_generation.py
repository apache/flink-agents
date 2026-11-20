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
from collections.abc import Callable
from typing import Any

import httpx
import pytest

from flink_agents.api.chat_message import AudioBlock, ImageBlock
from flink_agents.api.media.media_model import (
    ImageGenerationModel,
    MediaJobStatus,
    MediaResult,
)
from flink_agents.integrations.media_providers.dashscope_media_provider import (
    DashScopeMediaProvider,
)

_IMAGE = "https://example.test/source.png"
_RESULT = "https://oss.example/out.png?Expires=1700000000"


@pytest.fixture
def model_factory(
    provider_factory: Callable[..., DashScopeMediaProvider],
) -> Callable[..., ImageGenerationModel]:
    def create(**overrides: Any) -> ImageGenerationModel:
        values: dict[str, Any] = {"model": "qwen-image-3.0"}
        values.update(overrides)
        provider = provider_factory()
        provider.open()
        return provider.image(**values)

    return create


def test_submit_sends_text_and_images_in_order(
    model_factory: Callable[..., ImageGenerationModel],
    task_responses: Callable[..., list[dict[str, Any]]],
) -> None:
    model = model_factory(model_parameters={"n": 1})
    calls = task_responses(
        model,
        httpx.Response(
            200, json={"output": {"task_id": "job-1", "task_status": "PENDING"}}
        ),
    )

    job = model.submit(
        "Turn these into a watercolor.",
        images=[
            ImageBlock.from_url("image/png", _IMAGE),
            ImageBlock.from_base64("image/jpeg", "YQ=="),
        ],
        model_parameters={"size": "1024*1024"},
    )

    assert job.job_id == "job-1"
    assert job.task == "image_generation"
    (call,) = calls
    assert call["method"] == "POST"
    assert call["path"] == "/services/aigc/image-generation/generation"
    assert call["headers"] == {"X-DashScope-Async": "enable"}
    assert call["json"] == {
        "model": "qwen-image-3.0",
        "input": {
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"image": _IMAGE},
                        {"image": "data:image/jpeg;base64,YQ=="},
                        {"text": "Turn these into a watercolor."},
                    ],
                }
            ]
        },
        "parameters": {"n": 1, "size": "1024*1024"},
    }


@pytest.mark.parametrize(
    ("prompt", "images", "error"),
    [
        (None, None, TypeError),
        ("", None, ValueError),
        ("Draw a cat.", [AudioBlock.from_url("audio/mpeg", _IMAGE)], TypeError),
        ("Draw a cat.", ImageBlock.from_url("image/png", _IMAGE), TypeError),
    ],
)
def test_submit_rejects_invalid_inputs_before_request(
    model_factory: Callable[..., ImageGenerationModel],
    task_responses: Callable[..., list[dict[str, Any]]],
    prompt: Any,
    images: Any,
    error: type[Exception],
) -> None:
    model = model_factory()
    calls = task_responses(model)

    with pytest.raises(error):
        model.submit(prompt, images=images)

    assert calls == []


@pytest.mark.parametrize(
    ("second_url", "expires_at_ms"),
    [
        ("https://oss.example/b.png?Expires=1699999999", 1_699_999_999_000),
        ("https://oss.example/b.png", None),
        ("https://oss.example/b.png?Expires=unknown", None),
    ],
)
def test_fetch_reads_image_urls(
    model_factory: Callable[..., ImageGenerationModel],
    task_responses: Callable[..., list[dict[str, Any]]],
    second_url: str,
    expires_at_ms: int | None,
) -> None:
    model = model_factory()
    task_responses(
        model,
        httpx.Response(200, json={"output": {"task_id": "job-1"}}),
        httpx.Response(
            200,
            json={
                "request_id": "req-1",
                "usage": {"output_image_count": 2},
                "output": {
                    "task_status": "SUCCEEDED",
                    "choices": [
                        {
                            "message": {
                                "content": [
                                    {"image": _RESULT},
                                    {"image": second_url},
                                ]
                            }
                        }
                    ],
                },
            },
        ),
    )
    job = model.submit("A line drawing of a cat.")

    state = model.fetch(job)

    assert state.status is MediaJobStatus.SUCCEEDED
    assert isinstance(state.result, MediaResult[ImageBlock])
    restored = type(state).model_validate_json(state.model_dump_json())
    assert isinstance(restored.result, MediaResult[ImageBlock])
    assert [block.source.url for block in state.result.content] == [_RESULT, second_url]
    assert all(block.media_type == "image/png" for block in state.result.content)
    assert state.result.expires_at_ms == expires_at_ms
    assert state.result.usage == {"output_image_count": 2}
    assert state.result.request_id == "req-1"
