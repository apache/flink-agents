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
from collections.abc import Callable
from types import SimpleNamespace
from typing import Any

import httpx
import pytest

from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModel,
)


@pytest.fixture
def sdk_jobs() -> SimpleNamespace:
    created: list[dict[str, Any]] = []

    def create(**kwargs: Any) -> SimpleNamespace:
        created.append(kwargs)
        return SimpleNamespace(task_id="job-1")

    return SimpleNamespace(created=created, create=create)


@pytest.fixture
def task_responses(
    monkeypatch: pytest.MonkeyPatch,
) -> Callable[..., list[dict[str, Any]]]:
    def queue(
        model: DashScopeMediaModel, *responses: httpx.Response
    ) -> list[dict[str, Any]]:
        pending = list(responses)
        calls: list[dict[str, Any]] = []

        def request(method: str, path: str, **kwargs: Any) -> httpx.Response:
            calls.append({"method": method, "path": path, **kwargs})
            return pending.pop(0)

        monkeypatch.setattr(model._client().transport, "request", request)
        return calls

    return queue
