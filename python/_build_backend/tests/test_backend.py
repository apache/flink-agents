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
from __future__ import annotations

import hashlib
import http.server
import json
import threading
import zipfile
from typing import TYPE_CHECKING

import pytest

if TYPE_CHECKING:
    from pathlib import Path
from _build_backend.backend import (
    _ensure_jars,
    _jar_filename,
    _load_manifest,
    _verify_checksum,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _write_manifest(path: Path, manifest: dict) -> None:
    with path.open("w") as f:
        json.dump(manifest, f)


# ---------------------------------------------------------------------------
# Unit tests
# ---------------------------------------------------------------------------


class TestJarFilename:  # noqa: D101
    def test_without_classifier(self) -> None:
        entry = {
            "artifact_id": "flink-agents-dist-common",
            "dest": "lib/common/",
            "sha256": "abc",
        }
        assert _jar_filename(entry, "0.3.0") == "flink-agents-dist-common-0.3.0.jar"

    def test_with_classifier(self) -> None:
        entry = {
            "artifact_id": "flink-agents-dist-flink-2.2",
            "classifier": "thin",
            "dest": "lib/flink-2.2/",
            "sha256": "abc",
        }
        assert (
            _jar_filename(entry, "0.3.0")
            == "flink-agents-dist-flink-2.2-0.3.0-thin.jar"
        )


class TestLoadManifest:  # noqa: D101
    def test_load(self, tmp_path) -> None:
        manifest = {
            "maven_base_url": "https://repo1.maven.org/maven2",
            "group_id": "org.apache.flink",
            "version": "0.3.0",
            "jars": [],
        }
        _write_manifest(tmp_path / "jar_manifest.json", manifest)
        loaded = _load_manifest(tmp_path / "jar_manifest.json")
        assert loaded == manifest


class TestVerifyChecksum:  # noqa: D101
    def test_valid_checksum(self, tmp_path) -> None:
        content = b"fake jar content"
        jar = tmp_path / "test.jar"
        jar.write_bytes(content)
        _verify_checksum(jar, _sha256(content))

    def test_invalid_checksum(self, tmp_path) -> None:
        jar = tmp_path / "test.jar"
        jar.write_bytes(b"fake jar content")
        with pytest.raises(ValueError, match="SHA-256 mismatch"):
            _verify_checksum(
                jar, "0000000000000000000000000000000000000000000000000000000000000000"
            )
        assert not jar.exists()


class TestEnsureJars:  # noqa: D101
    def test_skip_when_no_manifest(self, tmp_path, monkeypatch) -> None:
        monkeypatch.chdir(tmp_path)
        _ensure_jars()

    def test_skip_when_env_var_set(self, tmp_path, monkeypatch) -> None:
        monkeypatch.chdir(tmp_path)
        _write_manifest(
            tmp_path / "jar_manifest.json",
            {
                "maven_base_url": "http://example.com",
                "group_id": "org.apache.flink",
                "version": "0.3.0",
                "jars": [{"artifact_id": "a", "dest": "lib/", "sha256": "abc"}],
            },
        )
        monkeypatch.setenv("FLINK_AGENTS_SKIP_JAR_DOWNLOAD", "true")
        _ensure_jars()
        assert not (tmp_path / "lib").exists()

    def test_skip_when_jar_already_exists(self, tmp_path, monkeypatch) -> None:
        monkeypatch.chdir(tmp_path)
        dest = tmp_path / "lib" / "common"
        dest.mkdir(parents=True)
        jar = dest / "flink-agents-dist-common-0.3.0.jar"
        jar.write_bytes(b"existing")

        _write_manifest(
            tmp_path / "jar_manifest.json",
            {
                "maven_base_url": "http://localhost:1",
                "group_id": "org.apache.flink",
                "version": "0.3.0",
                "jars": [
                    {
                        "artifact_id": "flink-agents-dist-common",
                        "dest": "lib/common/",
                        "sha256": "abc",
                    }
                ],
            },
        )
        _ensure_jars()
        assert jar.read_bytes() == b"existing"


# ---------------------------------------------------------------------------
# Integration test — local HTTP server
# ---------------------------------------------------------------------------


class _JarHandler(http.server.SimpleHTTPRequestHandler):
    """Serve fake JARs from a directory."""

    def log_message(self, format, *args: object) -> None:
        pass


@pytest.fixture(scope="module")
def maven_server(tmp_path_factory) -> tuple[str, Path]:
    """Start a local HTTP server that serves fake Maven artifacts."""
    serve_dir = tmp_path_factory.mktemp("maven_repo")

    fake_jar_content = b"PK\x03\x04fake-jar-bytes-for-testing"
    sha = _sha256(fake_jar_content)

    group_path = "org/apache/flink"
    version = "0.3.0"
    artifacts = [
        ("flink-agents-dist-common", None),
        ("flink-agents-dist-flink-1.20", "thin"),
        ("flink-agents-dist-flink-2.0", "thin"),
        ("flink-agents-dist-flink-2.1", "thin"),
        ("flink-agents-dist-flink-2.2", "thin"),
    ]

    for artifact_id, classifier in artifacts:
        if classifier:
            filename = f"{artifact_id}-{version}-{classifier}.jar"
        else:
            filename = f"{artifact_id}-{version}.jar"
        artifact_dir = serve_dir / group_path / artifact_id / version
        artifact_dir.mkdir(parents=True, exist_ok=True)
        (artifact_dir / filename).write_bytes(fake_jar_content)

    def handler(*args: object, **kwargs: object) -> _JarHandler:
        return _JarHandler(*args, directory=str(serve_dir), **kwargs)

    server = http.server.HTTPServer(("127.0.0.1", 0), handler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    yield f"http://127.0.0.1:{port}", sha

    server.shutdown()


def test_download_jars_from_local_server(tmp_path, monkeypatch, maven_server) -> None:
    base_url, expected_sha = maven_server
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("FLINK_AGENTS_MAVEN_MIRROR", base_url)

    manifest = {
        "maven_base_url": "https://should-not-be-used.example.com",
        "group_id": "org.apache.flink",
        "version": "0.3.0",
        "jars": [
            {
                "artifact_id": "flink-agents-dist-common",
                "dest": "flink_agents/lib/common/",
                "sha256": expected_sha,
            },
            {
                "artifact_id": "flink-agents-dist-flink-1.20",
                "classifier": "thin",
                "dest": "flink_agents/lib/flink-1.20/",
                "sha256": expected_sha,
            },
            {
                "artifact_id": "flink-agents-dist-flink-2.0",
                "classifier": "thin",
                "dest": "flink_agents/lib/flink-2.0/",
                "sha256": expected_sha,
            },
            {
                "artifact_id": "flink-agents-dist-flink-2.1",
                "classifier": "thin",
                "dest": "flink_agents/lib/flink-2.1/",
                "sha256": expected_sha,
            },
            {
                "artifact_id": "flink-agents-dist-flink-2.2",
                "classifier": "thin",
                "dest": "flink_agents/lib/flink-2.2/",
                "sha256": expected_sha,
            },
        ],
    }
    _write_manifest(tmp_path / "jar_manifest.json", manifest)

    _ensure_jars()

    assert (
        tmp_path / "flink_agents/lib/common/flink-agents-dist-common-0.3.0.jar"
    ).exists()
    assert (
        tmp_path
        / "flink_agents/lib/flink-1.20/flink-agents-dist-flink-1.20-0.3.0-thin.jar"
    ).exists()
    assert (
        tmp_path
        / "flink_agents/lib/flink-2.0/flink-agents-dist-flink-2.0-0.3.0-thin.jar"
    ).exists()
    assert (
        tmp_path
        / "flink_agents/lib/flink-2.1/flink-agents-dist-flink-2.1-0.3.0-thin.jar"
    ).exists()
    assert (
        tmp_path
        / "flink_agents/lib/flink-2.2/flink-agents-dist-flink-2.2-0.3.0-thin.jar"
    ).exists()


def test_download_fails_on_checksum_mismatch(
    tmp_path, monkeypatch, maven_server
) -> None:
    base_url, _ = maven_server
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("FLINK_AGENTS_MAVEN_MIRROR", base_url)

    manifest = {
        "maven_base_url": "https://should-not-be-used.example.com",
        "group_id": "org.apache.flink",
        "version": "0.3.0",
        "jars": [
            {
                "artifact_id": "flink-agents-dist-common",
                "dest": "flink_agents/lib/common/",
                "sha256": "bad_checksum",
            },
        ],
    }
    _write_manifest(tmp_path / "jar_manifest.json", manifest)

    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        _ensure_jars()

    assert not (
        tmp_path / "flink_agents/lib/common/flink-agents-dist-common-0.3.0.jar"
    ).exists()


def test_build_wheel_produces_wheel_with_jars(
    tmp_path, monkeypatch, maven_server
) -> None:
    """Integration test: build a wheel and verify it contains downloaded JARs."""
    base_url, expected_sha = maven_server

    # Use a subdirectory as the "project root" to isolate from wheel output dir
    project_dir = tmp_path / "project"
    project_dir.mkdir()
    monkeypatch.chdir(project_dir)
    monkeypatch.setenv("FLINK_AGENTS_MAVEN_MIRROR", base_url)

    # Minimal Python package structure (with __init__.py in lib dirs so
    # setuptools discovers them as packages for package-data inclusion)
    lib_common = project_dir / "flink_agents" / "lib" / "common"
    lib_common.mkdir(parents=True)
    (project_dir / "flink_agents" / "__init__.py").write_text("")
    (project_dir / "flink_agents" / "lib" / "__init__.py").write_text("")
    (project_dir / "flink_agents" / "lib" / "common" / "__init__.py").write_text("")

    manifest = {
        "maven_base_url": "https://should-not-be-used.example.com",
        "group_id": "org.apache.flink",
        "version": "0.3.0",
        "jars": [
            {
                "artifact_id": "flink-agents-dist-common",
                "dest": "flink_agents/lib/common/",
                "sha256": expected_sha,
            },
        ],
    }
    _write_manifest(project_dir / "jar_manifest.json", manifest)

    (project_dir / "setup.cfg").write_text(
        "[metadata]\n"
        "name = test-pkg\n"
        "version = 0.0.1\n\n"
        "[options]\n"
        "packages = find:\n\n"
        "[options.package_data]\n"
        "flink_agents.lib = **/*.jar\n"
    )

    from _build_backend.backend import build_wheel

    wheel_dir = tmp_path / "wheel_out"
    wheel_dir.mkdir()
    wheel_name = build_wheel(str(wheel_dir))

    wheel_path = wheel_dir / wheel_name
    assert wheel_path.exists()

    with zipfile.ZipFile(wheel_path) as zf:
        names = zf.namelist()
        jar_entries = [n for n in names if n.endswith(".jar")]
        assert len(jar_entries) == 1
        assert any("flink-agents-dist-common-0.3.0.jar" in n for n in jar_entries)
