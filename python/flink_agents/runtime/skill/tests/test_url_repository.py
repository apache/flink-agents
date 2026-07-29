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
"""Unit tests for URLSkillRepository."""

import threading
import zipfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.error import HTTPError

import pytest

from flink_agents.runtime.skill.repository.url_repository import URLSkillRepository


def _zip_dir(src: Path, dst_zip: Path) -> None:
    with zipfile.ZipFile(dst_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in src.rglob("*"):
            if path.is_file():
                zf.write(path, arcname=path.relative_to(src))


@pytest.fixture
def skills_zip_path(tmp_path: Path) -> Path:
    src = Path(__file__).parent / "resources" / "skills"
    zip_path = tmp_path / "skills.zip"
    _zip_dir(src, zip_path)
    return zip_path


class _ZipHandler(BaseHTTPRequestHandler):
    zip_bytes: bytes = b""
    status: int = 200

    def do_GET(self) -> None:
        self.send_response(type(self).status)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(len(type(self).zip_bytes)))
        self.end_headers()
        self.wfile.write(type(self).zip_bytes)

    def log_message(self, *_args: object) -> None:
        pass


@pytest.fixture
def zip_server(skills_zip_path: Path) -> "tuple[str, type[_ZipHandler]]":
    _ZipHandler.zip_bytes = skills_zip_path.read_bytes()
    _ZipHandler.status = 200
    server = HTTPServer(("127.0.0.1", 0), _ZipHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{port}/skills.zip", _ZipHandler
    finally:
        server.shutdown()
        server.server_close()
        _ZipHandler.zip_bytes = b""
        _ZipHandler.status = 200


class TestURLSkillRepository:
    def test_load_from_url(self, zip_server: "tuple[str, type[_ZipHandler]]") -> None:
        url, _handler = zip_server
        repo = URLSkillRepository(url)

        skills = repo.get_skills()
        names = {s.name for s in skills}
        assert names == {"github", "nano-banana-pro"}

    def test_non_http_url_rejected(self) -> None:
        with pytest.raises(ValueError, match="Only http"):
            URLSkillRepository("file:///tmp/skills.zip")

        with pytest.raises(ValueError, match="Only http"):
            URLSkillRepository("ftp://example.com/skills.zip")

    def test_404_error(self, zip_server: "tuple[str, type[_ZipHandler]]") -> None:
        url, handler = zip_server
        handler.status = 404

        with pytest.raises(HTTPError):
            URLSkillRepository(url)
