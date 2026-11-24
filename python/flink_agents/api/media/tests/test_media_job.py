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
import json
import pickle
from typing import Any

import pytest
from pydantic import ValidationError

from flink_agents.api.chat_message import ImageBlock, TextBlock, VideoBlock
from flink_agents.api.media import (
    MediaJob,
    MediaJobError,
    MediaJobState,
    MediaJobStatus,
    MediaJobTimeoutError,
    MediaResult,
)

_JOB = MediaJob(
    job_id="job-1",
    submitted_at_ms=1_700_000_000_000,
    expires_at_ms=1_700_086_400_000,
)


def test_media_job_json_roundtrip() -> None:
    restored = MediaJob.model_validate_json(_JOB.model_dump_json())

    assert restored == _JOB
    payload = json.loads(_JOB.model_dump_json())
    assert set(payload) == {"job_id", "submitted_at_ms", "expires_at_ms"}


def test_media_job_rejects_empty_job_id() -> None:
    for bad in ("", "   "):
        with pytest.raises(ValueError):
            MediaJob(job_id=bad, submitted_at_ms=1)


def test_media_job_and_state_are_immutable_and_reject_extra_fields() -> None:
    state = MediaJobState[MediaResult[TextBlock]](
        job=_JOB, status=MediaJobStatus.PENDING
    )
    with pytest.raises(ValidationError):
        _JOB.job_id = "other"  # type: ignore[misc]
    with pytest.raises(ValidationError):
        state.status = MediaJobStatus.RUNNING  # type: ignore[misc]
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        MediaJob(job_id="j1", submitted_at_ms=1, extra_field="x")  # type: ignore[call-arg]
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        MediaJobState(job=_JOB, status=MediaJobStatus.PENDING, extra_field="x")  # type: ignore[call-arg]


def test_media_job_state_json_roundtrip() -> None:
    state = MediaJobState[MediaResult[VideoBlock]](
        job=_JOB,
        status=MediaJobStatus.FAILED,
        error_code="BadRequest",
        error_message="no prompt",
    )

    restored = MediaJobState[MediaResult[VideoBlock]].model_validate_json(
        state.model_dump_json()
    )

    assert restored == state
    assert restored.result is None


@pytest.mark.parametrize(
    ("result_type", "block"),
    [
        (MediaResult[TextBlock], TextBlock(text="hello")),
        (
            MediaResult[ImageBlock],
            ImageBlock.from_url("image/png", "https://example.test/a.png"),
        ),
        (
            MediaResult[VideoBlock],
            VideoBlock.from_url("video/mp4", "https://example.test/a.mp4"),
        ),
    ],
)
def test_job_state_deserialization_preserves_the_result_type(
    result_type: type[MediaResult], block: Any
) -> None:
    result = result_type(content=[block], usage={"count": 1})
    state_type = MediaJobState[result_type]
    state = state_type(job=_JOB, status=MediaJobStatus.SUCCEEDED, result=result)

    restored = state_type.model_validate_json(state.model_dump_json())

    assert type(restored.result) is result_type
    assert type(restored.result.content[0]) is type(block)
    assert restored == state
    wrong_block = ImageBlock.from_url("image/png", "https://example.test/wrong.png")
    if isinstance(block, ImageBlock):
        wrong_block = TextBlock(text="wrong")
    payload = state.model_dump(mode="json")
    payload["result"]["content"] = [wrong_block.model_dump(mode="json")]
    with pytest.raises(ValueError):
        state_type.model_validate(payload)


@pytest.mark.parametrize(
    "error",
    [
        MediaJobError(_JOB, MediaJobStatus.FAILED, "BadRequest", "invalid prompt"),
        MediaJobTimeoutError(_JOB, 5.0, MediaJobStatus.PENDING),
    ],
    ids=["job-error", "timeout"],
)
def test_media_job_exception_pickle_roundtrip(error: MediaJobError) -> None:
    restored = pickle.loads(pickle.dumps(error))

    assert type(restored) is type(error)
    assert vars(restored) == vars(error)
    assert str(restored) == str(error)


@pytest.mark.parametrize(
    ("timeout", "error_type", "pattern"),
    [
        (0, ValueError, "greater than 0"),
        (-1.0, ValueError, "greater than 0"),
        (float("nan"), ValueError, "must be finite"),
        (float("inf"), ValueError, "must be finite"),
        (True, TypeError, "positive finite number"),
    ],
)
def test_media_job_timeout_error_rejects_non_positive_budget(
    timeout: Any, error_type: type[Exception], pattern: str
) -> None:
    with pytest.raises(error_type, match=pattern):
        MediaJobTimeoutError(_JOB, timeout, MediaJobStatus.UNKNOWN)


def test_media_job_timeout_error_message_format() -> None:
    error = MediaJobTimeoutError(_JOB, 5.0, MediaJobStatus.PENDING)
    assert str(error) == "Media job job-1 timed out after 5.0s (last status: pending)"
