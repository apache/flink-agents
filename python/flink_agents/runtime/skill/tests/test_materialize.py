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

import logging
import struct
import tempfile
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.error import HTTPError

import pytest

from flink_agents.api.skills import redact_skill_url
from flink_agents.runtime.skill.repository._materialize import (
    DEFAULT_LIMITS,
    Materialized,
    MaterializerLimits,
    download_to_tempfile,
    extract_zip_safely,
    limits_from_config,
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
    redirect_status: int = 302
    redirect_location: str | None = None
    request_count: int = 0

    def do_GET(self) -> None:
        type(self).request_count += 1
        is_chain = self.path.startswith("/chain/")
        is_redirect = self.path.startswith("/redirect") and (
            type(self).redirect_location is not None
        )
        self.send_response(
            type(self).redirect_status if is_redirect or is_chain else type(self).status
        )
        if is_redirect:
            self.send_header("Location", type(self).redirect_location)
        elif is_chain:
            step = int(self.path.rsplit("/", 1)[-1])
            self.send_header("Location", f"/chain/{step + 1}")
        self.send_header("Content-Length", str(len(type(self).payload)))
        self.end_headers()
        self.wfile.write(type(self).payload)

    def log_message(self, *_args: object) -> None:
        pass


@pytest.fixture
def static_server() -> "tuple[str, type[_StaticHandler]]":
    _StaticHandler.payload = b""
    _StaticHandler.status = 200
    _StaticHandler.redirect_status = 302
    _StaticHandler.redirect_location = None
    _StaticHandler.request_count = 0
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
        _StaticHandler.redirect_status = 302
        _StaticHandler.redirect_location = None
        _StaticHandler.request_count = 0


class TestDownloadToTempfile:
    def test_redact_skill_url_redacts_opaque_malformed_credentials(self) -> None:
        assert redact_skill_url("https:user:password?token=top-secret") == "<redacted>"

    def test_redact_skill_url_rejects_control_characters(self) -> None:
        assert (
            redact_skill_url("https://u:pw@example.com/a\x1b[31mred?token=top-secret")
            == "<redacted>"
        )

    def test_downloads_bytes(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server
        handler.payload = b"hello-zip-bytes"
        handler.status = 200

        path = download_to_tempfile(
            f"{base_url}/anything", timeout=10, allow_insecure_http=True
        )

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
            download_to_tempfile(
                f"{base_url}/missing", timeout=10, allow_insecure_http=True
            )


# ---------------------------------------------------------------------------
# Helpers for size-cap tests
# ---------------------------------------------------------------------------


def _make_streaming_server(
    declared_content_length: int | None, bytes_to_stream: int
) -> tuple[str, HTTPServer]:
    class _StreamingHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            self.send_response(200)
            if declared_content_length is not None:
                self.send_header("Content-Length", str(declared_content_length))
            self.end_headers()
            chunk = b"x" * 65536
            remaining = bytes_to_stream
            while remaining > 0:
                to_write = min(len(chunk), remaining)
                try:
                    self.wfile.write(chunk[:to_write])
                    self.wfile.flush()
                except OSError:
                    break
                remaining -= to_write

        def log_message(self, *_args: object) -> None:
            pass

    server = HTTPServer(("127.0.0.1", 0), _StreamingHandler)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{port}", server


def _make_zip_with_patched_declared_sizes(
    zip_path: Path, entries: dict[str, bytes], declared_size: int
) -> None:
    r"""Writing a ZIP and patching the declared uncompressed size in both the local file
    header (LFH, ``PK\x03\x04``) and the central directory header (CDH, ``PK\x01\x02``)
    for every entry.

    Patching only the CDH is insufficient for the per-entry streaming test: Python's
    ``zipfile`` reads the LFH declared size to bound how many bytes it decompresses, so
    leaving the LFH intact causes a CRC mismatch before the byte counter is reached.
    Patching both headers lets the full real payload flow through the streaming counter.
    """
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED) as zf:
        for name, content in entries.items():
            zf.writestr(name, content)
    data = bytearray(zip_path.read_bytes())
    # Patch local file headers (PK\x03\x04).
    # offset +22 = uncompressed size (used for the declared-size per check)
    # We patch ONLY offset +22 so the pre-check sees the declared_size value,
    # while offset +18 (compressed size) keeps the real payload length intact.
    lfh_sig = b"PK\x03\x04"
    pos = 0
    while pos < len(data) - 30:
        if data[pos : pos + 4] == lfh_sig:
            compress_size = struct.unpack_from("<I", data, pos + 18)[0]
            # Patch uncompressed size only - compressed size stays at real length.
            struct.pack_into("<I", data, pos + 22, declared_size)
            fn_len = struct.unpack_from("<H", data, pos + 26)[0]
            extra_len = struct.unpack_from("<H", data, pos + 28)[0]
            pos += 30 + fn_len + extra_len + compress_size
        else:
            pos += 1
    # Patch central directory headers (PK\x01\x02): uncompressed size at offset +24
    cdh_sig = b"PK\x01\x02"
    pos = 0
    while pos < len(data) - 46:
        if data[pos : pos + 4] == cdh_sig:
            struct.pack_into("<I", data, pos + 24, declared_size)
            fn_len = struct.unpack_from("<H", data, pos + 28)[0]
            extra_len = struct.unpack_from("<H", data, pos + 30)[0]
            comment_len = struct.unpack_from("<H", data, pos + 32)[0]
            pos += 46 + fn_len + extra_len + comment_len
        else:
            pos += 1
    zip_path.write_bytes(data)


def _make_zip_with_bad_crc(zip_path: Path, entries: dict[str, bytes]) -> None:
    """Write a ZIP and corrupt the CRC-32 in both LFH and CDH for every entry.

    Used by tests that need extraction to fail with an integrity error under the
    raw-read path (which reads ``compress_size`` bytes from disk and validates CRC
    manually).  Patching the CRC to ``0x00000000`` guarantees a mismatch for any
    non-empty entry, regardless of the entry's declared or actual size.

    LFH CRC-32 is at offset +14; CDH CRC-32 is at offset +16.
    """
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED) as zf:
        for name, content in entries.items():
            zf.writestr(name, content)
    data = bytearray(zip_path.read_bytes())
    lfh_sig = b"PK\x03\x04"
    pos = 0
    while pos < len(data) - 30:
        if data[pos : pos + 4] == lfh_sig:
            compress_size = struct.unpack_from("<I", data, pos + 18)[0]
            struct.pack_into("<I", data, pos + 14, 0xDEADBEEF)  # corrupt CRC
            fn_len = struct.unpack_from("<H", data, pos + 26)[0]
            extra_len = struct.unpack_from("<H", data, pos + 28)[0]
            pos += 30 + fn_len + extra_len + compress_size
        else:
            pos += 1
    cdh_sig = b"PK\x01\x02"
    pos = 0
    while pos < len(data) - 46:
        if data[pos : pos + 4] == cdh_sig:
            struct.pack_into("<I", data, pos + 16, 0xDEADBEEF)  # corrupt CRC
            fn_len = struct.unpack_from("<H", data, pos + 28)[0]
            extra_len = struct.unpack_from("<H", data, pos + 30)[0]
            comment_len = struct.unpack_from("<H", data, pos + 32)[0]
            pos += 46 + fn_len + extra_len + comment_len
        else:
            pos += 1
    zip_path.write_bytes(data)


# ---------------------------------------------------------------------------
# Download size cap tests
# ---------------------------------------------------------------------------


class TestDownloadSizeCap:
    def test_rejects_declared_content_length_over_cap(self) -> None:
        cap = 1024
        limits = MaterializerLimits(
            max_download_bytes=cap,
            max_extract_entry_bytes=DEFAULT_LIMITS.max_extract_entry_bytes,
            max_extract_total_bytes=DEFAULT_LIMITS.max_extract_total_bytes,
            max_extract_entries=DEFAULT_LIMITS.max_extract_entries,
        )
        url, server = _make_streaming_server(
            declared_content_length=cap + 1, bytes_to_stream=0
        )
        try:
            with pytest.raises(ValueError, match="exceeding the limit"):
                download_to_tempfile(
                    url, timeout=10, allow_insecure_http=True, limits=limits
                )
        finally:
            server.shutdown()

    def test_rejects_understated_content_length_via_byte_counter(self) -> None:
        cap = 1024
        limits = MaterializerLimits(
            max_download_bytes=cap,
            max_extract_entry_bytes=DEFAULT_LIMITS.max_extract_entry_bytes,
            max_extract_total_bytes=DEFAULT_LIMITS.max_extract_total_bytes,
            max_extract_entries=DEFAULT_LIMITS.max_extract_entries,
        )
        url, server = _make_streaming_server(
            declared_content_length=None, bytes_to_stream=cap + 1
        )
        try:
            with pytest.raises(ValueError, match="exceeded the limit"):
                download_to_tempfile(
                    url, timeout=10, allow_insecure_http=True, limits=limits
                )
        finally:
            server.shutdown()

    def test_rejects_stream_with_no_content_length_and_body_over_cap(self) -> None:
        cap = 1024
        limits = MaterializerLimits(
            max_download_bytes=cap,
            max_extract_entry_bytes=DEFAULT_LIMITS.max_extract_entry_bytes,
            max_extract_total_bytes=DEFAULT_LIMITS.max_extract_total_bytes,
            max_extract_entries=DEFAULT_LIMITS.max_extract_entries,
        )
        url, server = _make_streaming_server(
            declared_content_length=None, bytes_to_stream=cap + 1
        )
        try:
            with pytest.raises(ValueError, match="exceeded the limit"):
                download_to_tempfile(
                    url, timeout=10, allow_insecure_http=True, limits=limits
                )
        finally:
            server.shutdown()

    def test_accepts_body_exactly_at_cap(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        cap = 1024
        limits = MaterializerLimits(
            max_download_bytes=cap,
            max_extract_entry_bytes=DEFAULT_LIMITS.max_extract_entry_bytes,
            max_extract_total_bytes=DEFAULT_LIMITS.max_extract_total_bytes,
            max_extract_entries=DEFAULT_LIMITS.max_extract_entries,
        )
        base_url, handler = static_server
        handler.payload = b"z" * cap
        handler.status = 200
        path = download_to_tempfile(
            f"{base_url}/skill.zip", timeout=10, allow_insecure_http=True, limits=limits
        )
        try:
            assert path.stat().st_size == cap
        finally:
            path.unlink(missing_ok=True)

    def test_rejects_body_one_byte_over_download_cap(self) -> None:
        cap = 1024
        limits = MaterializerLimits(
            max_download_bytes=cap,
            max_extract_entry_bytes=DEFAULT_LIMITS.max_extract_entry_bytes,
            max_extract_total_bytes=DEFAULT_LIMITS.max_extract_total_bytes,
            max_extract_entries=DEFAULT_LIMITS.max_extract_entries,
        )
        url, server = _make_streaming_server(
            declared_content_length=None, bytes_to_stream=cap + 1
        )
        try:
            with pytest.raises(ValueError, match=str(cap)):
                download_to_tempfile(
                    url, timeout=10, allow_insecure_http=True, limits=limits
                )
        finally:
            server.shutdown()

    def test_cleanup_on_download_failure(self) -> None:
        cap = 1024
        limits = MaterializerLimits(
            max_download_bytes=cap,
            max_extract_entry_bytes=DEFAULT_LIMITS.max_extract_entry_bytes,
            max_extract_total_bytes=DEFAULT_LIMITS.max_extract_total_bytes,
            max_extract_entries=DEFAULT_LIMITS.max_extract_entries,
        )
        url, server = _make_streaming_server(
            declared_content_length=cap + 1, bytes_to_stream=0
        )
        tmp_dir = Path(tempfile.gettempdir())
        try:
            before = sum(
                1
                for p in tmp_dir.iterdir()
                if p.name.startswith("flink-agents-skills-") and p.suffix == ".zip"
            )
            with pytest.raises(ValueError):
                download_to_tempfile(
                    url, timeout=10, allow_insecure_http=True, limits=limits
                )
            after = sum(
                1
                for p in tmp_dir.iterdir()
                if p.name.startswith("flink-agents-skills-") and p.suffix == ".zip"
            )
            assert before == after
        finally:
            server.shutdown()


# ---------------------------------------------------------------------------
# Extraction size cap tests
# ---------------------------------------------------------------------------


class TestExtractionSizeCap:
    def test_rejects_archive_with_too_many_entries(self, tmp_path: Path) -> None:
        limits = MaterializerLimits(
            max_download_bytes=DEFAULT_LIMITS.max_download_bytes,
            max_extract_entry_bytes=DEFAULT_LIMITS.max_extract_entry_bytes,
            max_extract_total_bytes=DEFAULT_LIMITS.max_extract_total_bytes,
            max_extract_entries=2,
        )
        zip_path = tmp_path / "many.zip"
        with zipfile.ZipFile(zip_path, "w") as zf:
            for i in range(3):
                zf.writestr(f"entry-{i}.txt", "")
        with pytest.raises(ValueError, match="entries"):
            extract_zip_safely(zip_path, limits=limits)

    def test_rejects_declared_entry_size_over_cap(self, tmp_path: Path) -> None:
        _make_zip_with_patched_declared_sizes(
            tmp_path / "big-declared.zip",
            {"entry.bin": b"x"},
            DEFAULT_LIMITS.max_extract_entry_bytes + 1,
        )
        with pytest.raises(ValueError, match="per-entry limit"):
            extract_zip_safely(tmp_path / "big-declared.zip")

    def test_rejects_actual_bytes_over_per_entry_cap_when_declared_size_passes(
        self, tmp_path: Path
    ) -> None:
        actual_size = 512
        cap = 400
        limits = MaterializerLimits(
            max_download_bytes=DEFAULT_LIMITS.max_download_bytes,
            max_extract_entry_bytes=cap,
            max_extract_total_bytes=cap * 10,
            max_extract_entries=DEFAULT_LIMITS.max_extract_entries,
        )
        zip_path = tmp_path / "big-actual.zip"
        _make_zip(zip_path, {"large.bin": "x" * actual_size})
        with pytest.raises(ValueError, match="per-entry limit"):
            extract_zip_safely(zip_path, limits=limits)

    def test_tampered_declared_size_below_actual_raises_integrity_error(
        self, tmp_path: Path
    ) -> None:
        # Simulate an attacker who corrupts the CRC in a zip archive.
        # The extraction path uses zf.open(member) which validates CRC on close();
        # a corrupted CRC raises BadZipFile, which is caught and re-raised as ValueError("integrity check")
        actual_size = 512
        zip_path = tmp_path / "big-actual.zip"
        _make_zip_with_bad_crc(zip_path, {"large.bin": b"x" * actual_size})
        with pytest.raises(ValueError, match="integrity check"):
            extract_zip_safely(zip_path)

    def test_oversized_entry_still_cleans_up(self, tmp_path: Path) -> None:
        actual_size = 512
        cap = 400
        limits = MaterializerLimits(
            max_download_bytes=DEFAULT_LIMITS.max_download_bytes,
            max_extract_entry_bytes=cap,
            max_extract_total_bytes=cap * 10,
            max_extract_entries=DEFAULT_LIMITS.max_extract_entries,
        )
        zip_path = tmp_path / "big-actual.zip"
        _make_zip(zip_path, {"large.bin": "x" * actual_size})
        tmp_dir = Path(tempfile.gettempdir())
        before = sum(
            1
            for p in tmp_dir.iterdir()
            if p.name.startswith("flink-agents-skills-") and p.is_dir()
        )
        with pytest.raises(ValueError, match="per-entry limit"):
            extract_zip_safely(zip_path, limits=limits)
        after = sum(
            1
            for p in tmp_dir.iterdir()
            if p.name.startswith("flink-agents-skills-") and p.is_dir()
        )
        assert before == after

    def test_rejects_cumulative_bytes_over_total_cap(self, tmp_path: Path) -> None:
        entry_size = 200
        limits = MaterializerLimits(
            max_download_bytes=DEFAULT_LIMITS.max_download_bytes,
            max_extract_entry_bytes=250,
            max_extract_total_bytes=300,
            max_extract_entries=DEFAULT_LIMITS.max_extract_entries,
        )
        zip_path = tmp_path / "cumulative.zip"
        entries = {f"entry-{i}.bin": "B" * entry_size for i in range(2)}
        _make_zip(zip_path, entries)
        with pytest.raises(ValueError, match="total"):
            extract_zip_safely(zip_path, limits=limits)

    def test_cleanup_on_extraction_failure(self, tmp_path: Path) -> None:
        limits = MaterializerLimits(
            max_download_bytes=DEFAULT_LIMITS.max_download_bytes,
            max_extract_entry_bytes=DEFAULT_LIMITS.max_extract_entry_bytes,
            max_extract_total_bytes=DEFAULT_LIMITS.max_extract_total_bytes,
            max_extract_entries=2,
        )
        zip_path = tmp_path / "many.zip"
        with zipfile.ZipFile(zip_path, "w") as zf:
            for i in range(3):
                zf.writestr(f"e{i}.txt", "")
        tmp_dir = Path(tempfile.gettempdir())
        before = sum(
            1
            for p in tmp_dir.iterdir()
            if p.name.startswith("flink-agents-skills-") and p.is_dir()
        )
        with pytest.raises(ValueError):
            extract_zip_safely(zip_path, limits=limits)
        after = sum(
            1
            for p in tmp_dir.iterdir()
            if p.name.startswith("flink-agents-skills-") and p.is_dir()
        )
        assert before == after

    def test_rejects_plain_http_by_default(self) -> None:
        with pytest.raises(ValueError, match="disabled by default"):
            download_to_tempfile("http://127.0.0.1:1/anything", timeout=10)

    def test_rejects_scoped_ipv6_before_connection(self) -> None:
        with pytest.raises(
            ValueError, match="must not include an IPv6 zone identifier"
        ):
            download_to_tempfile("https://[fe80::1%25lo0]/skills.zip", timeout=10)

    def test_rejects_cross_protocol_redirect_before_request(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server
        handler.redirect_location = "https://127.0.0.1:1/skills.zip"

        with pytest.raises(
            ValueError, match=r"unsupported redirect.*https://127\.0\.0\.1:1"
        ):
            download_to_tempfile(
                f"{base_url}/redirect", timeout=10, allow_insecure_http=True
            )

    def test_follows_308_redirect(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server
        handler.payload = b"redirected-zip-bytes"
        handler.redirect_status = 308
        handler.redirect_location = f"{base_url}/skills.zip"

        path = download_to_tempfile(
            f"{base_url}/redirect", timeout=10, allow_insecure_http=True
        )
        try:
            assert path.read_bytes() == b"redirected-zip-bytes"
        finally:
            path.unlink(missing_ok=True)

    def test_rejects_redirect_user_info_without_leaking_secrets(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server
        # A credential on a live host is indistinguishable from a real leak, so
        # use the reserved example.com (RFC 2606). As with the cross-protocol
        # case, the target is rejected before the redirect is followed.
        handler.redirect_location = (
            "http://user:password@example.com/skills.zip?token=top-secret"
        )

        with pytest.raises(ValueError, match="must not include user info") as exc_info:
            download_to_tempfile(
                f"{base_url}/redirect", timeout=10, allow_insecure_http=True
            )
        assert "password" not in str(exc_info.value)
        assert "top-secret" not in str(exc_info.value)

    def test_rejects_fifth_repeat_of_redirect_target(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server
        handler.redirect_location = f"{base_url}/redirect"

        with pytest.raises(HTTPError):
            download_to_tempfile(
                f"{base_url}/redirect", timeout=10, allow_insecure_http=True
            )
        assert handler.request_count == 5

    def test_rejects_eleventh_distinct_redirect(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server

        with pytest.raises(HTTPError):
            download_to_tempfile(
                f"{base_url}/chain/0", timeout=10, allow_insecure_http=True
            )
        assert handler.request_count == 11

    def test_rejects_redirect_location_with_raw_space(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server
        handler.redirect_location = f"{base_url}/skills archive.zip"

        with pytest.raises(ValueError, match="Invalid skill URL"):
            download_to_tempfile(
                f"{base_url}/redirect", timeout=10, allow_insecure_http=True
            )
        assert handler.request_count == 1

    def test_logs_sanitized_effective_url_for_same_protocol_redirect(
        self,
        static_server: "tuple[str, type[_StaticHandler]]",
        caplog: pytest.LogCaptureFixture,
    ) -> None:
        base_url, handler = static_server
        handler.payload = b"redirected-zip-bytes"
        handler.redirect_location = (
            f"{base_url}/skills.zip?redirect_token=secret#redirect-fragment"
        )

        configured_url = f"{base_url}/redirect?configured_token=secret"
        with caplog.at_level(
            logging.WARNING,
            logger="flink_agents.runtime.skill.repository._materialize",
        ):
            path = download_to_tempfile(
                configured_url, timeout=10, allow_insecure_http=True
            )

        try:
            assert path.read_bytes() == b"redirected-zip-bytes"
            warning = "\n".join(caplog.messages)
            assert f"{base_url}/redirect" in warning
            assert f"{base_url}/skills.zip" in warning
            assert "configured_token" not in warning
            assert "redirect_token" not in warning
            assert "redirect-fragment" not in warning
        finally:
            path.unlink(missing_ok=True)


class TestLimitsFromConfig:
    """Verify that limits_from_config reads all four YAML keys from a real config object.

    Every test constructs an AgentConfiguration with explicit values for the four
    skill-source limit keys and asserts that limits_from_config produces a
    MaterializerLimits whose fields match.  A typo in any key string in
    AgentConfigOptions would cause limits_from_config to silently fall back to the
    default value and the equality assertion would fail.
    """

    def _make_config(
        self,
        *,
        max_download_bytes: int,
        max_extract_entry_bytes: int,
        max_extract_total_bytes: int,
        max_extract_entries: int,
    ) -> object:
        """Build a real AgentConfiguration populated with the four limit keys."""
        from flink_agents.api.core_options import AgentConfigOptions as _opts
        from flink_agents.plan.configuration import AgentConfiguration

        cfg = AgentConfiguration()
        cfg.set(_opts.SKILL_SOURCE_URL_MAX_DOWNLOAD_BYTES, max_download_bytes)
        cfg.set(_opts.SKILL_SOURCE_URL_MAX_EXTRACT_ENTRY_BYTES, max_extract_entry_bytes)
        cfg.set(_opts.SKILL_SOURCE_URL_MAX_EXTRACT_TOTAL_BYTES, max_extract_total_bytes)
        cfg.set(_opts.SKILL_SOURCE_URL_MAX_EXTRACT_ENTRIES, max_extract_entries)
        return cfg

    def test_reads_all_four_keys(self) -> None:
        """limits_from_config must map each YAML key to the correct field."""
        cfg = self._make_config(
            max_download_bytes=11,
            max_extract_entry_bytes=22,
            max_extract_total_bytes=33,
            max_extract_entries=44,
        )
        limits = limits_from_config(cfg)

        assert limits.max_download_bytes == 11, (
            "limits_from_config must read skill.source.url.max-download-bytes"
        )
        assert limits.max_extract_entry_bytes == 22, (
            "limits_from_config must read skill.source.url.max-extract-entry-bytes"
        )
        assert limits.max_extract_total_bytes == 33, (
            "limits_from_config must read skill.source.url.max-extract-total-bytes"
        )
        assert limits.max_extract_entries == 44, (
            "limits_from_config must read skill.source.url.max-extract-entries"
        )

    def test_default_values_when_keys_absent(self) -> None:
        """An empty config must produce limits equal to DEFAULT_LIMITS."""
        from flink_agents.plan.configuration import AgentConfiguration

        cfg = AgentConfiguration()
        limits = limits_from_config(cfg)

        assert limits.max_download_bytes == DEFAULT_LIMITS.max_download_bytes
        assert limits.max_extract_entry_bytes == DEFAULT_LIMITS.max_extract_entry_bytes
        assert limits.max_extract_total_bytes == DEFAULT_LIMITS.max_extract_total_bytes
        assert limits.max_extract_entries == DEFAULT_LIMITS.max_extract_entries

    def test_partial_override_leaves_others_at_default(self) -> None:
        """Setting only one key must leave the other three at their defaults."""
        from flink_agents.api.core_options import AgentConfigOptions as _opts
        from flink_agents.plan.configuration import AgentConfiguration

        cfg = AgentConfiguration()
        cfg.set(_opts.SKILL_SOURCE_URL_MAX_DOWNLOAD_BYTES, 999)
        limits = limits_from_config(cfg)

        assert limits.max_download_bytes == 999
        assert limits.max_extract_entry_bytes == DEFAULT_LIMITS.max_extract_entry_bytes
        assert limits.max_extract_total_bytes == DEFAULT_LIMITS.max_extract_total_bytes
        assert limits.max_extract_entries == DEFAULT_LIMITS.max_extract_entries
