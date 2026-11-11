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
import math
import time
from abc import ABC, abstractmethod
from enum import Enum
from typing import Any, Sequence

from pydantic import BaseModel, ConfigDict, Field
from typing_extensions import override

from flink_agents.api.chat_message import DataContentBlock
from flink_agents.api.resource import Resource, ResourceType


class MediaTask(str, Enum):
    """The capability a MediaModel instance serves.

    One instance serves exactly one task; the task is configured on the
    resource and never inferred from the model name.
    """

    IMAGE_GENERATION = "image_generation"
    VIDEO_GENERATION = "video_generation"
    TRANSCRIPTION = "transcription"
    SPEECH_SYNTHESIS = "speech_synthesis"


class MediaJobStatus(str, Enum):
    """Lifecycle state of a submitted asynchronous provider task."""

    PENDING = "pending"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    CANCELED = "canceled"
    # The provider can no longer identify the task, for example because its
    # retention window has passed.
    UNKNOWN = "unknown"


class MediaJob(BaseModel):
    """Handle of a submitted asynchronous provider task.

    Frozen and JSON-serializable so it can be persisted as the result of a
    durable execution and replayed across recovery.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    provider: str = Field(description="Provider that owns the task, e.g. 'dashscope'.")
    model: str = Field(description="Model the task was submitted to.")
    task: MediaTask = Field(description="Capability the task was submitted for.")
    job_id: str = Field(description="Provider task identifier.")
    submitted_at_ms: int = Field(description="Local submission time, epoch millis.")
    expires_at_ms: int | None = Field(
        default=None,
        description="Time until which the provider can still be queried for this task.",
    )


class MediaResult(BaseModel):
    """Output of a finished generation, transcription, or synthesis."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    content: list[DataContentBlock] = Field(
        description="Generated content, in the order returned by the provider."
    )
    expires_at_ms: int | None = Field(
        default=None,
        description="Time until which result URLs stay valid, when the provider says so.",
    )
    usage: dict[str, int | float | str] = Field(
        default_factory=dict,
        description="Provider usage counters, kept as reported.",
    )
    request_id: str | None = Field(
        default=None, description="Provider request identifier."
    )
    metadata: dict[str, Any] = Field(
        default_factory=dict,
        description="Structured provider data that does not fit into a content block.",
    )


class MediaJobState(BaseModel):
    """One observation of a job, returned by ``fetch``."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    job: MediaJob
    status: MediaJobStatus
    result: MediaResult | None = Field(
        default=None, description="Present only when the status is SUCCEEDED."
    )
    error_code: str | None = Field(
        default=None, description="Present only when the status is FAILED."
    )
    error_message: str | None = None


class MediaJobError(Exception):
    """An operation on a media job failed.

    Attributes:
        job: The job associated with the error.
        status: The last observed job status, or UNKNOWN if unavailable.
        code: Provider error code, when the provider reported one.
        message: Provider error message, when the provider reported one.
    """

    def __init__(
        self,
        job: MediaJob,
        status: MediaJobStatus,
        code: str | None = None,
        message: str | None = None,
    ) -> None:
        """Record the job, its observed status, and any provider error."""
        self.job = job
        self.status = status
        self.code = code
        self.message = message
        detail = status.value
        if code is not None:
            detail = f"{detail} ({code})"
        if message is not None:
            detail = f"{detail}: {message}"
        super().__init__(f"Media job {job.job_id}: {detail}")

    def __reduce__(self) -> tuple[type[Exception], tuple[Any, ...], dict[str, Any]]:
        """Rebuild the exception with its structured constructor arguments."""
        return type(self), (self.job, self.status, self.code, self.message), vars(self)


class MediaJobTimeoutError(MediaJobError):
    """The polling budget for ``wait`` elapsed before a terminal status was observed.

    Attributes:
        timeout: The polling budget in seconds.
    """

    def __init__(
        self,
        job: MediaJob,
        timeout: float,
        status: MediaJobStatus = MediaJobStatus.UNKNOWN,
    ) -> None:
        """Record the job, its last observed status, and the polling budget."""
        self.timeout = timeout
        super().__init__(job, status, message=f"timed out after {timeout} seconds")

    def __reduce__(self) -> tuple[type[Exception], tuple[Any, ...], dict[str, Any]]:
        """Rebuild the timeout with its job, budget, and last observed status."""
        return type(self), (self.job, self.timeout, self.status), vars(self)


class BaseMediaModel(Resource, ABC):
    """A model that generates, transcribes, or synthesizes media.

    One instance binds a single ``model`` and a single ``task``. Providers
    implement ``generate``, ``supports_async_task``, ``submit``, ``fetch`` and
    ``cancel``; ``wait`` polls ``fetch`` until the task ends or the timeout
    passes.

    Methods never touch memory or emit events, so they can run inside a
    durable execution.
    """

    model: str = Field(description="Name of the model to call.")
    task: MediaTask = Field(description="Capability this instance serves.")
    wait_timeout: float | None = Field(
        default=1800.0,
        allow_inf_nan=False,
        description="Polling budget in seconds from the start of an asynchronous wait. "
        "None allows unlimited polling.",
    )
    poll_interval: float = Field(
        default=5.0,
        gt=0,
        allow_inf_nan=False,
        description="Seconds between status queries while waiting.",
    )

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.MEDIA_MODEL

    @abstractmethod
    def generate(
        self,
        content: Sequence[DataContentBlock],
        *,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> MediaResult:
        """Return the finished result, blocking until it is ready.

        Models that only support asynchronous submission implement this by
        submitting a task and waiting for it.

        Args:
            content: Input blocks. Accepted combinations depend on ``task``.
            timeout: Polling budget in seconds after asynchronous submission. None uses
                ``wait_timeout``. For a single synchronous request, this is that
                request's timeout; None then uses the provider's request timeout.
            **kwargs: Provider parameters overriding the configured defaults.
        """

    @abstractmethod
    def supports_async_task(self) -> bool:
        """Whether this model accepts submit-then-query task execution."""

    @abstractmethod
    def submit(self, content: Sequence[DataContentBlock], **kwargs: Any) -> MediaJob:
        """Submit a task and return its handle without waiting.

        Raises:
            NotImplementedError: When ``supports_async_task`` is false.
        """

    @abstractmethod
    def fetch(self, job: MediaJob) -> MediaJobState:
        """Query the task status once.

        Retrieve the full result when the status is SUCCEEDED.
        """

    def wait(self, job: MediaJob, *, timeout: float | None = None) -> MediaResult:
        """Block until the task reaches a terminal status.

        Waits up to ``poll_interval`` seconds between queries. The polling
        budget limits when a new query can start. A query already in progress
        can finish and return its result. On timeout ``MediaJobTimeoutError``
        is raised and the provider task can continue.

        Args:
            job: Handle returned by ``submit``.
            timeout: Polling budget in seconds from the start of this wait.
                None uses the resource ``wait_timeout``.

        Raises:
            MediaJobError: The task ended as FAILED, CANCELED, or UNKNOWN.
            MediaJobTimeoutError: The polling budget elapsed with no terminal result.
            ValueError: The polling budget is NaN or infinite.
        """
        started_at = time.monotonic()
        self._check_job(job)
        wait_timeout = self._resolve_wait_timeout(timeout)
        status = MediaJobStatus.UNKNOWN
        while True:
            if (
                wait_timeout is not None
                and time.monotonic() - started_at >= wait_timeout
            ):
                raise MediaJobTimeoutError(job, wait_timeout, status)
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
            delay = self.poll_interval
            if wait_timeout is not None:
                elapsed = time.monotonic() - started_at
                delay = min(delay, wait_timeout - elapsed)
            if delay > 0:
                time.sleep(delay)

    @abstractmethod
    def cancel(self, job: MediaJob) -> None:
        """Request cancellation of a task.

        Providers that can only cancel queued tasks raise ``MediaJobError`` for
        any other status.
        """

    def _resolve_wait_timeout(self, timeout: float | None) -> float | None:
        """Resolve and validate the polling budget for an asynchronous job."""
        wait_timeout = self.wait_timeout if timeout is None else timeout
        if wait_timeout is not None and not math.isfinite(wait_timeout):
            msg = "The wait timeout must be finite."
            raise ValueError(msg)
        return wait_timeout

    def _check_job(self, job: MediaJob) -> None:
        """Check the job against this resource's provider, model, and task."""
        expected = self._provider_name()
        if job.provider != expected or job.model != self.model or job.task != self.task:
            msg = (
                f"Media job {job.job_id} belongs to provider {job.provider!r}, "
                f"model {job.model!r}, task {job.task.value!r}; this resource is "
                f"provider {expected!r}, model {self.model!r}, task "
                f"{self.task.value!r}."
            )
            raise ValueError(msg)

    def _provider_name(self) -> str:
        """Provider identifier stamped on jobs this resource submits.

        Subclasses override this with their own provider name.
        """
        return type(self).__name__
