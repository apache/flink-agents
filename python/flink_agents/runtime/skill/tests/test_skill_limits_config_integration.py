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
"""Verify configured limits through ResourceCache for each source and child scope."""

import io
import sys
import threading
import zipfile
from contextlib import closing
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Iterator

import pytest

from flink_agents.api.resource import ResourceType
from flink_agents.api.skills import Skills
from flink_agents.plan.agent_plan import SKILLS_CONFIG
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.resource_cache import ResourceCache


def _skill_zip(skills: int) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as zf:
        for i in range(skills):
            zf.writestr(
                f"skill-{i}/SKILL.md",
                f"---\nname: skill-{i}\ndescription: demo {i}\n---\nbody\n",
            )
    return buffer.getvalue()


def _write_zip(tmp_path: Path, skills: int) -> Path:
    zip_path = tmp_path / "skills.zip"
    zip_path.write_bytes(_skill_zip(skills))
    return zip_path


def _cache_with(
    skills: Skills,
    config: AgentConfiguration | None,
    parent: ResourceCache | None = None,
) -> ResourceCache:
    cache = ResourceCache({}, config, parent=parent)
    cache._cache[ResourceType.SKILLS] = {SKILLS_CONFIG: skills}
    return cache


def _skill_names(cache: ResourceCache) -> list[str]:
    manager = cache.get_resource_context().get_skill_manager()
    assert manager is not None
    return sorted(manager.get_all_skill_names())


def _load_failure(cache: ResourceCache) -> str:
    """Return the root-cause message of the failed SkillManager creation."""
    with pytest.raises(RuntimeError) as exc_info:
        cache.get_resource_context().get_skill_manager()
    cause: BaseException = exc_info.value
    while cause.__cause__ is not None:
        cause = cause.__cause__
    return str(cause)


class _ZipHandler(BaseHTTPRequestHandler):
    body: bytes = b""

    def do_GET(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(len(type(self).body)))
        self.end_headers()
        self.wfile.write(type(self).body)

    def log_message(self, *_args: object) -> None:
        pass


@pytest.fixture
def serve() -> Iterator:
    """Return a function that serves a body over local HTTP and returns its URL."""
    servers: list[HTTPServer] = []

    def start(body: bytes) -> str:
        _ZipHandler.body = body
        server = HTTPServer(("127.0.0.1", 0), _ZipHandler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        servers.append(server)
        return f"http://127.0.0.1:{server.server_address[1]}/skills.zip"

    try:
        yield start
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
        _ZipHandler.body = b""


@pytest.fixture(params=["local", "url", "package"])
def skill_source(
    request: pytest.FixtureRequest, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> Iterator[Skills]:
    """Expose the same three-skill ZIP through each supported Python source."""
    if request.param == "url":
        yield Skills.from_url_unsafe(request.getfixturevalue("serve")(_skill_zip(3)))
    elif request.param == "local":
        yield Skills.from_local_dir(str(_write_zip(tmp_path, 3)))
    else:
        package = "_flink_agents_test_limits_pkg"
        package_dir = tmp_path / package
        package_dir.mkdir()
        (package_dir / "__init__.py").write_text("")
        _write_zip(package_dir, 3)
        monkeypatch.syspath_prepend(str(tmp_path))
        try:
            yield Skills.from_package((package, "skills.zip"))
        finally:
            sys.modules.pop(package, None)


def test_loads_with_default_limits(skill_source: Skills) -> None:
    with closing(_cache_with(skill_source, AgentConfiguration())) as cache:
        assert _skill_names(cache) == ["skill-0", "skill-1", "skill-2"]


@pytest.mark.parametrize(
    ("key", "value", "message"),
    [
        ("skill.max-archive-entries", 2, "limit of 2"),
        ("skill.max-uncompressed-entry-bytes", 5, "per-entry limit of 5 bytes"),
        ("skill.max-uncompressed-total-bytes", 10, "limit of 10 bytes"),
    ],
)
def test_extraction_limits_reach_source(
    skill_source: Skills, key: str, value: int, message: str
) -> None:
    with closing(_cache_with(skill_source, AgentConfiguration({key: value}))) as cache:
        assert message in _load_failure(cache)


def test_entry_count_exactly_at_limit_loads(skill_source: Skills) -> None:
    config = AgentConfiguration({"skill.max-archive-entries": 3})
    with closing(_cache_with(skill_source, config)) as cache:
        assert len(_skill_names(cache)) == 3


@pytest.mark.parametrize(
    ("value", "message"),
    [
        (0, "strictly positive"),
        (3_000_000_000, r"Invalid value for skill\.max-archive-entries"),
    ],
)
def test_invalid_config_fails_instead_of_using_defaults(
    skill_source: Skills, value: int, message: str
) -> None:
    config = AgentConfiguration({"skill.max-archive-entries": value})
    with (
        closing(_cache_with(skill_source, config)) as cache,
        pytest.raises(ValueError, match=message),
    ):
        cache.get_resource_context().get_skill_manager()


def test_string_valued_config_applies(skill_source: Skills) -> None:
    config = AgentConfiguration({"skill.max-archive-entries": "2"})
    with closing(_cache_with(skill_source, config)) as cache:
        assert "limit of 2" in _load_failure(cache)


@pytest.mark.parametrize("offset", [-1, 0], ids=["over-limit", "at-limit"])
def test_download_limit_reaches_url_source(serve, offset: int) -> None:
    body = _skill_zip(2)
    config = AgentConfiguration({"skill.max-download-bytes": len(body) + offset})
    with closing(_cache_with(Skills.from_url_unsafe(serve(body)), config)) as cache:
        if offset == 0:
            assert len(_skill_names(cache)) == 2
        else:
            message = _load_failure(cache)
            assert "download" in message
            assert f"limit of {len(body) - 1} bytes" in message


class TestSubagentChildCache:
    """A child cache inherits the root's limits unless it has its own."""

    def test_child_cache_inherits_root_limits(self, tmp_path: Path) -> None:
        zip_path = _write_zip(tmp_path, 3)
        config = AgentConfiguration({"skill.max-archive-entries": 2})
        root = _cache_with(Skills.from_local_dir(str(zip_path)), config)
        child = ResourceCache({}, parent=root)
        try:
            assert "limit of 2" in _load_failure(child)
        finally:
            child.close()
            root.close()

    def test_child_cache_own_config_overrides_root(self, tmp_path: Path) -> None:
        zip_path = _write_zip(tmp_path, 3)
        root_config = AgentConfiguration({"skill.max-archive-entries": 2})
        child_config = AgentConfiguration({"skill.max-archive-entries": 3})
        root = _cache_with(Skills.from_local_dir(str(zip_path)), root_config)
        child = _cache_with(
            Skills.from_local_dir(str(zip_path)), child_config, parent=root
        )
        try:
            assert len(_skill_names(child)) == 3
        finally:
            child.close()
            root.close()

    def test_child_cache_without_root_config_uses_defaults(
        self, tmp_path: Path
    ) -> None:
        zip_path = _write_zip(tmp_path, 3)
        root = _cache_with(Skills.from_local_dir(str(zip_path)), None)
        child = ResourceCache({}, parent=root)
        try:
            assert len(_skill_names(child)) == 3
        finally:
            child.close()
            root.close()
