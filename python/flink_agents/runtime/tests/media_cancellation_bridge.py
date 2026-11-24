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
import threading
import time
from collections.abc import Mapping, Sequence
from typing import Any

from pydantic import PrivateAttr
from typing_extensions import override

from flink_agents.api.chat_message import AudioBlock, ImageBlock
from flink_agents.api.media import (
    AsyncJobModel,
    BaseImageGenerationModelSetup,
    BaseMediaModelConnection,
    BaseSpeechSynthesisModelSetup,
    MediaJob,
    MediaJobState,
    MediaJobStatus,
    MediaResult,
)
from flink_agents.api.resource import ResourceType


class RecordingMediaModelConnection(BaseMediaModelConnection):
    """Minimal test connection recording submit, fetch, and cancel counts across Pemja."""

    connection_tag: str = "recording"
    _lock: threading.Lock = PrivateAttr(default_factory=threading.Lock)
    _submit_count: int = PrivateAttr(default=0)
    _fetch_count: int = PrivateAttr(default=0)
    _cancel_count: int = PrivateAttr(default=0)
    _fetch_outcome: str = PrivateAttr(default="pending")
    _fetch_delay: float = PrivateAttr(default=0.0)

    def _record_submit(self) -> MediaJob:
        with self._lock:
            self._submit_count += 1
            job_id = f"job-{self._submit_count}"
        return MediaJob(job_id=job_id, submitted_at_ms=1)

    def _record_fetch(self, job: MediaJob) -> MediaJobState[MediaResult[ImageBlock]]:
        with self._lock:
            self._fetch_count += 1
            outcome = self._fetch_outcome
            delay = self._fetch_delay
        if delay > 0:
            time.sleep(delay)
        if outcome == "failed":
            return MediaJobState[MediaResult[ImageBlock]](
                job=job,
                status=MediaJobStatus.FAILED,
                error_code="RemoteError",
                error_message="remote job failed",
            )
        if outcome == "succeeded":
            return MediaJobState[MediaResult[ImageBlock]](
                job=job,
                status=MediaJobStatus.SUCCEEDED,
                result=MediaResult[ImageBlock](
                    content=[
                        ImageBlock.from_url(
                            "image/png",
                            f"https://example.test/{self.connection_tag}/{job.job_id}.png",
                        )
                    ],
                    metadata={"connection_tag": self.connection_tag},
                ),
            )
        return MediaJobState[MediaResult[ImageBlock]](
            job=job, status=MediaJobStatus.PENDING
        )

    def _record_cancel(self) -> None:
        with self._lock:
            self._cancel_count += 1

    def set_fetch_outcome(self, outcome: str) -> None:
        """Configure the state returned by subsequent fetch calls."""
        with self._lock:
            self._fetch_outcome = outcome

    def set_fetch_delay(self, seconds: float) -> None:
        """Configure a delay inside subsequent fetch calls."""
        with self._lock:
            self._fetch_delay = seconds

    def get_counts(self) -> dict[str, int]:
        """Return invocation counts as a flat map for Java assertions."""
        with self._lock:
            return {
                "submit": self._submit_count,
                "fetch": self._fetch_count,
                "cancel": self._cancel_count,
            }

    def reset(self) -> None:
        """Reset recorded invocation counts, fetch delay, and fetch outcome."""
        with self._lock:
            self._submit_count = 0
            self._fetch_count = 0
            self._cancel_count = 0
            self._fetch_outcome = "pending"
            self._fetch_delay = 0.0


class RecordingSpeechSynthesisModelSetup(BaseSpeechSynthesisModelSetup):
    """Test speech setup delegating to RecordingMediaModelConnection."""

    @override
    def synthesize(
        self,
        text: str,
        *,
        voice: str | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[AudioBlock]:
        return MediaResult[AudioBlock](
            content=[
                AudioBlock.from_url("audio/wav", "https://example.test/speech.wav")
            ],
            metadata={"text": text},
        )


class RecordingImageGenerationModelSetup(
    BaseImageGenerationModelSetup, AsyncJobModel[MediaResult[ImageBlock]]
):
    """Async test image setup recording connection submit/fetch/cancel calls."""

    @override
    def generate(
        self,
        prompt: str,
        *,
        images: Sequence[ImageBlock] | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[ImageBlock]:
        return self._submit_and_wait(
            prompt, images=images, model_parameters=model_parameters, timeout=timeout
        )

    @override
    def submit(
        self,
        prompt: str,
        *,
        images: Sequence[ImageBlock] | None = None,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> MediaJob:
        return self._get_connection()._record_submit()

    @override
    def fetch(self, job: MediaJob) -> MediaJobState[MediaResult[ImageBlock]]:
        return self._get_connection()._record_fetch(job)

    @override
    def cancel(self, job: MediaJob) -> None:
        self._get_connection()._record_cancel()


class YamlPlanScopeProbe:
    """Exercise deserialized Java AgentPlans in Python's scoped ResourceCache."""

    def __init__(self, **_kwargs: Any) -> None:
        """Accept initPythonResource kwargs (such as get_resource) and ignore them."""

    def verify_scopes(
        self, root_plan_json: str, child_plan_json: str
    ) -> dict[str, Any]:
        """Resolve omitted-type YAML setups in both root and child caches."""
        from flink_agents.plan.agent_plan import AgentPlan
        from flink_agents.runtime.resource_cache import ResourceCache

        root_plan = AgentPlan.model_validate_json(root_plan_json)
        child_plan = AgentPlan.model_validate_json(child_plan_json)
        root_cache = ResourceCache(root_plan.resource_providers, root_plan.config)
        child_cache = ResourceCache(
            child_plan.resource_providers, child_plan.config, parent=root_cache
        )
        try:
            root_conn = root_cache.get_resource(
                "recording", ResourceType.MEDIA_MODEL_CONNECTION
            )
            child_conn = child_cache.get_resource(
                "recording", ResourceType.MEDIA_MODEL_CONNECTION
            )
            child_only_conn = child_cache.get_resource(
                "child_only", ResourceType.MEDIA_MODEL_CONNECTION
            )
            child_conn.set_fetch_outcome("succeeded")
            child_only_conn.set_fetch_outcome("succeeded")

            override_model = child_cache.get_resource(
                "child_override_image", ResourceType.MEDIA_MODEL
            )
            only_model = child_cache.get_resource(
                "child_only_image", ResourceType.MEDIA_MODEL
            )
            override_result = override_model.generate("cat")
            only_result = only_model.generate("dog")

            return {
                "root_submits": root_conn.get_counts()["submit"],
                "child_submits": child_conn.get_counts()["submit"],
                "child_only_submits": child_only_conn.get_counts()["submit"],
                "override_tag": override_result.metadata.get("connection_tag"),
                "only_tag": only_result.metadata.get("connection_tag"),
            }
        finally:
            try:
                child_cache.close()
            finally:
                root_cache.close()
