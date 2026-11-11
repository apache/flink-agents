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
from asyncio import CancelledError as AsyncCancelledError
from concurrent.futures import CancelledError
from types import SimpleNamespace

import pytest

from flink_agents.api.chat_message import TextBlock
from flink_agents.api.media_models.media_model import (
    MediaJob,
    MediaJobError,
    MediaJobStatus,
    MediaJobTimeoutError,
    MediaResult,
    MediaTask,
)
from flink_agents.api.rerank_models.rerank_model import RerankHit, RerankResult
from flink_agents.runtime.python_java_utils import (
    call_media_cancel,
    call_media_fetch,
    call_media_generate,
    call_media_submit,
    call_media_wait,
    call_rerank_model,
)

_JOB = MediaJob(
    provider="dashscope",
    model="wan2.7-i2v",
    task=MediaTask.VIDEO_GENERATION,
    job_id="job-1",
    submitted_at_ms=1,
)


class _Media:
    def generate(self, content, *, timeout=None, **kwargs: object) -> MediaResult:
        assert timeout == 1.5
        assert kwargs == {"size": "512*512"}
        return MediaResult(content=list(content), usage={"images": 1})

    def fetch(self, job: MediaJob) -> None:
        raise MediaJobError(job, MediaJobStatus.FAILED, "BadRequest", "invalid prompt")

    def wait(self, job: MediaJob, *, timeout: float | None = None) -> None:
        raise MediaJobTimeoutError(job, 5.0, MediaJobStatus.PENDING)

    def cancel(self, job: MediaJob) -> None:
        assert job.job_id == "job-1"


class _Rerank:
    def rerank(self, query, documents, **kwargs: object) -> RerankResult:
        assert kwargs["top_n"] == 1
        assert isinstance(query, TextBlock)
        return RerankResult(
            results=[RerankHit(index=0, relevance_score=0.5, document=documents[0])],
            usage={"total_tokens": 3},
            request_id="req-1",
        )


def test_call_media_generate_returns_json() -> None:
    """The Java bridge receives the finished media result as JSON."""
    outcome = call_media_generate(
        _Media(), '[{"type": "text", "text": "A notebook."}]', 1.5, {"size": "512*512"}
    )

    assert outcome["status"] == "SUCCESS"
    result = MediaResult.model_validate_json(outcome["result"])
    assert result.content[0].text == "A notebook."
    assert result.usage == {"images": 1}


def test_call_media_fetch_returns_job_error() -> None:
    outcome = call_media_fetch(_Media(), _JOB.model_dump_json())

    assert outcome["status"] == "JOB_ERROR"
    assert outcome["job_status"] == "failed"
    assert outcome["code"] == "BadRequest"
    assert outcome["message"] == "invalid prompt"
    assert MediaJob.model_validate_json(outcome["job"]).job_id == "job-1"


def test_call_media_wait_returns_timeout() -> None:
    outcome = call_media_wait(_Media(), _JOB.model_dump_json(), 5.0)

    assert outcome["status"] == "TIMEOUT"
    assert outcome["job_status"] == "pending"
    assert outcome["timeout"] == 5.0
    assert outcome["message"] == "timed out after 5.0 seconds"


def test_call_media_cancel_returns_empty_success() -> None:
    outcome = call_media_cancel(_Media(), _JOB.model_dump_json())

    assert outcome == {"status": "SUCCESS", "result": None}


def test_call_rerank_model_returns_json() -> None:
    """The Java bridge receives the top rerank hit as JSON."""
    outcome = call_rerank_model(
        _Rerank(),
        '{"type": "text", "text": "notes"}',
        '[{"type": "text", "text": "A notebook."}]',
        1,
        True,
        None,
        None,
        {},
    )

    assert outcome["status"] == "SUCCESS"
    result = RerankResult.model_validate_json(outcome["result"])
    assert result.results[0].index == 0
    assert result.results[0].document.text == "A notebook."


_OPERATIONS = [
    (call_media_generate, "generate", ("[]", None)),
    (call_media_submit, "submit", ("[]",)),
    (call_media_fetch, "fetch", (_JOB.model_dump_json(),)),
    (call_media_wait, "wait", (_JOB.model_dump_json(), None)),
    (call_media_cancel, "cancel", (_JOB.model_dump_json(),)),
    (
        call_rerank_model,
        "rerank",
        ('{"type":"text","text":"query"}', "[]", None, None, None, None),
    ),
]


@pytest.mark.parametrize(("bridge", "method", "args"), _OPERATIONS)
@pytest.mark.parametrize(
    ("error_type", "status"),
    [
        (InterruptedError, "INTERRUPTED"),
        (CancelledError, "CANCELLED"),
        (AsyncCancelledError, "CANCELLED"),
    ],
)
def test_bridge_preserves_cancellation(
    bridge, method, args, error_type, status
) -> None:
    def interrupted(*args: object, **kwargs: object) -> None:
        message = "operation stopped"
        raise error_type(message)

    resource = SimpleNamespace(**{method: interrupted})
    assert bridge(resource, *args) == {"status": status, "message": "operation stopped"}


@pytest.mark.parametrize(("bridge", "method", "args"), _OPERATIONS)
def test_bridge_leaves_ordinary_errors_unchanged(bridge, method, args) -> None:
    error = ValueError("invalid request")

    def failed(*args: object, **kwargs: object) -> None:
        raise error

    resource = SimpleNamespace(**{method: failed})
    with pytest.raises(ValueError) as raised:
        bridge(resource, *args)
    assert raised.value is error
