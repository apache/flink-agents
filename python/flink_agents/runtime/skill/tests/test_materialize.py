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
"""Unit tests for the _materialize utility module."""

import threading
import zipfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.error import HTTPError

import pytest

from flink_agents.runtime.skill.repository._materialize import (
    Materialized,
    download_to_tempfile,
    extract_zip_safely,
)


def _make_zip(zip_path: Path, entries: dict[str, str]) -> None:
    with zipfile.ZipFile(zip_path, "w") as zf:
        for name, content in entries.items():
            zf.writestr(name, content)


class TestExtractZipSafely:
    def test_extracts_top_level_entries(self, tmp_path: Path) -> None:
        zip_path = tmp_path / "skills.zip"
        _make_zip(
            zip_path,
            {
                "skill-a/SKILL.md": "---\nname: skill-a\n---\nbody",
                "skill-b/SKILL.md": "---\nname: skill-b\n---\nbody",
            },
        )

        with extract_zip_safely(zip_path) as m:
            extract_dir = m.dir
            assert extract_dir.is_dir()
            assert (extract_dir / "skill-a" / "SKILL.md").read_text().startswith("---")
            assert (extract_dir / "skill-b" / "SKILL.md").is_file()

    def test_rejects_zip_slip_relative(self, tmp_path: Path) -> None:
        zip_path = tmp_path / "evil.zip"
        _make_zip(zip_path, {"../evil.txt": "pwn"})

        with pytest.raises(ValueError, match="Unsafe zip entry"):
            extract_zip_safely(zip_path)

    def test_rejects_zip_slip_absolute(self, tmp_path: Path) -> None:
        # Defense-in-depth: CPython's extractall already strips leading slashes,
        # but we reject absolute entries explicitly so we don't depend on that.
        zip_path = tmp_path / "evil.zip"
        _make_zip(zip_path, {"/etc/evil.txt": "pwn"})

        with pytest.raises(ValueError, match="Unsafe zip entry"):
            extract_zip_safely(zip_path)


class TestMaterialized:
    def test_close_removes_dir(self, tmp_path: Path) -> None:
        zip_path = tmp_path / "skills.zip"
        _make_zip(zip_path, {"skill-a/SKILL.md": "---\nname: skill-a\n---\nbody"})
        m = extract_zip_safely(zip_path)
        extracted = m.dir
        assert extracted.exists()

        m.close()
        assert not extracted.exists(), "close() must remove the temp dir"

        # Idempotent.
        m.close()

    def test_borrowed_does_not_remove_dir(self, tmp_path: Path) -> None:
        target = tmp_path / "borrowed"
        target.mkdir()
        m = Materialized.borrowed(target)
        m.close()
        assert target.exists(), "borrowed dirs must not be deleted on close"


class _StaticHandler(BaseHTTPRequestHandler):
    payload: bytes = b""
    status: int = 200

    def do_GET(self) -> None:
        self.send_response(type(self).status)
        self.send_header("Content-Length", str(len(type(self).payload)))
        self.end_headers()
        self.wfile.write(type(self).payload)

    def log_message(self, *_args: object) -> None:
        pass


@pytest.fixture
def static_server() -> "tuple[str, type[_StaticHandler]]":
    _StaticHandler.payload = b""
    _StaticHandler.status = 200
    server = HTTPServer(("127.0.0.1", 0), _StaticHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{port}", _StaticHandler
    finally:
        server.shutdown()
        server.server_close()
        _StaticHandler.payload = b""
        _StaticHandler.status = 200


class TestDownloadToTempfile:
    def test_downloads_bytes(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server
        handler.payload = b"hello-zip-bytes"
        handler.status = 200

        path = download_to_tempfile(f"{base_url}/anything", timeout=10)

        try:
            assert path.is_file()
            assert path.read_bytes() == b"hello-zip-bytes"
        finally:
            path.unlink(missing_ok=True)

    def test_raises_on_http_error(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server
        handler.payload = b""
        handler.status = 404

        with pytest.raises(HTTPError):
            download_to_tempfile(f"{base_url}/missing", timeout=10)
