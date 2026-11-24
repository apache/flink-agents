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
"""Asynchronous media job handle, state, status, and exceptions."""

import math
from enum import Enum
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field, field_validator
from typing_extensions import override

from flink_agents.api.media.media_result import MediaResult

ResultT = TypeVar("ResultT", bound=MediaResult)


def validate_positive_seconds(value: Any, name: str) -> float:
    """Validate that ``value`` is a finite positive number of seconds."""
    if isinstance(value, bool) or not isinstance(value, int | float):
        msg = f"{name} must be a positive finite number, got {type(value).__name__}."
        raise TypeError(msg)
    seconds = float(value)
    if not math.isfinite(seconds) or seconds <= 0:
        msg = f"{name} must be finite and greater than 0, got {value}."
        raise ValueError(msg)
    return seconds


class MediaJobStatus(str, Enum):
    """Lifecycle status of an asynchronous provider media job."""

    PENDING = "pending"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    CANCELED = "canceled"
    UNKNOWN = "unknown"


class MediaJob(BaseModel):
    """Serializable handle for one provider-managed media job.

    A ``MediaJob`` records only the provider job identifier and submission
    timestamps; callers are responsible for routing each job only to a setup
    matching the service, model, and capability that created it.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    job_id: str = Field(min_length=1, description="Provider job identifier.")
    submitted_at_ms: int = Field(
        description="Epoch millisecond timestamp when the job was submitted."
    )
    expires_at_ms: int | None = Field(
        default=None,
        description="Epoch millisecond deadline after which the provider may drop the job.",
    )

    @field_validator("job_id")
    @classmethod
    def _check_job_id(cls, value: str) -> str:
        if not value.strip():
            msg = "job_id must be a non-blank string."
            raise ValueError(msg)
        return value


class MediaJobState(BaseModel, Generic[ResultT]):
    """Snapshot returned by ``AsyncJobModel.fetch``."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    job: MediaJob = Field(description="Job handle observed.")
    status: MediaJobStatus = Field(description="Observed job lifecycle status.")
    result: ResultT | None = Field(
        default=None,
        description="Completed media result when status is SUCCEEDED.",
    )
    error_code: str | None = Field(
        default=None,
        description="Provider error code when the job did not succeed.",
    )
    error_message: str | None = Field(
        default=None,
        description="Provider error detail when the job did not succeed.",
    )


class MediaJobError(Exception):
    """Error raised when a provider media job fails or reaches a terminal status."""

    def __init__(
        self,
        job: MediaJob,
        status: MediaJobStatus,
        code: str | None = None,
        message: str | None = None,
    ) -> None:
        """Initialize a media job failure carrying job handle and status details."""
        self.job = job
        self.status = status
        self.code = code
        self.message = message
        super().__init__(self._format_message())

    def _format_message(self) -> str:
        prefix = f"Media job {self.job.job_id} ended with status {self.status.value}"
        if self.code and self.message:
            return f"{prefix} ({self.code}): {self.message}"
        if self.message:
            return f"{prefix}: {self.message}"
        if self.code:
            return f"{prefix} ({self.code})"
        return prefix

    def __reduce__(self) -> tuple[type["MediaJobError"], tuple[Any, ...]]:
        """Preserve job error fields across pickle serialization."""
        return (type(self), (self.job, self.status, self.code, self.message))


class MediaJobTimeoutError(MediaJobError):
    """Error raised when polling a media job exceeds the wait timeout budget."""

    def __init__(
        self,
        job: MediaJob,
        timeout: float,
        status: MediaJobStatus = MediaJobStatus.UNKNOWN,
        message: str | None = None,
    ) -> None:
        """Initialize a timeout error with strictly positive budget and last status."""
        validated_timeout = validate_positive_seconds(timeout, "wait timeout")
        self.timeout = validated_timeout
        detail = (
            message
            if message is not None
            else f"Media job {job.job_id} timed out after {validated_timeout}s"
        )
        super().__init__(job, status, message=detail)

    @override
    def _format_message(self) -> str:
        return f"{self.message} (last status: {self.status.value})"

    def __reduce__(self) -> tuple[type["MediaJobTimeoutError"], tuple[Any, ...]]:
        """Preserve timeout error fields across pickle serialization."""
        return (type(self), (self.job, self.timeout, self.status, self.message))
