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
"""Asynchronous media job submission, polling, and cancellation contract."""

import time
from abc import ABC, abstractmethod
from typing import Any, Generic, TypeVar

from flink_agents.api.media.media_job import (
    MediaJob,
    MediaJobError,
    MediaJobState,
    MediaJobStatus,
    MediaJobTimeoutError,
    validate_positive_seconds,
)
from flink_agents.api.media.media_result import MediaResult

DEFAULT_WAIT_TIMEOUT = 1800.0
DEFAULT_POLL_INTERVAL = 5.0

ResultT = TypeVar("ResultT", bound=MediaResult)


class AsyncJobModel(ABC, Generic[ResultT]):
    """Asynchronous media capability exposing resumable job operations."""

    @abstractmethod
    def submit(self, *args: Any, **kwargs: Any) -> MediaJob:
        """Submit an asynchronous media job and return its serializable handle."""

    @abstractmethod
    def fetch(self, job: MediaJob) -> MediaJobState[ResultT]:
        """Observe the current state of a previously submitted media job."""

    def wait(
        self,
        job: MediaJob,
        *,
        timeout: float | None = DEFAULT_WAIT_TIMEOUT,
        poll_interval: float = DEFAULT_POLL_INTERVAL,
    ) -> ResultT:
        """Poll ``job`` until completion, failure, or timeout budget exhaustion.

        Args:
            job: Previously submitted media job handle.
            timeout: Maximum wait duration in seconds, or None for unlimited waiting.
            poll_interval: Positive interval between polls in seconds.

        Returns:
            Result produced when the job reaches SUCCEEDED.

        Raises:
            MediaJobTimeoutError: When ``timeout`` elapses before completion.
            MediaJobError: When the job reaches FAILED, CANCELED, or UNKNOWN,
                or SUCCEEDED without a result payload.
        """
        validated_timeout = (
            None
            if timeout is None
            else validate_positive_seconds(timeout, "wait timeout")
        )
        validated_interval = validate_positive_seconds(poll_interval, "poll interval")
        deadline = (
            None if validated_timeout is None else time.monotonic() + validated_timeout
        )
        status = MediaJobStatus.UNKNOWN
        while True:
            if deadline is not None and time.monotonic() >= deadline:
                raise MediaJobTimeoutError(job, validated_timeout, status)
            state = self.fetch(job)
            status = state.status
            if status is MediaJobStatus.SUCCEEDED:
                if state.result is None:
                    msg = f"Media job {job.job_id} succeeded without a result."
                    raise MediaJobError(job, status, message=msg)
                return state.result
            if status in (
                MediaJobStatus.FAILED,
                MediaJobStatus.CANCELED,
                MediaJobStatus.UNKNOWN,
            ):
                raise MediaJobError(job, status, state.error_code, state.error_message)
            delay = validated_interval
            if deadline is not None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise MediaJobTimeoutError(job, validated_timeout, status)
                delay = min(delay, remaining)
            time.sleep(delay)

    @abstractmethod
    def cancel(self, job: MediaJob) -> None:
        """Request cancellation of a pending or running media job."""

    def _submit_and_wait(
        self,
        *args: Any,
        timeout: float | None = DEFAULT_WAIT_TIMEOUT,
        **kwargs: Any,
    ) -> ResultT:
        """Validate ``timeout``, submit a job, and wait for its completion."""
        wait_budget = DEFAULT_WAIT_TIMEOUT if timeout is None else timeout
        validate_positive_seconds(wait_budget, "wait timeout")
        job = self.submit(*args, **kwargs)
        return self.wait(job, timeout=wait_budget)
