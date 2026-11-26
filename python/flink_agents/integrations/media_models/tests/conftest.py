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
from collections.abc import Callable, Iterator
from types import SimpleNamespace
from typing import Any

import httpx
import pytest

from flink_agents.api.media import AsyncJobModel
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.api.tools.tool import Tool
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModelConnection,
)


class SingleConnectionContext(ResourceContext):
    """Test ResourceContext exposing one media connection under a resource name."""

    def __init__(self, connection: Any, name: str = "dashscope") -> None:
        self._name = name
        self._connection = connection

    def get_resource(
        self, name: str, resource_type: ResourceType, metric_group: Any = None
    ) -> Resource:
        if name == self._name and resource_type is ResourceType.MEDIA_MODEL_CONNECTION:
            return self._connection
        msg = f"Unknown resource: {name} ({resource_type})"
        raise KeyError(msg)

    def get_subagent_tool(self, name: str) -> Tool:
        raise NotImplementedError

    def generate_available_skills_prompt(self, *skill_names: str) -> str:
        return ""

    def get_skill_dirs(self, *skill_names: str) -> list[str]:
        return []


@pytest.fixture
def connection_factory() -> Iterator[Callable[..., DashScopeMediaModelConnection]]:
    connections = []

    def create(**overrides: Any) -> DashScopeMediaModelConnection:
        values = {"api_key": "sk-test", "workspace": "ws-1", **overrides}
        connection = DashScopeMediaModelConnection(**values)
        connections.append(connection)
        return connection

    yield create
    for connection in connections:
        connection.close()


@pytest.fixture
def setup_factory(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
) -> Iterator[Callable[..., Any]]:
    setups = []

    def create(
        setup_cls: type[Any],
        *,
        connection: Any | None = None,
        open_connection: bool = True,
        open_setup: bool = True,
        **kwargs: Any,
    ) -> Any:
        conn = connection if connection is not None else connection_factory()
        if open_connection:
            conn.open()
        ctx = SingleConnectionContext(conn)
        setup = setup_cls(
            connection="dashscope",
            resource_context=ctx,
            **kwargs,
        )
        if open_setup:
            setup.open()
        setups.append(setup)
        return setup

    yield create
    for setup in reversed(setups):
        setup.close()


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
    def queue(model: AsyncJobModel, *responses: httpx.Response) -> list[dict[str, Any]]:
        pending = list(responses)
        calls: list[dict[str, Any]] = []

        def request(method: str, path: str, **kwargs: Any) -> httpx.Response:
            calls.append({"method": method, "path": path, **kwargs})
            return pending.pop(0)

        monkeypatch.setattr(model._client().transport, "request", request)
        return calls

    return queue
