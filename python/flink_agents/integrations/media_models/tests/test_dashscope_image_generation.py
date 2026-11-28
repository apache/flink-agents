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
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any
from unittest.mock import Mock

import httpx
import pytest

from flink_agents.api.chat_message import (
    AudioBlock,
    ImageBlock,
    UnsupportedContentBlockError,
)
from flink_agents.api.media import (
    BaseImageGenerationModelSetup,
    MediaJob,
    MediaJobStatus,
    MediaResult,
)
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeImageGenerationModelSetup,
)

_IMAGE = "https://example.test/source.png"
_RESULT = "https://oss.example/out.png?Expires=1700000000"


@pytest.fixture
def model_factory(
    setup_factory: Callable[..., BaseImageGenerationModelSetup],
) -> Callable[..., BaseImageGenerationModelSetup]:
    def create(**overrides: Any) -> BaseImageGenerationModelSetup:
        values: dict[str, Any] = {"model": "qwen-image-3.0"}
        values.update(overrides)
        return setup_factory(DashScopeImageGenerationModelSetup, **values)

    return create


@pytest.mark.parametrize(
    ("model_name", "use_image_jobs"),
    [
        ("qwen-image-3.0", False),
        ("custom-image", False),
        ("wan2.6-t2i", True),
        ("wan2.6-image", True),
        ("wan2.7-image-pro", True),
        ("kling-image", True),
        ("vidu-image", True),
        ("z-image", True),
    ],
)
def test_submit_sends_text_and_images_in_order(
    model_factory: Callable[..., BaseImageGenerationModelSetup],
    http_calls,
    model_name: str,
    use_image_jobs: bool,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model = model_factory(model=model_name, n=1, watermark=False)
    jobs = model._client().images.jobs
    create = Mock(wraps=jobs.create)
    monkeypatch.setattr(jobs, "create", create)
    calls, responses = http_calls
    responses.append(
        httpx.Response(
            200, json={"output": {"task_id": "job-1", "task_status": "PENDING"}}
        )
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
    assert create.call_count == int(use_image_jobs)
    (call,) = calls
    assert call.method == "POST"
    assert call.url.path == "/api/v1/services/aigc/image-generation/generation"
    assert call.headers["X-DashScope-Async"] == "enable"
    assert json.loads(call.content) == {
        "model": model_name,
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
        "parameters": {"n": 1, "watermark": False, "size": "1024*1024"},
    }


@pytest.mark.parametrize(
    ("prompt", "images", "error"),
    [
        (None, None, TypeError),
        ("", None, ValueError),
        ("Draw a cat.", [AudioBlock.from_url("audio/mpeg", _IMAGE)], TypeError),
        ("Draw a cat.", ImageBlock.from_url("image/png", _IMAGE), TypeError),
        (
            "Draw a cat.",
            [ImageBlock.from_url("image/png", "file:///etc/passwd")],
            UnsupportedContentBlockError,
        ),
        (
            "Draw a cat.",
            [ImageBlock.from_url("image/png", "/etc/passwd")],
            UnsupportedContentBlockError,
        ),
        (
            "Draw a cat.",
            [ImageBlock.from_url("image/png", "oss://dashscope-instant/a.png")],
            UnsupportedContentBlockError,
        ),
    ],
)
def test_submit_rejects_invalid_inputs_before_request(
    model_factory: Callable[..., BaseImageGenerationModelSetup],
    http_calls,
    prompt: Any,
    images: Any,
    error: type[Exception],
) -> None:
    model = model_factory()
    calls, _ = http_calls

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
    model_factory: Callable[..., BaseImageGenerationModelSetup],
    http_calls,
    second_url: str,
    expires_at_ms: int | None,
) -> None:
    model = model_factory()
    _, responses = http_calls
    responses.extend(
        [
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
                                        {"text": "caption"},
                                        {"image": _RESULT},
                                        {"image": second_url},
                                    ]
                                }
                            }
                        ],
                    },
                },
            ),
        ]
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


def test_image_jobs_preserves_file_uri_as_prompt(
    model_factory: Callable[..., BaseImageGenerationModelSetup],
    http_calls,
    tmp_path: Path,
) -> None:
    prompt_file = tmp_path / "prompt.txt"
    prompt_file.write_text("local text", encoding="utf-8")
    prompt = prompt_file.as_uri()
    model = model_factory(model="wan2.6-t2i")
    requests, responses = http_calls
    responses.append(httpx.Response(200, json={"output": {"task_id": "job-1"}}))
    assert model.submit(prompt).job_id == "job-1"
    [request] = requests
    assert json.loads(request.content)["input"]["messages"] == [
        {"role": "user", "content": [{"text": prompt}]}
    ]


@pytest.mark.parametrize(
    ("model_name", "body"),
    [
        ("qwen-image-3.0", {"task_id": "top-level", "output": {}}),
        ("wan2.6-t2i", {"output": {}}),
    ],
)
def test_submit_requires_task_id(
    model_factory: Callable[..., BaseImageGenerationModelSetup],
    http_calls,
    model_name: str,
    body: dict[str, Any],
) -> None:
    model = model_factory(model=model_name)
    _, responses = http_calls
    responses.append(httpx.Response(200, json=body))
    with pytest.raises(ValueError, match="task id"):
        model.submit("Draw one picture.")


@pytest.mark.parametrize(
    "content",
    [
        {"image": _RESULT},
        [{"image": _RESULT}, {"image": 123}],
        [{"image": _RESULT}, {"image": {"url": _RESULT}}],
        [{"image": _RESULT}, {"image": "file:///tmp/private.png"}],
        [{"image": _RESULT}, {"image": "https:///no-host.png"}],
    ],
)
def test_fetch_rejects_invalid_image_result(
    model_factory: Callable[..., BaseImageGenerationModelSetup],
    http_calls,
    content,
) -> None:
    model = model_factory()
    requests, responses = http_calls
    output = {
        "task_status": "SUCCEEDED",
        "choices": [{"message": {"content": content}}],
    }
    responses.append(httpx.Response(200, json={"output": output}))
    with pytest.raises(ValueError):
        model.fetch(MediaJob(job_id="job-1", submitted_at_ms=0))
    [request] = requests
    assert request.method == "GET"
