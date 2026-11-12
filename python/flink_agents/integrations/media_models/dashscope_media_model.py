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
import os
import time
from dataclasses import dataclass, replace
from typing import TYPE_CHECKING, Any, Mapping, Sequence
from urllib.parse import parse_qs, urlparse

import dashscope
import httpx
from dashscope.errors import DashScopeError, check_response, response_json
from pydantic import Field, PrivateAttr
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
from flink_agents.api.media_models.media_model import (
    BaseMediaModel,
    MediaJob,
    MediaJobError,
    MediaJobState,
    MediaJobStatus,
    MediaResult,
    MediaTask,
)
from flink_agents.integrations.dashscope_utils import (
    create_client,
    media_source_url,
    usage_counters,
)

if TYPE_CHECKING:
    from flink_agents.api.chat_message import MediaBlock

DEFAULT_REQUEST_TIMEOUT = 60.0
DEFAULT_REGION = "cn-beijing"
_PROVIDER = "dashscope"
_DAY_MS = 24 * 60 * 60 * 1000
_PROVIDER_LABEL = "DashScope"
_ROLES_REQUIRED = "video_media_roles is required when content contains media blocks."
_AUDIO_MEDIA_TYPES = {
    "mp3": "audio/mpeg",
    "wav": "audio/wav",
    "opus": "audio/ogg",
    "pcm": "audio/pcm",
}
_DEFAULT_AUDIO_FORMAT = "mp3"
_ASYNC_TASKS = frozenset(
    {MediaTask.TRANSCRIPTION, MediaTask.VIDEO_GENERATION, MediaTask.IMAGE_GENERATION}
)
_TASK_STATUS = {
    "PENDING": MediaJobStatus.PENDING,
    "SUSPENDED": MediaJobStatus.PENDING,
    "RUNNING": MediaJobStatus.RUNNING,
    "SUCCEEDED": MediaJobStatus.SUCCEEDED,
    "FAILED": MediaJobStatus.FAILED,
    "CANCELED": MediaJobStatus.CANCELED,
}


class DashScopeMediaModel(BaseMediaModel):
    """DashScope media generation, transcription, and speech synthesis.

    One instance binds a single ``model`` and ``task``. This layer owns the
    shared configuration, the DashScope SDK client, the HTTP call used where
    the SDK has no matching method, and the request for each task.

    The client is built from these fields. ``~/.dashscope/config.toml`` is not
    read. An unset ``workspace`` or ``base_url`` still follows the SDK rules,
    including ``DASHSCOPE_WORKSPACE`` and ``DASHSCOPE_BASE_URL``. Automatic
    retries are disabled for SDK requests.

    Attributes:
    ----------
    api_key : str
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
    parameters : dict
        Bailian ``parameters`` object. Call-time kwargs override same-named
        keys.
    voice : str | None
        Speech-synthesis voice. A call may override it with ``voice``.
    """

    api_key: str = Field(repr=False, description="DashScope API key.")
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
        description="Timeout in seconds for one HTTP request.",
    )
    parameters: dict[str, Any] = Field(
        default_factory=dict,
        description="Bailian parameters overridden by call-time kwargs.",
    )
    voice: str | None = Field(
        default=None,
        description="Speech-synthesis voice.",
    )

    _sdk_client: dashscope.Client | None = PrivateAttr(default=None)

    def __init__(
        self,
        model: str,
        task: MediaTask | str,
        api_key: str | None = None,
        workspace: str | None = None,
        region: str | None = None,
        base_url: str | None = None,
        request_timeout: float = DEFAULT_REQUEST_TIMEOUT,
        parameters: Mapping[str, Any] | None = None,
        voice: str | None = None,
        **kwargs: Any,
    ) -> None:
        """Resolve the API key, then store the remaining configuration."""
        resolved_api_key = api_key or os.environ.get("DASHSCOPE_API_KEY")
        if not resolved_api_key:
            msg = (
                "DashScope API key is not provided. "
                "Please pass it as an argument or set the "
                "'DASHSCOPE_API_KEY' environment variable."
            )
            raise ValueError(msg)
        super().__init__(
            model=model,
            task=task,
            api_key=resolved_api_key,
            workspace=workspace,
            region=region or DEFAULT_REGION,
            base_url=base_url,
            request_timeout=request_timeout,
            parameters=dict(parameters or {}),
            voice=voice,
            **kwargs,
        )

    @override
    def _provider_name(self) -> str:
        return _PROVIDER

    @override
    def generate(
        self,
        content: Sequence[DataContentBlock],
        *,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> MediaResult:
        """Return the finished result for the configured task.

        Transcription, video generation, and image generation submit a task
        and wait for it. ``timeout`` is that wait; None uses ``wait_timeout``.
        Speech synthesis is one request and returns the audio URL directly.
        Its ``timeout`` bounds that request; None uses ``request_timeout``.
        """
        if self.task is MediaTask.SPEECH_SYNTHESIS:
            return self._generate_speech(content, timeout=timeout, **kwargs)
        wait_timeout = self._resolve_wait_timeout(timeout)
        job = self.submit(content, **kwargs)
        return self.wait(job, timeout=wait_timeout)

    @override
    def supports_async_task(self) -> bool:
        """Whether the configured task supports submitted jobs."""
        return self.task in _ASYNC_TASKS

    def _require_async_task(self) -> None:
        if not self.supports_async_task():
            msg = f"DashScope task {self.task.value!r} does not support asynchronous jobs."
            raise NotImplementedError(msg)

    @override
    def submit(self, content: Sequence[DataContentBlock], **kwargs: Any) -> MediaJob:
        """Submit one asynchronous task and return its handle.

        Raises:
            NotImplementedError: When ``task`` has no submit path yet.
        """
        self._require_async_task()
        parameters = {**self.parameters, **kwargs}
        if self.task is MediaTask.TRANSCRIPTION:
            task_id = self._submit_transcription(content, parameters)
        elif self.task is MediaTask.VIDEO_GENERATION:
            task_id = self._submit_video(content, parameters)
        elif self.task is MediaTask.IMAGE_GENERATION:
            task_id = self._submit_image(content, parameters)
        else:
            msg = f"Task creation is not implemented for {self.task.value!r}."
            raise NotImplementedError(msg)
        return self._submitted_job(task_id)

    @override
    def fetch(self, job: MediaJob) -> MediaJobState:
        """Query one task and return its current observation.

        Transcription, video generation, and image generation all use
        ``GET /tasks/{id}``. A task that has not succeeded is returned from
        that status. A succeeded task is read into content blocks and stored
        in one ``MediaResult``.

        Transcription downloads its result file, and treats a failed file
        inside a succeeded task as ``FAILED``. Video generation reads
        ``video_url``. Image generation reads the image URLs from the task
        choices. A query error that does not carry a failed task status is
        raised.
        """
        self._check_job(job)
        self._require_async_task()
        snapshot = self._fetch_task(job)
        if snapshot.status is MediaJobStatus.SUCCEEDED:
            return MediaJobState(
                job=job,
                status=snapshot.status,
                result=self._media_result(snapshot),
            )
        return MediaJobState(
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
        self._require_async_task()
        try:
            self._request_json("POST", f"/tasks/{job.job_id}/cancel")
        except DashScopeError as exc:
            if exc.code != "UnsupportedOperation":
                raise
            self._raise_cancel_failure(job, exc)

    @override
    def close(self) -> None:
        """Close the SDK client if it was created."""
        client = self._sdk_client
        self._sdk_client = None
        if client is not None:
            client.close()

    def _generate_speech(
        self,
        content: Sequence[DataContentBlock],
        *,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> MediaResult:
        """Synthesize one text block. The SDK call is a single HTTP POST.

        ``timeout`` is the timeout for that request. None uses
        ``request_timeout``.
        """
        parameters = {**self.parameters, **kwargs}
        voice = parameters.pop("voice", None) or self.voice
        if not isinstance(voice, str) or not voice:
            msg = "Speech synthesis requires a voice."
            raise ValueError(msg)
        audio_format = parameters.pop("format", None)
        sample_rate = parameters.pop("sample_rate", None)
        language_type = parameters.pop("language_type", None)
        text = _speech_text(content)
        synthesized = self._client().audio.tts.http.synthesize(
            text,
            model=self.model,
            voice=voice,
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
        if isinstance(expires_at, int):
            expires_at_ms = expires_at * 1000
        else:
            expires_at_ms = _result_expiry_ms(audio_url)
        return MediaResult(
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

    def _submitted_job(self, task_id: str) -> MediaJob:
        submitted_at_ms = int(time.time() * 1000)
        return MediaJob(
            provider=self._provider_name(),
            model=self.model,
            task=self.task,
            job_id=task_id,
            submitted_at_ms=submitted_at_ms,
            expires_at_ms=submitted_at_ms + _DAY_MS,
        )

    def _fetch_task(self, job: MediaJob) -> "_TaskSnapshot":
        """Read and normalize one task observation from DashScope."""
        try:
            body = self._request_json("GET", f"/tasks/{job.job_id}")
        except DashScopeError as exc:
            snapshot = _task_snapshot_if_failed(exc)
            if snapshot is None:
                raise
        else:
            snapshot = _task_snapshot(body)
        if self.task is MediaTask.TRANSCRIPTION:
            return self._transcription_snapshot(snapshot)
        return snapshot

    def _media_result(self, snapshot: "_TaskSnapshot") -> MediaResult:
        """Read the result of a succeeded task."""
        if self.task is MediaTask.TRANSCRIPTION:
            return self._read_transcription_result(snapshot)
        if self.task is MediaTask.VIDEO_GENERATION:
            return self._read_video_result(snapshot)
        if self.task is MediaTask.IMAGE_GENERATION:
            return self._read_image_result(snapshot)
        msg = f"Task result parsing is not implemented for {self.task.value!r}."
        raise NotImplementedError(msg)

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

    def _client(self) -> dashscope.Client:
        """Return the SDK client for this resource, creating it once."""
        if self._sdk_client is None:
            self._sdk_client = create_client(
                api_key=self.api_key,
                base_url=self.base_url,
                workspace=self.workspace,
                region=self.region,
                timeout=self.request_timeout,
            )
        return self._sdk_client

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

    def _submit_transcription(
        self, content: Sequence[DataContentBlock], parameters: dict[str, Any]
    ) -> str:
        file_url = _transcription_file_url(content)
        created = self._client().audio.asr.jobs.create(
            model=self.model,
            file_urls=[file_url],
            parameters=parameters,
        )
        return created.task_id

    @staticmethod
    def _transcription_snapshot(snapshot: "_TaskSnapshot") -> "_TaskSnapshot":
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

    def _read_transcription_result(self, snapshot: "_TaskSnapshot") -> MediaResult:
        url = _transcription_result_url(snapshot.output)
        # The signed result URL uses a separate request without SDK credentials.
        response = httpx.get(url, timeout=self.request_timeout)
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict):
            msg = "A transcription result must be a JSON object."
            raise TypeError(msg)
        return _task_result(
            snapshot,
            _transcript_blocks(payload),
            metadata={"transcription": payload},
        )

    def _submit_video(
        self, content: Sequence[DataContentBlock], parameters: dict[str, Any]
    ) -> str:
        prompt, media = _video_input(content, parameters.pop("video_media_roles", None))
        created = self._client().videos.jobs.create(
            model=self.model,
            prompt=prompt,
            media=media,
            parameters=parameters or None,
        )
        return created.task_id

    def _read_video_result(self, snapshot: "_TaskSnapshot") -> MediaResult:
        url = snapshot.output.get("video_url")
        if not isinstance(url, str) or not url:
            msg = "A succeeded video task did not return a video URL."
            raise ValueError(msg)
        return _task_result(
            snapshot,
            [VideoBlock.from_url("video/mp4", url)],
            expires_at_ms=_result_expiry_ms(url),
        )

    def _submit_image(
        self, content: Sequence[DataContentBlock], parameters: dict[str, Any]
    ) -> str:
        body: dict[str, Any] = {
            "model": self.model,
            "input": {
                "messages": [
                    {"role": "user", "content": _image_generation_content(content)}
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
        return task_id

    def _read_image_result(self, snapshot: "_TaskSnapshot") -> MediaResult:
        urls = _generated_image_urls(snapshot.output)
        if not urls:
            msg = "A succeeded image task did not return an image URL."
            raise ValueError(msg)
        expiries = [
            expiry for url in urls if (expiry := _result_expiry_ms(url)) is not None
        ]
        return _task_result(
            snapshot,
            [ImageBlock.from_url("image/png", url) for url in urls],
            expires_at_ms=min(expiries) if len(expiries) == len(urls) else None,
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
    snapshot: _TaskSnapshot,
    content: Sequence[DataContentBlock],
    *,
    expires_at_ms: int | None = None,
    metadata: dict[str, Any] | None = None,
) -> MediaResult:
    return MediaResult(
        content=list(content),
        expires_at_ms=expires_at_ms,
        usage=usage_counters(snapshot.usage),
        request_id=snapshot.request_id,
        metadata=metadata or {},
    )


def _optional_prompt(texts: Sequence[TextBlock], *, task: str) -> str | None:
    if len(texts) > 1:
        msg = f"{task} accepts at most one text block."
        raise ValueError(msg)
    if not texts:
        return None
    return texts[0].text


def _transcription_file_url(content: Sequence[DataContentBlock]) -> str:
    """The single http or https file accepted by one transcription request."""
    if len(content) != 1:
        msg = "Transcription accepts exactly one audio or video block."
        raise ValueError(msg)
    block = content[0]
    provider = _PROVIDER_LABEL
    if not isinstance(block, AudioBlock | VideoBlock):
        reason = "transcription accepts one audio or video URL"
        raise UnsupportedContentBlockError.for_block(provider, block, reason)
    if not isinstance(block.source, UrlSource):
        reason = "transcription accepts an http or https URL"
        raise UnsupportedContentBlockError.for_block(provider, block, reason)
    scheme = urlparse(block.source.url).scheme
    if scheme not in ("http", "https"):
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
    content: Sequence[DataContentBlock],
    roles: Sequence[str] | None,
) -> tuple[str | None, list[dict[str, str]] | None]:
    """Return the prompt and ``media`` array for any video model.

    The model name is forwarded unchanged. Bailian decides whether that model
    accepts the prompt and media roles.
    """
    texts: list[TextBlock] = []
    media: list[MediaBlock] = []
    for block in content:
        if isinstance(block, TextBlock):
            texts.append(block)
        elif isinstance(block, ImageBlock | AudioBlock | VideoBlock | DocumentBlock):
            media.append(block)
        else:
            reason = (
                "video generation accepts text, image, audio, video, "
                "and document blocks"
            )
            raise UnsupportedContentBlockError.for_block(_PROVIDER_LABEL, block, reason)
    prompt = _optional_prompt(texts, task="Video generation")
    if prompt is None and not media:
        msg = "Video generation requires a text block or at least one media block."
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
    content: Sequence[DataContentBlock],
) -> list[dict[str, str]]:
    """Return one user message content array for async image generation."""
    texts: list[TextBlock] = []
    images: list[ImageBlock] = []
    for block in content:
        if isinstance(block, TextBlock):
            texts.append(block)
        elif isinstance(block, ImageBlock):
            images.append(block)
        else:
            reason = "image generation accepts text and image blocks"
            raise UnsupportedContentBlockError.for_block(_PROVIDER_LABEL, block, reason)
    prompt = _optional_prompt(texts, task="Image generation")
    if not prompt:
        msg = "Image generation requires a text block."
        raise ValueError(msg)
    parts = [{"image": media_source_url(image)} for image in images]
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


def _speech_text(content: Sequence[DataContentBlock]) -> str:
    texts: list[TextBlock] = []
    for block in content:
        if isinstance(block, TextBlock):
            texts.append(block)
        else:
            reason = "speech synthesis accepts one text block"
            raise UnsupportedContentBlockError.for_block(_PROVIDER_LABEL, block, reason)
    text = _optional_prompt(texts, task="Speech synthesis")
    if not text:
        msg = "Speech synthesis requires a text block."
        raise ValueError(msg)
    return text


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
