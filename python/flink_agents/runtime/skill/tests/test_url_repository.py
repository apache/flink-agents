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

import hashlib
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from io import BytesIO
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
        digest = hashlib.sha256(_handler.zip_bytes).hexdigest().upper()
        repo = URLSkillRepository(url, sha256=digest, allow_insecure_http=True)

        skills = repo.get_skills()
        names = {s.name for s in skills}
        assert names == {"github", "nano-banana-pro"}

    def test_non_http_url_rejected(self) -> None:
        with pytest.raises(ValueError, match=r"Only HTTP\(S\)"):
            URLSkillRepository("file:///tmp/skills.zip")

        with pytest.raises(ValueError, match=r"Only HTTP\(S\)"):
            URLSkillRepository("ftp://example.com/skills.zip")

    def test_plain_http_rejected_by_default(
        self, zip_server: "tuple[str, type[_ZipHandler]]"
    ) -> None:
        url, _handler = zip_server
        with pytest.raises(ValueError, match="disabled by default"):
            URLSkillRepository(url)

    def test_sha256_mismatch_rejected_before_extraction(
        self, zip_server: "tuple[str, type[_ZipHandler]]"
    ) -> None:
        url, handler = zip_server
        archive = BytesIO()
        with zipfile.ZipFile(archive, "w") as zf:
            zf.writestr("../evil.txt", "pwn")
        handler.zip_bytes = archive.getvalue()

        signed_url = f"{url}?token=top-secret#fragment"
        with pytest.raises(ValueError, match="SHA-256 mismatch") as exc_info:
            URLSkillRepository(signed_url, sha256="0" * 64, allow_insecure_http=True)
        assert url in str(exc_info.value)
        assert "top-secret" not in str(exc_info.value)

    @pytest.mark.parametrize(
        "url",
        [
            "https://:443/skills.zip",
            "https://example.com:bad/skills.zip",
            "https://example.com:65536/skills.zip",
        ],
    )
    def test_invalid_host_and_port_rejected_before_download(self, url: str) -> None:
        with pytest.raises(ValueError, match=r"valid host|valid port"):
            URLSkillRepository(url)

    @pytest.mark.parametrize(
        "url",
        [
            "https://exa_mple.com/skills.zip",
            "https://tést.com/skills.zip",
            "https://%65xample.com/skills.zip",
            "https://-example.com/skills.zip",
            "https://example-.com/skills.zip",
            "https://.example.com/skills.zip",
            "https://example..com/skills.zip",
            "https://a../skills.zip",
            "https://../skills.zip",
            "https://999.999.999.999/skills.zip",
            "https://127.1/skills.zip",
            "https://1.2.3/skills.zip",
            "https://foo.123/skills.zip",
            "https://foo.1bar/skills.zip",
            "https://1.2.3.4.5/skills.zip",
            "https://1.2.3./skills.zip",
            "https://1.2.3.4./skills.zip",
            "https://[v1.foo]/skills.zip",
        ],
    )
    def test_invalid_hostname_syntax_rejected_before_download(self, url: str) -> None:
        with pytest.raises(ValueError, match="valid host"):
            URLSkillRepository(url)

    @pytest.mark.parametrize(
        "url",
        [
            "https://example.com/x zip?token=top-secret",
            "https://example.com/%invalid?token=top-secret",
            "https://[fe80::1%eth0]/x.zip?token=top-secret",
            "https://example.com/skills[1].zip?token=top-secret",
            "https:user:password?token=top-secret",
        ],
    )
    def test_malformed_url_is_rejected_without_leaking_query(self, url: str) -> None:
        with pytest.raises(ValueError) as exc_info:
            URLSkillRepository(url)
        assert "password" not in str(exc_info.value)
        assert "top-secret" not in str(exc_info.value)

    def test_user_info_is_rejected_without_leaking_secrets(self) -> None:
        url = "https://user:password@example.com/x.zip?token=top-secret"
        with pytest.raises(ValueError, match="must not include user info") as exc_info:
            URLSkillRepository(url)
        assert "password" not in str(exc_info.value)
        assert "top-secret" not in str(exc_info.value)

    def test_malformed_sha256_rejected_before_download(self) -> None:
        with pytest.raises(ValueError, match="64 hexadecimal"):
            URLSkillRepository(
                "http://127.0.0.1:1/skills.zip",
                sha256="invalid",
                allow_insecure_http=True,
            )

    def test_404_error(self, zip_server: "tuple[str, type[_ZipHandler]]") -> None:
        url, handler = zip_server
        handler.status = 404

        with pytest.raises(HTTPError) as exc_info:
            URLSkillRepository(f"{url}?token=top-secret", allow_insecure_http=True)
        assert "top-secret" not in str(exc_info.value)
