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
"""Integration tests for the ``SkillOptions`` agent config options.

A limit set only in the agent configuration must take effect at the bottom of
the real path: ``ResourceCache`` -> ``ResourceContextImpl`` -> ``SkillManager``
-> ``URLSkillRepository`` / ``FileSystemSkillRepository`` -> ``_materialize``.

Every rejection test has a matching control that uses the same archive with no
limit set, so a failure can only come from the configured limit.
"""

import importlib
import io
import sys
import threading
import zipfile
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


class TestLocalZipSource:
    def test_loads_when_no_limits_configured(self, tmp_path: Path) -> None:
        zip_path = _write_zip(tmp_path, 3)
        cache = _cache_with(Skills.from_local_dir(str(zip_path)), AgentConfiguration())
        try:
            assert _skill_names(cache) == ["skill-0", "skill-1", "skill-2"]
        finally:
            cache.close()

    def test_configured_entry_limit_rejects_zip(self, tmp_path: Path) -> None:
        zip_path = _write_zip(tmp_path, 3)
        config = AgentConfiguration({"skill.source.max-extract-entries": 2})
        cache = _cache_with(Skills.from_local_dir(str(zip_path)), config)
        try:
            message = _load_failure(cache)
            assert "3 entries" in message
            assert "limit of 2" in message
        finally:
            cache.close()

    def test_configured_entry_limit_exactly_at_archive_size_loads(
        self, tmp_path: Path
    ) -> None:
        zip_path = _write_zip(tmp_path, 3)
        config = AgentConfiguration({"skill.source.max-extract-entries": 3})
        cache = _cache_with(Skills.from_local_dir(str(zip_path)), config)
        try:
            assert len(_skill_names(cache)) == 3
        finally:
            cache.close()

    def test_configured_entry_byte_limit_rejects_zip(self, tmp_path: Path) -> None:
        zip_path = _write_zip(tmp_path, 1)
        config = AgentConfiguration({"skill.source.max-extract-entry-bytes": 5})
        cache = _cache_with(Skills.from_local_dir(str(zip_path)), config)
        try:
            assert "per-entry limit of 5 bytes" in _load_failure(cache)
        finally:
            cache.close()

    def test_configured_total_byte_limit_rejects_zip(self, tmp_path: Path) -> None:
        zip_path = _write_zip(tmp_path, 3)
        config = AgentConfiguration({"skill.source.max-extract-total-bytes": 10})
        cache = _cache_with(Skills.from_local_dir(str(zip_path)), config)
        try:
            assert "exceeds the limit of 10 bytes" in _load_failure(cache)
        finally:
            cache.close()

    def test_invalid_configured_limit_fails_instead_of_using_default(
        self, tmp_path: Path
    ) -> None:
        zip_path = _write_zip(tmp_path, 1)
        config = AgentConfiguration({"skill.source.max-extract-entries": 0})
        cache = _cache_with(Skills.from_local_dir(str(zip_path)), config)
        try:
            with pytest.raises(ValueError, match="strictly positive"):
                cache.get_resource_context().get_skill_manager()
        finally:
            cache.close()

    def test_string_valued_limit_from_yaml_applies(self, tmp_path: Path) -> None:
        zip_path = _write_zip(tmp_path, 3)
        config = AgentConfiguration({"skill.source.max-extract-entries": "2"})
        cache = _cache_with(Skills.from_local_dir(str(zip_path)), config)
        try:
            assert "limit of 2" in _load_failure(cache)
        finally:
            cache.close()


class TestUrlSource:
    def test_loads_when_no_limits_configured(self, serve) -> None:
        url = serve(_skill_zip(2))
        cache = _cache_with(Skills.from_url_unsafe(url), AgentConfiguration())
        try:
            assert len(_skill_names(cache)) == 2
        finally:
            cache.close()

    def test_configured_download_limit_rejects(self, serve) -> None:
        body = _skill_zip(2)
        url = serve(body)
        config = AgentConfiguration(
            {"skill.source.url.max-download-bytes": len(body) - 1}
        )
        cache = _cache_with(Skills.from_url_unsafe(url), config)
        try:
            message = _load_failure(cache)
            assert "download" in message
            assert f"limit of {len(body) - 1} bytes" in message
        finally:
            cache.close()

    def test_configured_download_limit_exactly_at_body_size_loads(self, serve) -> None:
        body = _skill_zip(2)
        url = serve(body)
        config = AgentConfiguration({"skill.source.url.max-download-bytes": len(body)})
        cache = _cache_with(Skills.from_url_unsafe(url), config)
        try:
            assert len(_skill_names(cache)) == 2
        finally:
            cache.close()

    def test_configured_entry_limit_rejects(self, serve) -> None:
        url = serve(_skill_zip(3))
        config = AgentConfiguration({"skill.source.max-extract-entries": 2})
        cache = _cache_with(Skills.from_url_unsafe(url), config)
        try:
            assert "limit of 2" in _load_failure(cache)
        finally:
            cache.close()

    def test_configured_entry_byte_limit_rejects(self, serve) -> None:
        url = serve(_skill_zip(1))
        config = AgentConfiguration({"skill.source.max-extract-entry-bytes": 5})
        cache = _cache_with(Skills.from_url_unsafe(url), config)
        try:
            assert "per-entry limit of 5 bytes" in _load_failure(cache)
        finally:
            cache.close()

    def test_configured_total_byte_limit_rejects(self, serve) -> None:
        url = serve(_skill_zip(3))
        config = AgentConfiguration({"skill.source.max-extract-total-bytes": 10})
        cache = _cache_with(Skills.from_url_unsafe(url), config)
        try:
            assert "exceeds the limit of 10 bytes" in _load_failure(cache)
        finally:
            cache.close()

    def test_value_too_large_for_java_fails_loading(self, serve) -> None:
        url = serve(_skill_zip(1))
        config = AgentConfiguration({"skill.source.max-extract-entries": 3_000_000_000})
        cache = _cache_with(Skills.from_url_unsafe(url), config)
        try:
            with pytest.raises(
                ValueError,
                match=r"Invalid value for skill\.source\.max-extract-entries",
            ):
                cache.get_resource_context().get_skill_manager()
        finally:
            cache.close()


class TestPackageSourceKeepsDefaults:
    """D14: configured limits apply to url and local sources only."""

    def test_package_zip_ignores_configured_limits(self, tmp_path: Path) -> None:
        pkg_root = tmp_path / "pkg_root"
        pkg_dir = pkg_root / "_flink_agents_test_limits_pkg"
        pkg_dir.mkdir(parents=True)
        (pkg_dir / "__init__.py").write_text("")
        (pkg_dir / "skills.zip").write_bytes(_skill_zip(3))
        sys.path.insert(0, str(pkg_root))
        importlib.invalidate_caches()
        config = AgentConfiguration({"skill.source.max-extract-entries": 1})
        cache = _cache_with(
            Skills.from_package(("_flink_agents_test_limits_pkg", "skills.zip")),
            config,
        )
        try:
            assert len(_skill_names(cache)) == 3
        finally:
            cache.close()
            sys.path.remove(str(pkg_root))
            sys.modules.pop("_flink_agents_test_limits_pkg", None)


class TestSubagentChildCache:
    """D15 / D15a: a child cache inherits the root's limits unless it has its own."""

    def test_child_cache_inherits_root_limits(self, tmp_path: Path) -> None:
        zip_path = _write_zip(tmp_path, 3)
        config = AgentConfiguration({"skill.source.max-extract-entries": 2})
        root = _cache_with(Skills.from_local_dir(str(zip_path)), config)
        child = ResourceCache({}, parent=root)
        try:
            assert "limit of 2" in _load_failure(child)
        finally:
            child.close()
            root.close()

    def test_child_cache_own_config_overrides_root(self, tmp_path: Path) -> None:
        zip_path = _write_zip(tmp_path, 3)
        root_config = AgentConfiguration({"skill.source.max-extract-entries": 2})
        child_config = AgentConfiguration({"skill.source.max-extract-entries": 3})
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
