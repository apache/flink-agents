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
"""DashScope media resource with model-bound capabilities and a shared client."""

import os
import time
from abc import abstractmethod
from copy import deepcopy
from dataclasses import dataclass, replace
from typing import Any, ClassVar, Generic, Mapping, Sequence, TypeVar
from urllib.parse import parse_qs, quote, urlparse

import dashscope
import httpx
from dashscope.errors import DashScopeError, check_response, response_json
from pydantic import BaseModel, Field, PrivateAttr
from typing_extensions import override

from flink_agents.api.chat_message import (
    AudioBlock,
    DataContentBlock,
    DocumentBlock,
    ImageBlock,
    TextBlock,
    UnsupportedContentBlockError,
    UrlSource,
    VideoBlock,
)
from flink_agents.api.media.media_model import (
    AsyncJobModel,
    ImageGenerationModel,
    MediaJob,
    MediaJobError,
    MediaJobState,
    MediaJobStatus,
    MediaResult,
    SpeechSynthesisModel,
    TranscriptionModel,
    VideoGenerationModel,
)
from flink_agents.api.media.media_model_provider import BaseMediaModelProvider
from flink_agents.integrations.dashscope_utils import (
    create_client,
    media_source_url,
    usage_counters,
    validate_request_timeout,
)

DEFAULT_REQUEST_TIMEOUT = 60.0
DEFAULT_REGION = "cn-beijing"
_PROVIDER = "dashscope"
_DAY_MS = 24 * 60 * 60 * 1000
_PROVIDER_LABEL = "DashScope"
_ROLES_REQUIRED = "video_media_roles is required when media is not empty."
_AUDIO_MEDIA_TYPES = {
    "mp3": "audio/mpeg",
    "wav": "audio/wav",
    "opus": "audio/ogg",
    "pcm": "audio/pcm",
}
_DEFAULT_AUDIO_FORMAT = "mp3"
_TASK_STATUS = {
    "PENDING": MediaJobStatus.PENDING,
    "SUSPENDED": MediaJobStatus.PENDING,
    "RUNNING": MediaJobStatus.RUNNING,
    "SUCCEEDED": MediaJobStatus.SUCCEEDED,
    "FAILED": MediaJobStatus.FAILED,
    "CANCELED": MediaJobStatus.CANCELED,
}


ResultT = TypeVar("ResultT", bound=MediaResult)


class DashScopeMediaModelProvider(BaseMediaModelProvider):
    """DashScope media generation, transcription, and speech synthesis.

    Register ``ResourceDescriptor(clazz=ResourceName.MediaModelProvider.DASHSCOPE)``
    and retrieve the provider with ``get_resource``. For example,
    ``dashscope.speech(model, voice="Cherry").synthesize("Hello")`` synthesizes
    speech. A bound model can also be reused for multiple calls.

    The SDK client is created by ``open()`` before any model requests. An omitted
    API key is read from ``DASHSCOPE_API_KEY`` when the resource opens.
    ``request_timeout`` bounds each HTTP request. SDK retries and local SDK
    configuration-file loading are disabled.
    ``workspace`` and ``base_url`` retain SDK environment defaults when omitted.
    Closing the resource closes the shared client and ends all bound models'
    lifetime. Bound models must not be closed separately. When constructing the
    provider directly, call ``open()`` before use and ``close()`` when finished.

    Attributes:
    ----------
    api_key : str | None
        DashScope API key. Falls back to ``DASHSCOPE_API_KEY``.
    workspace : str | None
        Bailian workspace id. With ``region``, it selects the workspace
        gateway ``https://{workspace}.{region}.maas.aliyuncs.com``.
    region : str
        Gateway region. Defaults to ``cn-beijing``.
    base_url : str | None
        Replaces the SDK-derived address when set. The value is the API root,
        including its path, and is not given an extra ``/api/v1``.
    request_timeout : float
        Timeout in seconds for one HTTP request. Defaults to 60.
    """

    api_key: str | None = Field(
        default=None, repr=False, description="DashScope API key."
    )
    workspace: str | None = Field(
        default=None,
        description="Bailian workspace id used in the gateway host.",
    )
    region: str = Field(
        default=DEFAULT_REGION,
        description="DashScope gateway region.",
    )
    base_url: str | None = Field(
        default=None,
        description="API root that replaces the SDK-derived address.",
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        gt=0,
        allow_inf_nan=False,
        description="Timeout in seconds for one HTTP request.",
    )
    _sdk_client: dashscope.Client | None = PrivateAttr(default=None)
    _closed: bool = PrivateAttr(default=False)

    def speech(
        self,
        model: str,
        *,
        voice: str | None = None,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> SpeechSynthesisModel:
        """Bind a speech model that returns audio without creating a job.

        An explicit voice overrides ``model_parameters["voice"]``. Call-time
        parameters override the resulting binding.
        """
        bound = dict(model_parameters or {})
        if voice is not None:
            bound["voice"] = voice
        return _DashScopeSpeechSynthesisModel(
            provider=self,
            model=model,
            model_parameters=bound,
        )

    def transcription(
        self,
        model: str,
        *,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> TranscriptionModel:
        """Bind a file-transcription model with resumable provider jobs."""
        return _DashScopeTranscriptionModel(
            provider=self,
            model=model,
            model_parameters=dict(model_parameters or {}),
        )

    def image(
        self,
        model: str,
        *,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> ImageGenerationModel:
        """Bind an image model using the asynchronous generation protocol."""
        return _DashScopeImageGenerationModel(
            provider=self,
            model=model,
            model_parameters=dict(model_parameters or {}),
        )

    def video(
        self,
        model: str,
        *,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> VideoGenerationModel:
        """Bind a video model with resumable provider jobs."""
        return _DashScopeVideoGenerationModel(
            provider=self,
            model=model,
            model_parameters=dict(model_parameters or {}),
        )

    @override
    def open(self) -> None:
        """Open the shared client before using any bound model."""
        if self._closed:
            msg = "The DashScope resource is closed."
            raise RuntimeError(msg)
        if self._sdk_client is None:
            api_key = self.api_key or os.environ.get("DASHSCOPE_API_KEY")
            if not api_key:
                msg = "Pass api_key or set DASHSCOPE_API_KEY before opening DashScope."
                raise ValueError(msg)
            self._sdk_client = create_client(
                api_key=api_key,
                base_url=self.base_url,
                workspace=self.workspace,
                region=self.region,
                timeout=self.request_timeout,
            )

    @override
    def close(self) -> None:
        """End the provider lifetime and close its client once, if opened."""
        if not self._closed:
            self._closed = True
            client = self._sdk_client
            self._sdk_client = None
            if client is not None:
                client.close()

    def _client(self) -> dashscope.Client:
        """Return the shared client of an open resource."""
        if self._closed:
            msg = "The DashScope resource is closed."
            raise RuntimeError(msg)
        if self._sdk_client is None:
            msg = "The DashScope resource is not open. Call open() before using it."
            raise RuntimeError(msg)
        return self._sdk_client


class _DashScopeModel(BaseModel):
    model_parameters: dict[str, Any] = Field(
        default_factory=dict,
        description="Bailian model parameters overridden by call-time model_parameters.",
    )
    _provider: DashScopeMediaModelProvider = PrivateAttr()

    def __init__(self, *, provider: DashScopeMediaModelProvider, **kwargs: Any) -> None:
        if provider._closed:
            msg = "The DashScope resource is closed."
            raise RuntimeError(msg)
        super().__init__(**kwargs)
        self.model_parameters = deepcopy(self.model_parameters)
        self._provider = provider

    def _client(self) -> dashscope.Client:
        return self._provider._client()


class _DashScopeSpeechSynthesisModel(_DashScopeModel, SpeechSynthesisModel):
    def synthesize(
        self,
        text: str,
        *,
        voice: str | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[AudioBlock]:
        """Synthesize text. The SDK call is a single HTTP POST.

        ``timeout`` is the timeout for that request. None uses
        the provider's ``request_timeout``.
        """
        parameters = {**self.model_parameters, **(model_parameters or {})}
        if voice is not None:
            parameters["voice"] = voice
        resolved_voice = parameters.pop("voice", None)
        if not isinstance(resolved_voice, str) or not resolved_voice:
            msg = "Speech synthesis requires a voice."
            raise ValueError(msg)
        audio_format = parameters.pop("format", None)
        sample_rate = parameters.pop("sample_rate", None)
        language_type = parameters.pop("language_type", None)
        if not isinstance(text, str):
            msg = "Speech synthesis requires a string."
            raise TypeError(msg)
        if not text:
            msg = "Speech synthesis requires non-empty text."
            raise ValueError(msg)
        timeout = validate_request_timeout(timeout)
        synthesized = self._client().audio.tts.http.synthesize(
            text,
            model=self.model,
            voice=resolved_voice,
            format=audio_format,
            sample_rate=sample_rate,
            language_type=language_type,
            parameters=parameters or None,
            timeout=timeout,
        )
        audio_url = synthesized.audio_url
        if not isinstance(audio_url, str) or not audio_url:
            msg = "Speech synthesis did not return an audio URL."
            raise ValueError(msg)
        expires_at = synthesized.expires_at
        if isinstance(expires_at, int) and not isinstance(expires_at, bool):
            expires_at_ms = expires_at * 1000
        else:
            expires_at_ms = _result_expiry_ms(audio_url)
        return MediaResult[AudioBlock](
            content=[
                AudioBlock.from_url(
                    _audio_media_type(audio_format),
                    audio_url,
                )
            ],
            expires_at_ms=expires_at_ms,
            usage=usage_counters(synthesized.usage),
            request_id=synthesized.request_id or None,
        )


class _DashScopeAsyncModel(
    _DashScopeModel,
    AsyncJobModel[ResultT],
    Generic[ResultT],
):
    _state_type: ClassVar[type[MediaJobState]]

    def _request_json(
        self,
        method: str,
        path: str,
        *,
        body: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> dict[str, Any]:
        """Send one JSON request and return its object body.

        ``path`` is relative to the client API root. Authentication, the
        workspace gateway, and the request timeout come from the SDK client.
        A failed status or a Bailian business ``code`` raises the SDK error
        for that code.

        Args:
            method: HTTP method.
            path: Path under the API root, without a leading host.
            body: JSON request body. None sends no body.
            headers: Extra headers. These override the client headers.
        """
        response = self._client().transport.request(
            method,
            path,
            json=body,
            headers=headers,
        )
        check_response(response)
        return response_json(response)

    def _provider_name(self) -> str:
        return _PROVIDER

    @abstractmethod
    def _media_result(self, snapshot: "_TaskSnapshot") -> ResultT:
        """Decode the completed task using this capability's result contract."""

    def _normalize_snapshot(self, snapshot: "_TaskSnapshot") -> "_TaskSnapshot":
        return snapshot

    @override
    def fetch(self, job: MediaJob) -> MediaJobState[ResultT]:
        """Query the common task endpoint and decode a completed result."""
        self._check_job(job)
        snapshot = self._fetch_task(job)
        if snapshot.status is MediaJobStatus.SUCCEEDED:
            return self._state_type(
                job=job,
                status=snapshot.status,
                result=self._media_result(snapshot),
            )
        return self._state_type(
            job=job,
            status=snapshot.status,
            error_code=None if snapshot.code is None else str(snapshot.code),
            error_message=None if snapshot.message is None else str(snapshot.message),
        )

    @override
    def cancel(self, job: MediaJob) -> None:
        """Cancel a task that is still pending.

        Transcription, video generation, and image generation all use
        ``POST /tasks/{id}/cancel``. Any other status raises ``MediaJobError``.
        """
        self._check_job(job)
        task_id = quote(job.job_id, safe="")
        try:
            self._request_json("POST", f"/tasks/{task_id}/cancel")
        except DashScopeError as exc:
            if exc.code != "UnsupportedOperation":
                raise
            self._raise_cancel_failure(job, exc)

    def _submitted_job(self, task_id: str) -> MediaJob:
        submitted_at_ms = int(time.time() * 1000)
        return MediaJob(
            provider=self._provider_name(),
            model=self.model,
            task=self._task.value,
            job_id=task_id,
            submitted_at_ms=submitted_at_ms,
            expires_at_ms=submitted_at_ms + _DAY_MS,
        )

    def _fetch_task(self, job: MediaJob) -> "_TaskSnapshot":
        """Read and normalize one task observation from DashScope."""
        task_id = quote(job.job_id, safe="")
        try:
            body = self._request_json("GET", f"/tasks/{task_id}")
        except DashScopeError as exc:
            snapshot = _task_snapshot_if_failed(exc)
            if snapshot is None:
                raise
        else:
            snapshot = _task_snapshot(body)
        return self._normalize_snapshot(snapshot)

    def _raise_cancel_failure(self, job: MediaJob, exc: DashScopeError) -> None:
        """Raise ``MediaJobError`` for a cancel the provider rejected."""
        try:
            snapshot = self._fetch_task(job)
        except (DashScopeError, httpx.HTTPError) as query_error:
            reason = (
                "only a pending task can be canceled, and its status could not be read"
            )
            raise MediaJobError(
                job, MediaJobStatus.UNKNOWN, exc.code, reason
            ) from query_error
        status = snapshot.status
        reason = f"only a pending task can be canceled, got {status.value}"
        raise MediaJobError(job, status, exc.code, reason) from exc


class _DashScopeTranscriptionModel(
    _DashScopeAsyncModel[MediaResult[TextBlock]],
    TranscriptionModel,
):
    _state_type = MediaJobState[MediaResult[TextBlock]]

    @override
    def transcribe(
        self,
        audio: AudioBlock | VideoBlock,
        *,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[TextBlock]:
        """Submit one recording and wait for its transcription."""
        return self._submit_and_wait(
            audio, model_parameters=model_parameters, timeout=timeout
        )

    def submit(
        self,
        audio: AudioBlock | VideoBlock,
        *,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> MediaJob:
        """Submit one recording using the file-transcription protocol."""
        file_url = _transcription_file_url(audio)
        parameters = {**self.model_parameters, **(model_parameters or {})}
        created = self._client().audio.asr.jobs.create(
            model=self.model,
            file_urls=[file_url],
            parameters=parameters,
        )
        return self._submitted_job(created.task_id)

    def _normalize_snapshot(self, snapshot: "_TaskSnapshot") -> "_TaskSnapshot":
        """Treat a failed file as a failed transcription task."""
        for item in snapshot.output.get("results") or []:
            if not isinstance(item, dict):
                continue
            if str(item.get("subtask_status", "")).upper() == "FAILED":
                return replace(
                    snapshot,
                    status=MediaJobStatus.FAILED,
                    code=item.get("code") or snapshot.code,
                    message=item.get("message") or snapshot.message,
                )
        return snapshot

    def _media_result(self, snapshot: "_TaskSnapshot") -> MediaResult[TextBlock]:
        url = _transcription_result_url(snapshot.output)
        # The signed result URL uses a separate request without SDK credentials.
        response = httpx.get(url, timeout=self._provider.request_timeout)
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict):
            msg = "A transcription result must be a JSON object."
            raise TypeError(msg)
        return _task_result(
            MediaResult[TextBlock],
            snapshot,
            _transcript_blocks(payload),
            metadata={"transcription": payload},
        )


class _DashScopeImageGenerationModel(
    _DashScopeAsyncModel[MediaResult[ImageBlock]],
    ImageGenerationModel,
):
    _state_type = MediaJobState[MediaResult[ImageBlock]]

    @override
    def generate(
        self,
        prompt: str,
        *,
        images: Sequence[ImageBlock] | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[ImageBlock]:
        """Submit an image generation task and wait for its result."""
        return self._submit_and_wait(
            prompt, images=images, model_parameters=model_parameters, timeout=timeout
        )

    def submit(
        self,
        prompt: str,
        *,
        images: Sequence[ImageBlock] | None = None,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> MediaJob:
        """Submit a prompt and optional reference images for image generation."""
        parameters = {**self.model_parameters, **(model_parameters or {})}
        body: dict[str, Any] = {
            "model": self.model,
            "input": {
                "messages": [
                    {
                        "role": "user",
                        "content": _image_generation_content(prompt, images),
                    }
                ]
            },
        }
        if parameters:
            body["parameters"] = parameters
        created = self._request_json(
            "POST",
            "/services/aigc/image-generation/generation",
            body=body,
            headers={"X-DashScope-Async": "enable"},
        )
        task_id = _task_id(created)
        if task_id is None:
            msg = "Image generation did not return a task id."
            raise ValueError(msg)
        return self._submitted_job(task_id)

    def _media_result(self, snapshot: "_TaskSnapshot") -> MediaResult[ImageBlock]:
        urls = _generated_image_urls(snapshot.output)
        if not urls:
            msg = "A succeeded image task did not return an image URL."
            raise ValueError(msg)
        expiries = [
            expiry for url in urls if (expiry := _result_expiry_ms(url)) is not None
        ]
        return _task_result(
            MediaResult[ImageBlock],
            snapshot,
            [ImageBlock.from_url("image/png", url) for url in urls],
            expires_at_ms=min(expiries) if len(expiries) == len(urls) else None,
        )


class _DashScopeVideoGenerationModel(
    _DashScopeAsyncModel[MediaResult[VideoBlock]],
    VideoGenerationModel,
):
    _state_type = MediaJobState[MediaResult[VideoBlock]]

    @override
    def generate(
        self,
        prompt: str = "",
        *,
        media: Sequence[ImageBlock | AudioBlock | VideoBlock | DocumentBlock]
        | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[VideoBlock]:
        """Submit a video generation task and wait for its result."""
        return self._submit_and_wait(
            prompt, media=media, model_parameters=model_parameters, timeout=timeout
        )

    def submit(
        self,
        prompt: str = "",
        *,
        media: Sequence[ImageBlock | AudioBlock | VideoBlock | DocumentBlock]
        | None = None,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> MediaJob:
        """Submit a prompt and ordered media inputs for video generation."""
        parameters = {**self.model_parameters, **(model_parameters or {})}
        prompt, media_parts = _video_input(
            prompt, media, parameters.pop("video_media_roles", None)
        )
        created = self._client().videos.jobs.create(
            model=self.model,
            prompt=prompt,
            media=media_parts,
            parameters=parameters or None,
        )
        return self._submitted_job(created.task_id)

    def _media_result(self, snapshot: "_TaskSnapshot") -> MediaResult[VideoBlock]:
        url = snapshot.output.get("video_url")
        if not isinstance(url, str) or not url:
            msg = "A succeeded video task did not return a video URL."
            raise ValueError(msg)
        return _task_result(
            MediaResult[VideoBlock],
            snapshot,
            [VideoBlock.from_url("video/mp4", url)],
            expires_at_ms=_result_expiry_ms(url),
        )


@dataclass(frozen=True)
class _TaskSnapshot:
    """Normalized status and result fields from one task query."""

    status: MediaJobStatus
    output: Mapping[str, Any]
    code: str | None
    message: str | None
    usage: Any
    request_id: str | None


def _task_snapshot(body: Mapping[str, Any]) -> _TaskSnapshot:
    """Read one task response body into a snapshot."""
    output = body.get("output")
    output = output if isinstance(output, dict) else {}
    status = output.get("task_status", body.get("task_status"))
    return _TaskSnapshot(
        status=_media_job_status(status),
        output=output,
        code=body.get("code", output.get("code")),
        message=body.get("message", output.get("message")),
        usage=body.get("usage"),
        request_id=body.get("request_id"),
    )


def _task_snapshot_if_failed(exc: DashScopeError) -> _TaskSnapshot | None:
    """Read a snapshot when ``exc`` reports that the task failed.

    Connection, authentication, and other query failures carry no failed
    ``task_status``. Those stay exceptions.
    """
    if not isinstance(exc.body, dict):
        return None
    snapshot = _task_snapshot(exc.body)
    if snapshot.status is not MediaJobStatus.FAILED:
        return None
    return replace(
        snapshot,
        code=exc.code,
        message=exc.message,
        request_id=exc.request_id or snapshot.request_id,
    )


def _task_id(body: Mapping[str, Any]) -> str | None:
    output = body.get("output")
    output = output if isinstance(output, dict) else {}
    task_id = output.get("task_id", body.get("task_id"))
    if not isinstance(task_id, str) or not task_id:
        return None
    return task_id


def _media_job_status(status: object) -> MediaJobStatus:
    if not isinstance(status, str):
        return MediaJobStatus.UNKNOWN
    return _TASK_STATUS.get(status.upper(), MediaJobStatus.UNKNOWN)


def _task_result(
    result_type: type[ResultT],
    snapshot: _TaskSnapshot,
    content: Sequence[DataContentBlock],
    *,
    expires_at_ms: int | None = None,
    metadata: dict[str, Any] | None = None,
) -> ResultT:
    return result_type(
        content=list(content),
        expires_at_ms=expires_at_ms,
        usage=usage_counters(snapshot.usage),
        request_id=snapshot.request_id,
        metadata=metadata or {},
    )


def _transcription_file_url(block: AudioBlock | VideoBlock) -> str:
    """The single http or https file accepted by one transcription request."""
    if not isinstance(block, AudioBlock | VideoBlock):
        msg = "Transcription requires an AudioBlock or VideoBlock."
        raise TypeError(msg)
    if not isinstance(block.source, UrlSource):
        raise UnsupportedContentBlockError.for_block(
            _PROVIDER_LABEL, block, "transcription accepts an http or https URL"
        )
    if urlparse(block.source.url).scheme not in ("http", "https"):
        msg = "Transcription accepts only an http or https URL."
        raise ValueError(msg)
    return block.source.url


def _transcription_result_url(output: Mapping[str, Any]) -> str:
    for item in output.get("results") or []:
        if isinstance(item, dict) and item.get("transcription_url"):
            return str(item["transcription_url"])
    msg = "A succeeded transcription task did not return a result URL."
    raise ValueError(msg)


def _transcript_blocks(payload: Mapping[str, Any]) -> list[TextBlock]:
    return [
        TextBlock(text=str(item.get("text") or ""))
        for item in payload.get("transcripts") or []
        if isinstance(item, dict)
    ]


def _video_input(
    prompt: str,
    media: Sequence[ImageBlock | AudioBlock | VideoBlock | DocumentBlock] | None,
    roles: Sequence[str] | None,
) -> tuple[str | None, list[dict[str, str]] | None]:
    """Return the prompt and ``media`` array for any video model.

    The model name is forwarded unchanged. Bailian decides whether that model
    accepts the prompt and media roles.
    """
    if not isinstance(prompt, str):
        msg = "Video generation requires a string prompt."
        raise TypeError(msg)
    if media is not None and (
        not isinstance(media, Sequence)
        or isinstance(media, str | bytes | bytearray)
        or any(
            not isinstance(block, ImageBlock | AudioBlock | VideoBlock | DocumentBlock)
            for block in media
        )
    ):
        msg = "Video generation media must be a sequence of image, audio, video or document blocks."
        raise TypeError(msg)
    prompt = prompt or None
    if prompt is None and not media:
        msg = "Video generation requires a prompt or at least one media input."
        raise ValueError(msg)
    if not media:
        if roles:
            msg = "video_media_roles requires media blocks."
            raise ValueError(msg)
        return prompt, None
    if roles is None:
        raise ValueError(_ROLES_REQUIRED)
    if (
        isinstance(roles, str | bytes | bytearray)
        or not isinstance(roles, Sequence)
        or any(not isinstance(role, str) or not role.strip() for role in roles)
    ):
        msg = "video_media_roles must be an ordered sequence of non-empty role names."
        raise TypeError(msg)
    if len(roles) != len(media):
        msg = "video_media_roles must have one entry for each media block."
        raise ValueError(msg)
    return prompt, [
        {"type": role, "url": media_source_url(block)}
        for role, block in zip(roles, media, strict=True)
    ]


def _image_generation_content(
    prompt: str, images: Sequence[ImageBlock] | None
) -> list[dict[str, str]]:
    """Return one user message content array for async image generation."""
    if not isinstance(prompt, str):
        msg = "Image generation requires a string prompt."
        raise TypeError(msg)
    if not prompt:
        msg = "Image generation requires a non-empty prompt."
        raise ValueError(msg)
    if images is not None and (
        not isinstance(images, Sequence)
        or isinstance(images, str | bytes | bytearray)
        or any(not isinstance(image, ImageBlock) for image in images)
    ):
        msg = "Image generation images must be a sequence of ImageBlock values."
        raise TypeError(msg)
    parts = [{"image": media_source_url(image)} for image in images or ()]
    parts.append({"text": prompt})
    return parts


def _generated_image_urls(output: Mapping[str, Any]) -> list[str]:
    urls: list[str] = []
    for choice in output.get("choices") or []:
        if not isinstance(choice, dict):
            continue
        message = choice.get("message") or {}
        parts = message.get("content") or []
        if isinstance(parts, dict):
            parts = [parts]
        urls.extend(
            str(part["image"])
            for part in parts
            if isinstance(part, dict) and part.get("image")
        )
    return urls


def _audio_media_type(audio_format: str | None) -> str:
    name = audio_format or _DEFAULT_AUDIO_FORMAT
    return _AUDIO_MEDIA_TYPES.get(name, f"audio/{name}")


def _result_expiry_ms(url: str) -> int | None:
    expires = parse_qs(urlparse(url).query).get("Expires")
    if not expires:
        return None
    try:
        return int(expires[0]) * 1000
    except ValueError:
        return None
