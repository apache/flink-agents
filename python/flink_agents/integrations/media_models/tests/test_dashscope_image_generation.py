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
from typing import Any

import httpx
import pytest

from flink_agents.api.chat_message import (
    AudioBlock,
    ImageBlock,
    TextBlock,
    UnsupportedContentBlockError,
)
from flink_agents.api.media_models.media_model import (
    MediaJobStatus,
    MediaTask,
)
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModel,
)

_IMAGE = "https://example.test/source.png"
_RESULT = "https://oss.example/out.png?Expires=1700000000"


def _queue(
    model: DashScopeMediaModel, *responses: httpx.Response
) -> list[dict[str, Any]]:
    pending = list(responses)
    calls: list[dict[str, Any]] = []

    def request(method: str, path: str, **kwargs: Any) -> httpx.Response:
        calls.append({"method": method, "path": path, **kwargs})
        return pending.pop(0)

    model._client().transport.request = request
    return calls


def _model(**overrides: Any) -> DashScopeMediaModel:
    values: dict[str, Any] = {
        "model": "qwen-image-3.0",
        "task": MediaTask.IMAGE_GENERATION,
        "api_key": "sk-test",
        "workspace": "ws-1",
    }
    values.update(overrides)
    return DashScopeMediaModel(**values)


def test_submit_sends_text_and_images_in_order() -> None:
    model = _model(parameters={"n": 1})
    calls = _queue(
        model,
        httpx.Response(
            200, json={"output": {"task_id": "job-1", "task_status": "PENDING"}}
        ),
    )

    job = model.submit(
        [
            ImageBlock.from_url("image/png", _IMAGE),
            ImageBlock.from_base64("image/jpeg", "YQ=="),
            TextBlock(text="Turn these into a watercolor."),
        ],
        size="1024*1024",
    )

    assert job.job_id == "job-1"
    assert job.task is MediaTask.IMAGE_GENERATION
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


def test_submit_rejects_missing_text_and_non_images() -> None:
    model = _model()

    def request(*_args: object, **_kwargs: object) -> httpx.Response:
        msg = "submit must not send a request"
        raise AssertionError(msg)

    model._client().transport.request = request

    with pytest.raises(ValueError, match="requires a text block"):
        model.submit([ImageBlock.from_url("image/png", _IMAGE)])
    with pytest.raises(ValueError, match="Image generation accepts at most one"):
        model.submit([TextBlock(text="a"), TextBlock(text="b")])
    with pytest.raises(UnsupportedContentBlockError):
        model.submit([TextBlock(text="a"), AudioBlock.from_url("audio/mpeg", _IMAGE)])


@pytest.mark.parametrize(
    ("second_url", "expires_at_ms"),
    [
        ("https://oss.example/b.png?Expires=1699999999", 1_699_999_999_000),
        ("https://oss.example/b.png", None),
        ("https://oss.example/b.png?Expires=unknown", None),
    ],
)
def test_fetch_reads_image_urls(
    second_url: str,
    expires_at_ms: int | None,
) -> None:
    model = _model()
    _queue(
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
    job = model.submit([TextBlock(text="A line drawing of a cat.")])

    state = model.fetch(job)

    assert state.status is MediaJobStatus.SUCCEEDED
    assert state.result is not None
    assert [block.source.url for block in state.result.content] == [_RESULT, second_url]
    assert all(block.media_type == "image/png" for block in state.result.content)
    assert state.result.expires_at_ms == expires_at_ms
    assert state.result.usage == {"output_image_count": 2}
    assert state.result.request_id == "req-1"
