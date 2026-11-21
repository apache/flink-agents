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
from typing import Any, ClassVar, Generic, Mapping, Sequence, TypeVar

from pydantic import BaseModel, ConfigDict, Field, field_validator

from flink_agents.api.chat_message import (
    AudioBlock,
    DataContentBlock,
    DocumentBlock,
    ImageBlock,
    TextBlock,
    VideoBlock,
)


class _MediaTask(str, Enum):
    """Internal identifiers for job validation and cross-language dispatch."""

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
    task: str = Field(description="Capability identifier recorded when submitting.")
    job_id: str = Field(description="Provider task identifier.")
    submitted_at_ms: int = Field(description="Local submission time, epoch millis.")
    expires_at_ms: int | None = Field(
        default=None,
        description="Time until which the provider can still be queried for this task.",
    )

    @field_validator("task")
    @classmethod
    def _validate_task(cls, value: str) -> str:
        expected = tuple(task.value for task in _MediaTask)
        if value not in expected:
            msg = (
                f"Invalid media task {value!r}; expected one of: {', '.join(expected)}."
            )
            raise ValueError(msg)
        return value


ContentT = TypeVar("ContentT", bound=DataContentBlock)


class MediaResult(BaseModel, Generic[ContentT]):
    """Output of a finished generation, transcription, or synthesis."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    content: list[ContentT] = Field(
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


ResultT = TypeVar("ResultT", bound=MediaResult)


class MediaJobState(BaseModel, Generic[ResultT]):
    """One observation of a job, returned by ``fetch``."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    job: MediaJob
    status: MediaJobStatus
    result: ResultT | None = Field(
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


DEFAULT_WAIT_TIMEOUT = 1800.0
DEFAULT_POLL_INTERVAL = 5.0


class AsyncJobModel(BaseModel, Generic[ResultT], ABC):
    """A media capability with submitted jobs that can be queried and resumed.

    Providers that back a media capability with cloud jobs implement this
    interface alongside the task-specific model base class. Synchronous
    providers implement only the task-specific base class.

    Call ``submit`` and persist its returned job separately when waiting must
    resume after recovery. None of these methods records durable execution or
    emits events itself.
    """

    model_config = ConfigDict(extra="forbid")

    model: str = Field(description="Name of the model to call.")
    _task: ClassVar[_MediaTask]

    def _submit_and_wait(
        self,
        *args: Any,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> ResultT:
        """Submit a request and wait for its result.

        Args:
            *args: The inputs accepted by this media capability.
            timeout: Polling budget in seconds after submission. None uses
                ``DEFAULT_WAIT_TIMEOUT``. This does not change HTTP request
                timeouts.
            **kwargs: Provider parameters for submission.
        """
        budget = DEFAULT_WAIT_TIMEOUT if timeout is None else timeout
        budget = self._validate_wait_timeout(budget, allow_zero=False)
        job = self.submit(*args, **kwargs)
        return self.wait(job, timeout=budget)

    @abstractmethod
    def submit(self, *args: Any, **kwargs: Any) -> MediaJob:
        """Submit a request and return its job handle without waiting."""

    @abstractmethod
    def fetch(self, job: MediaJob) -> MediaJobState[ResultT]:
        """Query the task status once.

        Retrieve the full result when the status is SUCCEEDED.
        """

    def wait(
        self,
        job: MediaJob,
        *,
        timeout: float | None = DEFAULT_WAIT_TIMEOUT,
        poll_interval: float = DEFAULT_POLL_INTERVAL,
    ) -> ResultT:
        """Block until the task reaches a terminal status.

        Waits up to ``poll_interval`` seconds between queries. The polling
        budget limits when a new query can start. A query already in progress
        can finish and return its result. On timeout ``MediaJobTimeoutError``
        is raised and the provider task can continue.

        Args:
            job: Handle returned by ``submit``.
            timeout: Polling budget in seconds from the start of this wait.
                Defaults to ``DEFAULT_WAIT_TIMEOUT`` (1800s). Pass ``None`` for
                unlimited polling.
            poll_interval: Positive seconds between status queries. Defaults to
                ``DEFAULT_POLL_INTERVAL`` (5s).

        Raises:
            MediaJobError: The task ended as FAILED, CANCELED, or UNKNOWN.
            MediaJobTimeoutError: The polling budget elapsed with no terminal result.
            TypeError: ``timeout`` or ``poll_interval`` has an invalid type.
            ValueError: ``timeout`` is negative/non-finite or ``poll_interval`` is
                non-positive/non-finite.
        """
        started_at = time.monotonic()
        self._check_job(job)
        wait_timeout = self._validate_wait_timeout(timeout, allow_zero=True)
        interval = self._validate_poll_interval(poll_interval)
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
            delay = interval
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

    @staticmethod
    def _validate_wait_timeout(
        timeout: float | None, *, allow_zero: bool = True
    ) -> float | None:
        """Validate the polling budget for an asynchronous job."""
        if timeout is None:
            return None
        if isinstance(timeout, bool) or not isinstance(timeout, int | float):
            msg = "The wait timeout must be a finite number or None."
            raise TypeError(msg)
        if not math.isfinite(timeout):
            msg = "The wait timeout must be finite."
            raise ValueError(msg)
        if allow_zero:
            if timeout < 0:
                msg = "The wait timeout must be non-negative."
                raise ValueError(msg)
        elif timeout <= 0:
            msg = "The wait timeout must be greater than 0."
            raise ValueError(msg)
        return timeout

    @staticmethod
    def _validate_poll_interval(poll_interval: float) -> float:
        """Validate the positive delay between job status queries."""
        if isinstance(poll_interval, bool) or not isinstance(
            poll_interval, int | float
        ):
            msg = "The poll interval must be a positive finite number."
            raise TypeError(msg)
        if not math.isfinite(poll_interval):
            msg = "The poll interval must be finite."
            raise ValueError(msg)
        if poll_interval <= 0:
            msg = "The poll interval must be greater than 0."
            raise ValueError(msg)
        return poll_interval

    def _check_job(self, job: MediaJob) -> None:
        """Check the job against this capability's provider, model, and task."""
        expected = self._provider_name()
        if (
            job.provider != expected
            or job.model != self.model
            or job.task != self._task.value
        ):
            msg = (
                f"Media job {job.job_id} belongs to provider {job.provider!r}, "
                f"model {job.model!r}, task {job.task!r}; this capability is "
                f"provider {expected!r}, model {self.model!r}, task "
                f"{self._task.value!r}."
            )
            raise ValueError(msg)

    def _provider_name(self) -> str:
        """Provider identifier stamped on jobs this capability submits.

        Subclasses override this with their own provider name.
        """
        return type(self).__name__


class SpeechSynthesisModel(BaseModel, ABC):
    """Synthesize speech from text."""

    model_config = ConfigDict(extra="forbid")

    model: str = Field(description="Name of the model to call.")
    _task: ClassVar[_MediaTask] = _MediaTask.SPEECH_SYNTHESIS

    @abstractmethod
    def synthesize(
        self,
        text: str,
        *,
        voice: str | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[AudioBlock]:
        """Synthesize text and return the finished audio.

        Args:
            text: Text to synthesize.
            voice: Optional voice overriding ``model_parameters["voice"]`` and
                the bound default voice.
            model_parameters: Provider model parameters overriding bound defaults.
            timeout: Optional timeout in seconds for this call. None uses the
                provider default.
        """


class TranscriptionModel(BaseModel, ABC):
    """Transcribe one audio or video input."""

    model_config = ConfigDict(extra="forbid")

    model: str = Field(description="Name of the model to call.")
    _task: ClassVar[_MediaTask] = _MediaTask.TRANSCRIPTION

    @abstractmethod
    def transcribe(
        self,
        audio: AudioBlock | VideoBlock,
        *,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[TextBlock]:
        """Transcribe a recording and return its result.

        Args:
            audio: Recording to transcribe.
            model_parameters: Provider model parameters overriding bound defaults.
            timeout: Optional timeout in seconds for this call. None uses the
                provider default (or default polling budget for async providers).
        """


class ImageGenerationModel(BaseModel, ABC):
    """Generate images from a prompt and optional reference images."""

    model_config = ConfigDict(extra="forbid")

    model: str = Field(description="Name of the model to call.")
    _task: ClassVar[_MediaTask] = _MediaTask.IMAGE_GENERATION

    @abstractmethod
    def generate(
        self,
        prompt: str,
        *,
        images: Sequence[ImageBlock] | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[ImageBlock]:
        """Generate images from a prompt and optional reference images.

        Args:
            prompt: Text prompt describing the desired image.
            images: Optional reference images.
            model_parameters: Provider model parameters overriding bound defaults.
            timeout: Optional timeout in seconds for this call. None uses the
                provider default (or default polling budget for async providers).
        """


class VideoGenerationModel(BaseModel, ABC):
    """Generate videos from a prompt and optional media inputs."""

    model_config = ConfigDict(extra="forbid")

    model: str = Field(description="Name of the model to call.")
    _task: ClassVar[_MediaTask] = _MediaTask.VIDEO_GENERATION

    @abstractmethod
    def generate(
        self,
        prompt: str = "",
        *,
        media: Sequence[ImageBlock | AudioBlock | VideoBlock | DocumentBlock]
        | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[VideoBlock]:
        """Generate a video; provide a prompt, media inputs, or both.

        Args:
            prompt: Optional text prompt.
            media: Optional ordered media inputs.
            model_parameters: Provider model parameters overriding bound defaults.
            timeout: Optional timeout in seconds for this call. None uses the
                provider default (or default polling budget for async providers).
        """
