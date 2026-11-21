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
from types import SimpleNamespace
from typing import Any
from unittest.mock import Mock

import cloudpickle
import httpx
import pytest

from flink_agents.api.chat_message import VideoBlock
from flink_agents.api.media.media_model import (
    MediaJob,
    MediaResult,
)
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import PythonResourceProvider
from flink_agents.runtime.durable_exception import is_java_interruption
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext
from flink_agents.runtime.resource_cache import ResourceCache
from flink_agents.runtime.tests.test_durable_exception import (
    _INTERRUPTED,
    _java_error,
)
from flink_agents.runtime.tests.test_flink_runner_context_reconcilable import (
    _close_runner_context,
    _create_runner_context,
    _FakeJavaRunnerContext,
    _run_async,
)


class _Service:
    """External job state survives client and resource recreation."""

    def __init__(self) -> None:
        self.submits = 0
        self.queries: list[str] = []
        self.clients: list[Mock] = []

    def create_client(self, **kwargs: Any) -> Mock:
        client = Mock()
        client.videos.jobs.create.side_effect = self.submit
        client.transport.request.side_effect = self.query
        self.clients.append(client)
        return client

    def submit(self, **kwargs: Any) -> SimpleNamespace:
        self.submits += 1
        return SimpleNamespace(task_id="job-1")

    def query(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        assert method == "GET"
        self.queries.append(path)
        if len(self.queries) == 1:
            output = {"task_status": "PENDING"}
        elif len(self.queries) == 2:
            raise _java_error(_INTERRUPTED)
        else:
            output = {
                "task_status": "SUCCEEDED",
                "video_url": "https://example.test/out.mp4",
            }
        return httpx.Response(200, json={"output": output})


def _resource_cache() -> ResourceCache:
    descriptor = ResourceDescriptor(
        clazz=ResourceName.MediaModelProvider.DASHSCOPE,
        api_key="sk-test",
        workspace="ws-test",
    )
    resource_provider = PythonResourceProvider.get(
        "media", descriptor, ResourceType.MEDIA_MODEL_PROVIDER
    )
    return ResourceCache(
        {ResourceType.MEDIA_MODEL_PROVIDER: {"media": resource_provider}},
        AgentConfiguration(),
    )


class _Clock:
    def __init__(self) -> None:
        self.now = 0.0

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds


def _execute(ctx: FlinkRunnerContext, asynchronous: bool, func: Any, *args: Any) -> Any:
    if asynchronous:
        return _run_async(ctx.durable_execute_async(func, *args))
    return ctx.durable_execute(func, *args)


@pytest.mark.parametrize("asynchronous", [False, True])
def test_interrupted_wait_replays_the_submitted_job(
    asynchronous: bool, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A recreated provider resumes the persisted job after an interrupted wait."""
    monkeypatch.setattr(
        "flink_agents.api.media.media_model.time",
        _Clock(),
    )
    service = _Service()
    monkeypatch.setattr(
        "flink_agents.integrations.media_model_providers.dashscope_media_model_provider.create_client",
        service.create_client,
    )
    cache = _resource_cache()
    provider = cache.get_resource("media", ResourceType.MEDIA_MODEL_PROVIDER)
    model = provider.video("wan2.7-i2v")

    def submit_job() -> MediaJob:
        return model.submit("a cat")

    def wait_job(job: MediaJob) -> MediaResult[VideoBlock]:
        return model.wait(job)

    store = _FakeJavaRunnerContext()
    ctx = _create_runner_context(store)
    try:
        job = _execute(ctx, asynchronous, submit_job)
        with pytest.raises(RuntimeError) as raised:
            _execute(ctx, asynchronous, wait_job, job)
    finally:
        _close_runner_context(ctx)
        cache.close()

    assert is_java_interruption(raised.value)
    assert service.submits == 1
    assert service.queries == ["/tasks/job-1", "/tasks/job-1"]
    assert [slot.status for slot in store.call_results] == ["SUCCEEDED"]
    assert store.call_results[0].exception_payload is None
    stored_job = cloudpickle.loads(store.call_results[0].result_payload)
    assert stored_job == job
    assert store.current_call_index == 1

    store.current_call_index = 0
    cache = _resource_cache()
    recovered_provider = cache.get_resource("media", ResourceType.MEDIA_MODEL_PROVIDER)
    recovered_model = recovered_provider.video("wan2.7-i2v")
    assert recovered_provider is not provider
    assert recovered_model is not model
    model = recovered_model
    ctx = _create_runner_context(store)
    try:
        recovered = _execute(ctx, asynchronous, submit_job)
        result = _execute(ctx, asynchronous, wait_job, recovered)
    finally:
        _close_runner_context(ctx)
        cache.close()

    assert service.submits == 1
    assert recovered == job
    assert recovered is not job
    assert service.queries == ["/tasks/job-1"] * 3
    assert len(service.clients) == 2
    for client in service.clients:
        client.close.assert_called_once_with()
    assert result == MediaResult[VideoBlock](
        content=[VideoBlock.from_url("video/mp4", "https://example.test/out.mp4")]
    )
    assert [slot.status for slot in store.call_results] == ["SUCCEEDED", "SUCCEEDED"]
    stored_result = cloudpickle.loads(store.call_results[1].result_payload)
    assert stored_result == result
