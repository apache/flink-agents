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

import io
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
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.skill.repository._materialize import (
    Materialized,
    MaterializerLimits,
    _bounded_copy,
    download_to_tempfile,
    extract_zip_into,
    extract_zip_safely,
    limits_from_config,
)
from flink_agents.runtime.skill.tests.utils import default_limits

_LIMIT_KEYS = (
    ("skill.max-download-bytes", "max_download_bytes", 63),
    ("skill.max-uncompressed-entry-bytes", "max_extract_entry_bytes", 63),
    ("skill.max-uncompressed-total-bytes", "max_extract_total_bytes", 63),
    ("skill.max-archive-entries", "max_extract_entries", 31),
)


def _make_zip(zip_path: Path, entries: dict[str, str]) -> None:
    with zipfile.ZipFile(zip_path, "w") as zf:
        for name, content in entries.items():
            zf.writestr(name, content)


def _small_limits(*, entry: int, total: int) -> MaterializerLimits:
    return MaterializerLimits(
        max_download_bytes=default_limits().max_download_bytes,
        max_extract_entry_bytes=entry,
        max_extract_total_bytes=total,
        max_extract_entries=default_limits().max_extract_entries,
    )


def _download_cap(cap: int) -> MaterializerLimits:
    return MaterializerLimits(
        max_download_bytes=cap,
        max_extract_entry_bytes=default_limits().max_extract_entry_bytes,
        max_extract_total_bytes=default_limits().max_extract_total_bytes,
        max_extract_entries=default_limits().max_extract_entries,
    )


def _count_skill_temp_dirs(tmp_dir: Path) -> int:
    return sum(
        1
        for p in tmp_dir.iterdir()
        if p.name.startswith("flink-agents-skills-") and p.is_dir()
    )


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

        with extract_zip_safely(zip_path, limits=default_limits()) as m:
            extract_dir = m.dir
            assert extract_dir.is_dir()
            assert (extract_dir / "skill-a" / "SKILL.md").read_text().startswith("---")
            assert (extract_dir / "skill-b" / "SKILL.md").is_file()

    @pytest.mark.parametrize("entry", ["../evil.txt", "/etc/evil.txt"])
    def test_rejects_zip_slip_and_cleans_up(self, tmp_path: Path, entry: str) -> None:
        zip_path = tmp_path / "evil.zip"
        _make_zip(zip_path, {entry: "pwn"})
        tmp_dir = Path(tempfile.gettempdir())
        before = _count_skill_temp_dirs(tmp_dir)
        with pytest.raises(ValueError, match="Unsafe zip entry"):
            extract_zip_safely(zip_path, limits=default_limits())
        assert _count_skill_temp_dirs(tmp_dir) == before


class TestExtractZipInto:
    """``extract_zip_into`` is the public entry point used by package skills."""

    def test_extracts_into_caller_owned_dir(self, tmp_path: Path) -> None:
        zip_path = tmp_path / "skills.zip"
        _make_zip(zip_path, {"skill-a/SKILL.md": "---\nname: skill-a\n---\nbody"})
        target = tmp_path / "target"

        extract_zip_into(zip_path, target, limits=default_limits())

        assert (target / "skill-a" / "SKILL.md").is_file()

    def test_rejects_zip_slip(self, tmp_path: Path) -> None:
        zip_path = tmp_path / "evil.zip"
        _make_zip(zip_path, {"../evil.txt": "pwn"})

        with pytest.raises(ValueError, match="Unsafe zip entry"):
            extract_zip_into(zip_path, tmp_path / "target", limits=default_limits())
        assert not (tmp_path / "evil.txt").exists()

    def test_enforces_limits(self, tmp_path: Path) -> None:
        zip_path = tmp_path / "many.zip"
        _make_zip(zip_path, {f"s/{i}.md": "x" for i in range(3)})
        limits = MaterializerLimits(
            max_download_bytes=1024,
            max_extract_entry_bytes=1024,
            max_extract_total_bytes=1024,
            max_extract_entries=2,
        )

        with pytest.raises(ValueError, match="exceeding the limit of 2"):
            extract_zip_into(zip_path, tmp_path / "target", limits=limits)

    def test_package_materializer_extracts_zip_resource(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        # Regression: package_materializer imports extract_zip_into; removing it
        # broke every import of the package skill path.
        from flink_agents.runtime.skill.repository.package_materializer import (
            materialize_package,
        )

        pkg_dir = tmp_path / "pkgroot" / "fake_skill_pkg"
        pkg_dir.mkdir(parents=True)
        (pkg_dir / "__init__.py").write_text("")
        _make_zip(
            pkg_dir / "skills.zip",
            {"skill-a/SKILL.md": "---\nname: skill-a\n---\nbody"},
        )
        monkeypatch.syspath_prepend(str(tmp_path / "pkgroot"))
        target = tmp_path / "target"
        target.mkdir()

        materialize_package(
            "fake_skill_pkg", "skills.zip", str(target), limits=default_limits()
        )

        assert (target / "skill-a" / "SKILL.md").is_file()


class TestMaterialized:
    def test_close_removes_dir(self, tmp_path: Path) -> None:
        zip_path = tmp_path / "skills.zip"
        _make_zip(zip_path, {"skill-a/SKILL.md": "---\nname: skill-a\n---\nbody"})
        m = extract_zip_safely(zip_path, limits=default_limits())
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
            f"{base_url}/anything",
            timeout=10,
            allow_insecure_http=True,
            limits=default_limits(),
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
                f"{base_url}/missing",
                timeout=10,
                allow_insecure_http=True,
                limits=default_limits(),
            )

    def test_rejects_plain_http_by_default(self) -> None:
        with pytest.raises(ValueError, match="disabled by default"):
            download_to_tempfile(
                "http://127.0.0.1:1/anything", timeout=10, limits=default_limits()
            )

    def test_rejects_scoped_ipv6_before_connection(self) -> None:
        with pytest.raises(
            ValueError, match="must not include an IPv6 zone identifier"
        ):
            download_to_tempfile(
                "https://[fe80::1%25lo0]/skills.zip",
                timeout=10,
                limits=default_limits(),
            )

    def test_rejects_cross_protocol_redirect_before_request(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server
        handler.redirect_location = "https://127.0.0.1:1/skills.zip"

        with pytest.raises(
            ValueError, match=r"unsupported redirect.*https://127\.0\.0\.1:1"
        ):
            download_to_tempfile(
                f"{base_url}/redirect",
                timeout=10,
                allow_insecure_http=True,
                limits=default_limits(),
            )

    def test_follows_308_redirect(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server
        handler.payload = b"redirected-zip-bytes"
        handler.redirect_status = 308
        handler.redirect_location = f"{base_url}/skills.zip"

        path = download_to_tempfile(
            f"{base_url}/redirect",
            timeout=10,
            allow_insecure_http=True,
            limits=default_limits(),
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
                f"{base_url}/redirect",
                timeout=10,
                allow_insecure_http=True,
                limits=default_limits(),
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
                f"{base_url}/redirect",
                timeout=10,
                allow_insecure_http=True,
                limits=default_limits(),
            )
        assert handler.request_count == 5

    def test_rejects_eleventh_distinct_redirect(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server

        with pytest.raises(HTTPError):
            download_to_tempfile(
                f"{base_url}/chain/0",
                timeout=10,
                allow_insecure_http=True,
                limits=default_limits(),
            )
        assert handler.request_count == 11

    def test_rejects_redirect_location_with_raw_space(
        self, static_server: "tuple[str, type[_StaticHandler]]"
    ) -> None:
        base_url, handler = static_server
        handler.redirect_location = f"{base_url}/skills archive.zip"

        with pytest.raises(ValueError, match="Invalid skill URL"):
            download_to_tempfile(
                f"{base_url}/redirect",
                timeout=10,
                allow_insecure_http=True,
                limits=default_limits(),
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
                configured_url,
                timeout=10,
                allow_insecure_http=True,
                limits=default_limits(),
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
    r"""Write a ZIP and patch the declared uncompressed size in both the local file
    header (LFH, ``PK\x03\x04``) and the central directory header (CDH, ``PK\x01\x02``)
    for every entry.

    A declared size below the real one passes the size pre-check, and the forged-size
    tests expect CRC validation to reject the archive. The byte counters are tested
    through ``_bounded_copy``.
    """
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name, content in entries.items():
            zf.writestr(name, content)

    data = bytearray(zip_path.read_bytes())

    # Patch local file headers (PK\x03\x04): uncompressed size is at offset +22.
    lfh_sig = b"PK\x03\x04"
    pos = 0
    while pos < len(data) - 30:
        if data[pos : pos + 4] == lfh_sig:
            compress_size = struct.unpack_from("<I", data, pos + 18)[0]
            struct.pack_into("<I", data, pos + 22, declared_size)
            # Advance past this header (30 bytes fixed + filename + extra).
            fn_len = struct.unpack_from("<H", data, pos + 26)[0]
            extra_len = struct.unpack_from("<H", data, pos + 28)[0]
            pos += 30 + fn_len + extra_len + compress_size
        else:
            pos += 1

    # Patch central directory headers (PK\x01\x02): uncompressed size is at offset +24.
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


# ---------------------------------------------------------------------------
# Download size cap tests
# ---------------------------------------------------------------------------


class TestDownloadSizeCap:
    @pytest.mark.parametrize(
        ("declared_size", "body_size", "message"),
        [(1025, 0, "exceeding the limit"), (None, 1025, "exceeded the limit")],
        ids=["declared-size", "streamed-size"],
    )
    def test_rejects_oversized_download_and_cleans_up(
        self, declared_size: int | None, body_size: int, message: str
    ) -> None:
        url, server = _make_streaming_server(declared_size, body_size)
        tmp_dir = Path(tempfile.gettempdir())
        before = set(tmp_dir.glob("flink-agents-skills-*.zip"))
        try:
            with pytest.raises(ValueError, match=message):
                download_to_tempfile(
                    url,
                    timeout=10,
                    allow_insecure_http=True,
                    limits=_download_cap(1024),
                )
            assert set(tmp_dir.glob("flink-agents-skills-*.zip")) == before
        finally:
            server.shutdown()
            server.server_close()

    @pytest.mark.parametrize(
        "declared_size", [1024, None], ids=["declared-size", "streamed-size"]
    )
    def test_accepts_body_exactly_at_cap(self, declared_size: int | None) -> None:
        url, server = _make_streaming_server(declared_size, 1024)
        try:
            path = download_to_tempfile(
                url, timeout=10, allow_insecure_http=True, limits=_download_cap(1024)
            )
            try:
                assert path.stat().st_size == 1024
            finally:
                path.unlink()
        finally:
            server.shutdown()
            server.server_close()


# ---------------------------------------------------------------------------
# Extraction size cap tests
# ---------------------------------------------------------------------------


class TestExtractionSizeCap:
    def test_rejects_archive_with_too_many_entries_and_cleans_up(
        self, tmp_path: Path
    ) -> None:
        limits = MaterializerLimits(
            max_download_bytes=default_limits().max_download_bytes,
            max_extract_entry_bytes=default_limits().max_extract_entry_bytes,
            max_extract_total_bytes=default_limits().max_extract_total_bytes,
            max_extract_entries=2,
        )
        zip_path = tmp_path / "many.zip"
        with zipfile.ZipFile(zip_path, "w") as zf:
            for i in range(3):
                zf.writestr(f"entry-{i}.txt", "")
        tmp_dir = Path(tempfile.gettempdir())
        before = _count_skill_temp_dirs(tmp_dir)
        with pytest.raises(ValueError, match="entries"):
            extract_zip_safely(zip_path, limits=limits)
        assert _count_skill_temp_dirs(tmp_dir) == before

    @pytest.mark.parametrize(
        ("actual_size", "declared_size", "message"),
        [(1, 401, "per-entry limit"), (512, 1, "failed integrity check")],
        ids=["declared-size", "forged-size-crc"],
    )
    def test_rejects_invalid_entry_and_cleans_up(
        self, tmp_path: Path, actual_size: int, declared_size: int, message: str
    ) -> None:
        # CPython rejects the forged size via CRC validation, before the byte limit.
        zip_path = tmp_path / "invalid.zip"
        _make_zip_with_patched_declared_sizes(
            zip_path, {"entry.bin": b"x" * actual_size}, declared_size
        )
        limits = _small_limits(entry=400, total=4000)
        tmp_dir = Path(tempfile.gettempdir())
        before = _count_skill_temp_dirs(tmp_dir)
        with pytest.raises(ValueError, match=message):
            extract_zip_safely(zip_path, limits=limits)
        assert _count_skill_temp_dirs(tmp_dir) == before

    def test_accepts_entries_exactly_at_total_cap(self, tmp_path: Path) -> None:
        limits = _small_limits(entry=600, total=1200)
        zip_path = tmp_path / "at-cap.zip"
        _make_zip(zip_path, {"a.bin": "A" * 600, "b.bin": "B" * 600})
        with extract_zip_safely(zip_path, limits=limits) as m:
            assert (m.dir / "a.bin").stat().st_size == 600
            assert (m.dir / "b.bin").stat().st_size == 600


class TestBoundedCopy:
    """Exercise the streaming byte counters directly, independent of zip metadata."""

    @pytest.mark.parametrize(
        ("size", "entry_limit", "already_written", "total_limit"),
        [(100, 100, 0, 100), (424, 1024, 600, 1024)],
        ids=["entry-boundary", "total-boundary"],
    )
    def test_accepts_exact_limits(
        self, size: int, entry_limit: int, already_written: int, total_limit: int
    ) -> None:
        dst = io.BytesIO()
        written = _bounded_copy(
            io.BytesIO(b"x" * size),
            dst,
            name="e",
            max_entry_bytes=entry_limit,
            total_already_written=already_written,
            max_total_bytes=total_limit,
            chunk_size=7,
        )
        assert written == size
        assert dst.getvalue() == b"x" * size

    @pytest.mark.parametrize(
        ("size", "entry_limit", "already_written", "total_limit", "message"),
        [
            (101, 100, 0, 1000, "'e' exceeds the per-entry limit of 100"),
            (600, 1024, 600, 1024, "total extracted size exceeds the limit of 1024"),
        ],
        ids=["entry-limit", "total-limit"],
    )
    def test_rejects_oversized_copy(
        self,
        size: int,
        entry_limit: int,
        already_written: int,
        total_limit: int,
        message: str,
    ) -> None:
        dst = io.BytesIO()
        with pytest.raises(ValueError, match=message):
            _bounded_copy(
                io.BytesIO(b"x" * size),
                dst,
                name="e",
                max_entry_bytes=entry_limit,
                total_already_written=already_written,
                max_total_bytes=total_limit,
                chunk_size=7,
            )
        assert len(dst.getvalue()) <= min(entry_limit, total_limit - already_written)

    def test_extraction_uses_running_total_not_declared_sizes(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        # rob-9: a valid zip where each file fits but the total exceeds the cap.
        # Disable the declared-size pre-check so only the streaming counter can
        # reject it, and assert the counter's specific message.
        import flink_agents.runtime.skill.repository._materialize as materialize

        monkeypatch.setattr(
            materialize,
            "_validate_zip_members",
            lambda members, extract_dir, limits: None,
        )
        limits = _small_limits(entry=1024, total=1024)
        zip_path = tmp_path / "two-files.zip"
        _make_zip(zip_path, {"a.bin": "A" * 600, "b.bin": "B" * 600})
        with pytest.raises(
            ValueError, match="total extracted size exceeds the limit of 1024"
        ):
            extract_zip_safely(zip_path, limits=limits)


class TestLimitsFromConfig:
    """``limits_from_config`` builds the limits from the ``SkillOptions`` keys."""

    def test_no_keys_returns_default_values(self) -> None:
        limits = limits_from_config(AgentConfiguration())
        assert limits.max_download_bytes == 64 * 1024 * 1024
        assert limits.max_extract_entry_bytes == 64 * 1024 * 1024
        assert limits.max_extract_total_bytes == 256 * 1024 * 1024
        assert limits.max_extract_entries == 1_000

    @pytest.mark.parametrize(
        "values",
        [(11, 12, 13, 14), (1, 1, 1, 1), ("1024", "12", "13", "+5")],
        ids=["numbers", "minimum", "strings"],
    )
    def test_reads_all_four_keys(self, values: tuple) -> None:
        config = AgentConfiguration(
            {
                key: value
                for (key, _attr, _bits), value in zip(_LIMIT_KEYS, values, strict=False)
            }
        )
        limits = limits_from_config(config)
        assert tuple(
            getattr(limits, attr) for _key, attr, _bits in _LIMIT_KEYS
        ) == tuple(map(int, values))

    @pytest.mark.parametrize(("key", "attr", "bits"), _LIMIT_KEYS)
    def test_one_key_set_and_the_rest_default(
        self, key: str, attr: str, bits: int
    ) -> None:
        limits = limits_from_config(AgentConfiguration({key: 7}))
        defaults = default_limits()
        for _other_key, other_attr, _bits in _LIMIT_KEYS:
            assert getattr(limits, other_attr) == (
                7 if other_attr == attr else getattr(defaults, other_attr)
            )

    @pytest.mark.parametrize(("key", "attr", "bits"), _LIMIT_KEYS)
    def test_rejects_non_positive_values(self, key: str, attr: str, bits: int) -> None:
        for value in (0, -1, -(2**bits)):
            with pytest.raises(ValueError, match=f"{attr} must be a strictly positive"):
                limits_from_config(AgentConfiguration({key: value}))

    @pytest.mark.parametrize(
        "value", [True, False, 1.5, 64.0, "64.0", "1e3", "", "ten"]
    )
    def test_rejects_non_integer_values_like_java(self, value: object) -> None:
        # Java parses with Long.parseLong / Integer.parseInt, which reject all of
        # these, so the same config value must not load in one runtime only.
        config = AgentConfiguration({"skill.max-archive-entries": value})
        with pytest.raises(
            ValueError,
            match=r"Invalid value for skill\.max-archive-entries",
        ):
            limits_from_config(config)

    @pytest.mark.parametrize(("key", "attr", "bits"), _LIMIT_KEYS)
    @pytest.mark.parametrize("as_string", [False, True], ids=["number", "string"])
    def test_java_integer_range(
        self, key: str, attr: str, bits: int, as_string: bool
    ) -> None:
        maximum = 2**bits - 1
        value = str(maximum) if as_string else maximum
        limits = limits_from_config(AgentConfiguration({key: value}))
        assert getattr(limits, attr) == maximum
        for outside in (2**bits, -(2**bits) - 1):
            value = str(outside) if as_string else outside
            with pytest.raises(ValueError, match=f"Invalid value for {key}: {outside}"):
                limits_from_config(AgentConfiguration({key: value}))
