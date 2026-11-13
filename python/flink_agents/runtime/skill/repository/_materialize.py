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
"""Internal helpers for materializing skills from non-filesystem sources."""

from __future__ import annotations

import atexit
import logging
import os
import re
import shutil
import tempfile
import zipfile
from pathlib import Path
from typing import TYPE_CHECKING, BinaryIO
from urllib.parse import urljoin
from urllib.request import HTTPRedirectHandler, Request, build_opener

from flink_agents.api.skills import SkillOptions, redact_skill_url, validate_skill_url

if TYPE_CHECKING:
    from typing import Any

    from typing_extensions import Self

    from flink_agents.api.configuration import ConfigOption, ReadableConfiguration

_TEMP_DIR_PREFIX = "flink-agents-skills-"
logger = logging.getLogger(__name__)


class MaterializerLimits:
    """Validated resource limits for a single skill archive download and extraction.

    Use ``limits_from_config`` to read the agent's ``SkillOptions`` once and pass
    the values through to the materializer. All values must be strictly positive
    integers.
    """

    __slots__ = (
        "max_download_bytes",
        "max_extract_entries",
        "max_extract_entry_bytes",
        "max_extract_total_bytes",
    )

    def __init__(
        self,
        *,
        max_download_bytes: int,
        max_extract_entry_bytes: int,
        max_extract_total_bytes: int,
        max_extract_entries: int,
    ) -> None:
        for name, value in (
            ("max_download_bytes", max_download_bytes),
            ("max_extract_entry_bytes", max_extract_entry_bytes),
            ("max_extract_total_bytes", max_extract_total_bytes),
            ("max_extract_entries", max_extract_entries),
        ):
            if not isinstance(value, int) or value <= 0:
                msg = f"MaterializerLimits.{name} must be a strictly positive int, got {value!r}"
                raise ValueError(msg)
        self.max_download_bytes = max_download_bytes
        self.max_extract_entry_bytes = max_extract_entry_bytes
        self.max_extract_total_bytes = max_extract_total_bytes
        self.max_extract_entries = max_extract_entries


_INTEGER_PATTERN = re.compile(r"[+-]?[0-9]+")

#: Value ranges of Java's ``Long`` and ``Integer``, the types of the matching
#: Java options (``Long.parseLong`` / ``Integer.parseInt`` reject values outside them).
_JAVA_LONG_RANGE = (-(2**63), 2**63 - 1)
_JAVA_INT_RANGE = (-(2**31), 2**31 - 1)


def _read_strict_int(
    config: ReadableConfiguration, option: ConfigOption, java_range: tuple[int, int]
) -> int:
    """Read an int option the way Java's ``Long.parseLong``/``Integer.parseInt`` do.

    ``AgentConfiguration.get`` converts with ``int(value)``, which truncates
    ``1.5`` and accepts ``True``. The value is read as its string form instead,
    as Java does, so ``True``, ``1.5`` and ``64.0`` are rejected in both runtimes.
    A value outside ``java_range`` (the Java option type's range) is rejected
    too, so a value too large for Java does not load in Python only.
    """
    raw = config.get_str(option.get_key(), None)
    if raw is None:
        return option.get_default_value()
    if not _INTEGER_PATTERN.fullmatch(raw):
        msg = f"Invalid value for {option.get_key()}: {raw}"
        raise ValueError(msg)
    value = int(raw)
    low, high = java_range
    if not low <= value <= high:
        msg = f"Invalid value for {option.get_key()}: {raw}"
        raise ValueError(msg)
    return value


def limits_from_config(config: ReadableConfiguration) -> MaterializerLimits:
    """Build :class:`MaterializerLimits` from the :class:`SkillOptions` options.

    Mirrors Java's ``SkillMaterializer.Limits.fromConfig``: an absent key
    falls back to its default in ``SkillOptions``.
    Zero or negative values are rejected by :class:`MaterializerLimits`.

    Raises:
        ValueError: If a value is not an integer, is outside the range of the
            matching Java option's type, or is not strictly positive.
    """
    return MaterializerLimits(
        max_download_bytes=_read_strict_int(
            config,
            SkillOptions.MAX_DOWNLOAD_BYTES,
            _JAVA_LONG_RANGE,
        ),
        max_extract_entry_bytes=_read_strict_int(
            config,
            SkillOptions.MAX_UNCOMPRESSED_ENTRY_BYTES,
            _JAVA_LONG_RANGE,
        ),
        max_extract_total_bytes=_read_strict_int(
            config,
            SkillOptions.MAX_UNCOMPRESSED_TOTAL_BYTES,
            _JAVA_LONG_RANGE,
        ),
        max_extract_entries=_read_strict_int(
            config,
            SkillOptions.MAX_ARCHIVE_ENTRIES,
            _JAVA_INT_RANGE,
        ),
    )


class _SameProtocolRedirectHandler(HTTPRedirectHandler):
    """Validate redirect targets and enforce the shared redirect limits."""

    def __init__(
        self, initial_scheme: str, *, allow_insecure_http: bool = False
    ) -> None:
        self._initial_scheme = initial_scheme
        self._allow_insecure_http = allow_insecure_http

    def redirect_request(
        self,
        req: Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> Request | None:
        _require_allowed_transport(
            newurl,
            allow_insecure_http=self._allow_insecure_http,
            initial_scheme=self._initial_scheme,
        )
        # Python 3.10's HTTPRedirectHandler rejects 308 even though it supports
        # the otherwise identical method-preserving behavior for 307.
        compatible_code = 307 if code == 308 else code
        return super().redirect_request(req, fp, compatible_code, msg, headers, newurl)

    def http_error_302(
        self,
        req: Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
    ) -> Any:
        raw_location = headers.get("location") or headers.get("uri")
        if raw_location is not None:
            _require_allowed_transport(
                urljoin(req.full_url, raw_location),
                allow_insecure_http=self._allow_insecure_http,
                initial_scheme=self._initial_scheme,
            )
        return super().http_error_302(req, fp, code, msg, headers)

    http_error_301 = http_error_303 = http_error_307 = http_error_308 = http_error_302


class Materialized:
    """Owns one temp directory plus a fallback atexit cleanup handler.

    :meth:`close` unregisters the handler and removes the dir eagerly;
    it is idempotent. Mirrors Java's ``SkillMaterializer.Materialized``.
    """

    def __init__(self, dir_: Path, *, borrowed: bool = False) -> None:
        """Wrap ``dir_`` as an owned (or borrowed) handle.

        Args:
            dir_: The directory to wrap.
            borrowed: If True, the caller owns the dir; ``close()`` is a
                no-op and no atexit handler is registered.
        """
        self.dir = dir_
        self._closed = False
        self._borrowed = borrowed
        if not borrowed:
            atexit.register(self._cleanup)

    @classmethod
    def borrowed(cls, existing_dir: Path) -> Materialized:
        """Wrap an existing directory the caller does not own."""
        return cls(existing_dir, borrowed=True)

    def _cleanup(self) -> None:
        shutil.rmtree(self.dir, ignore_errors=True)

    def close(self) -> None:
        """Release the temp dir eagerly. Idempotent."""
        if self._closed:
            return
        self._closed = True
        if self._borrowed:
            return
        # atexit.unregister matches by identity; passing the bound method works because
        # the same bound-method instance was registered in __init__.
        atexit.unregister(self._cleanup)
        self._cleanup()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()


def copy_dir_to_temp(src_dir: Path) -> Materialized:
    """Copy ``src_dir`` into a fresh owned tempdir and return a Materialized.

    Used when the caller has transient access to a directory (e.g. inside an
    ``importlib.resources.as_file`` context) and wants a lasting owned copy
    whose lifetime is independent of the original.

    Args:
        src_dir: Source directory to copy.

    Returns:
        A :class:`Materialized` handle owning the copied directory.
    """
    extract_dir = Path(tempfile.mkdtemp(prefix=_TEMP_DIR_PREFIX)).resolve()
    materialized = Materialized(extract_dir)
    try:
        shutil.copytree(src_dir, extract_dir, dirs_exist_ok=True)
    except Exception:
        materialized.close()
        raise
    return materialized


def extract_zip_safely(zip_path: Path, *, limits: MaterializerLimits) -> Materialized:
    """Extract a zip into a fresh temp dir, returning a :class:`Materialized`.

    Each entry is validated against zip-slip. ``close()`` the returned handle
    to free the dir eagerly; an atexit cleanup is the fallback.

    Args:
        zip_path: Path to the zip file to extract.
        limits: Resource limits to enforce during extraction.

    Returns:
        A :class:`Materialized` handle owning the extraction directory.

    Raises:
        ValueError: if any zip entry resolves outside the extraction directory,
            or if any size or entry-count limit is exceeded.
    """
    extract_dir = Path(tempfile.mkdtemp(prefix=_TEMP_DIR_PREFIX)).resolve()
    # Construct the handle before validation so the (empty) tempdir is always reclaimed,
    # even if validation raises.
    materialized = Materialized(extract_dir)
    try:
        _extract_zip_to_dir(zip_path, extract_dir, limits)
    except BaseException:
        materialized.close()
        raise
    return materialized


def extract_zip_into(
    zip_path: Path, target_dir: Path, *, limits: MaterializerLimits
) -> None:
    """Extract into a caller-owned directory with zip-slip checks and resource limits.

    Used by package skill materialization, where the caller (Python or Java)
    owns ``target_dir`` and its cleanup.

    Args:
        zip_path: Path to the zip file to extract.
        target_dir: Caller-owned directory to extract into. Created if missing.
        limits: Resource limits to enforce during extraction.

    Raises:
        ValueError: if any zip entry resolves outside ``target_dir``, or if any
            size or entry-count limit is exceeded.
    """
    target_dir = Path(target_dir)
    target_dir.mkdir(parents=True, exist_ok=True)
    _extract_zip_to_dir(zip_path, target_dir.resolve(), limits)


def _validate_zip_members(
    members: list, extract_dir: Path, limits: MaterializerLimits
) -> None:
    if len(members) > limits.max_extract_entries:
        msg = (
            f"Skill archive contains {len(members)} entries, "
            f"exceeding the limit of {limits.max_extract_entries}"
        )
        raise ValueError(msg)

    for member in members:
        target = (extract_dir / member.filename).resolve()
        if not target.is_relative_to(extract_dir):
            msg = f"Unsafe zip entry: {member.filename}"
            raise ValueError(msg)

    total_declared = 0
    for member in members:
        if member.is_dir():
            continue
        declared = member.file_size
        if declared > limits.max_extract_entry_bytes:
            msg = (
                f"Skill archive entry '{member.filename}' declared size {declared} "
                f"exceeds the per-entry limit of {limits.max_extract_entry_bytes} bytes"
            )
            raise ValueError(msg)
        if declared > 0:
            total_declared += declared
    if total_declared > limits.max_extract_total_bytes:
        msg = (
            f"Skill archive declared total uncompressed size {total_declared} "
            f"exceeds the limit of {limits.max_extract_total_bytes} bytes"
        )
        raise ValueError(msg)


def _bounded_copy(
    src: BinaryIO,
    dst: BinaryIO,
    *,
    name: str,
    max_entry_bytes: int,
    total_already_written: int,
    max_total_bytes: int,
    chunk_size: int = 65536,
) -> int:
    """Stream ``src`` into ``dst``, enforcing per-entry and cumulative byte limits.

    The actual streamed byte count is authoritative: each chunk is checked
    *before* it is written, so the per-entry and cumulative limits hold even
    when the archive's declared sizes are wrong. Bytes are never buffered
    beyond one chunk, so a highly compressed entry cannot exhaust memory.

    Args:
        src: Readable binary stream (for example, ``ZipFile.open(member)``).
        dst: Writable binary stream.
        name: Entry name, used in error messages.
        max_entry_bytes: Maximum bytes this single entry may produce.
        total_already_written: Bytes already written by earlier entries.
        max_total_bytes: Maximum cumulative bytes across all entries.
        chunk_size: Read size per iteration.

    Returns:
        The number of bytes written for this entry.

    Raises:
        ValueError: If the per-entry or cumulative limit would be exceeded.
    """
    written = 0
    while True:
        chunk = src.read(chunk_size)
        if not chunk:
            return written
        if written + len(chunk) > max_entry_bytes:
            msg = (
                f"Skill archive entry '{name}' exceeds the "
                f"per-entry limit of {max_entry_bytes} bytes"
            )
            raise ValueError(msg)
        if total_already_written + written + len(chunk) > max_total_bytes:
            msg = (
                f"Skill archive total extracted size exceeds the limit of "
                f"{max_total_bytes} bytes"
            )
            raise ValueError(msg)
        dst.write(chunk)
        written += len(chunk)


def _extract_zip_to_dir(
    zip_path: Path, extract_dir: Path, limits: MaterializerLimits
) -> None:
    with zipfile.ZipFile(zip_path) as zf:
        members = zf.infolist()
        _validate_zip_members(members, extract_dir, limits)
        total_written = 0
        for member in members:
            target = (extract_dir / member.filename).resolve()
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            try:
                # zf.open streams and decompresses incrementally and validates
                # CRC-32 at end of entry; never inflate a whole entry in memory.
                with zf.open(member) as src, target.open("xb") as dst:
                    total_written += _bounded_copy(
                        src,
                        dst,
                        name=member.filename,
                        max_entry_bytes=limits.max_extract_entry_bytes,
                        total_already_written=total_written,
                        max_total_bytes=limits.max_extract_total_bytes,
                    )
            except zipfile.BadZipFile as exc:
                msg = f"Skill archive entry '{member.filename}' failed integrity check: {exc}"
                raise ValueError(msg) from exc


def _check_declared_download_size(
    content_length: int | None, limits: MaterializerLimits
) -> None:
    if content_length is not None and content_length > limits.max_download_bytes:
        msg = (
            f"Skill archive download size declared as {content_length} bytes, "
            f"exceeding the limit of {limits.max_download_bytes} bytes"
        )
        raise ValueError(msg)


def _check_download_size(
    already_written: int, chunk: int, limits: MaterializerLimits
) -> None:
    if already_written + chunk > limits.max_download_bytes:
        msg = f"Skill archive download exceeded the limit of {limits.max_download_bytes} bytes"
        raise ValueError(msg)


def download_to_tempfile(
    url: str,
    timeout: int = 90,
    *,
    allow_insecure_http: bool = False,
    limits: MaterializerLimits,
) -> Path:
    """Download ``url`` to a temp file and return its path.

    Uses ``urllib.request`` from the standard library. ``timeout`` is the
    socket-level timeout passed to the opener and applies to both the
    connection and the read phases.

    Args:
        url: The URL to download.
        timeout: Socket timeout in seconds.
        allow_insecure_http: Whether the request may use plain HTTP.
        limits: Resource limits to enforce during download.

    Returns:
        Path to the downloaded temp file (caller is responsible for deletion).

    Raises:
        ValueError: If the URL violates the transport policy or a redirect
            changes protocols.
        urllib.error.HTTPError / URLError on HTTP or transport failures.
    """
    initial_scheme = _require_allowed_transport(
        url, allow_insecure_http=allow_insecure_http
    )
    opener = build_opener(
        _SameProtocolRedirectHandler(
            initial_scheme, allow_insecure_http=allow_insecure_http
        )
    )
    req = Request(url, method="GET")
    # The .zip suffix is load-bearing: FileSystemSkillRepository uses
    # path.suffix == ".zip" to detect zip input. Do not change it.
    fd, tmp_path_str = tempfile.mkstemp(prefix=_TEMP_DIR_PREFIX, suffix=".zip")
    os.close(fd)
    tmp_path = Path(tmp_path_str)
    try:
        with opener.open(req, timeout=timeout) as resp, tmp_path.open("wb") as out:
            final_url = resp.geturl()
            _require_allowed_transport(
                final_url,
                allow_insecure_http=allow_insecure_http,
                initial_scheme=initial_scheme,
            )
            if final_url != url:
                logger.warning(
                    "Skill URL redirected from %s to %s",
                    redact_skill_url(url),
                    redact_skill_url(final_url),
                )
            raw_cl = resp.headers.get("Content-Length")
            if raw_cl is not None:
                try:
                    content_length = int(raw_cl)
                except ValueError:
                    content_length = None
                _check_declared_download_size(content_length, limits)
            written = 0
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                _check_download_size(written, len(chunk), limits)
                out.write(chunk)
                written += len(chunk)
    except Exception:
        tmp_path.unlink(missing_ok=True)
        raise
    return tmp_path


def _require_allowed_transport(
    final_url: str,
    *,
    allow_insecure_http: bool,
    initial_scheme: str | None = None,
) -> str:
    if initial_scheme is None:
        return validate_skill_url(final_url, allow_insecure_http=allow_insecure_http)
    # Redirect targets: the scheme-change rule below subsumes the transport
    # policy (matching the Java materializer), so validate leniently first.
    final_scheme = validate_skill_url(final_url, allow_insecure_http=True)
    if final_scheme != initial_scheme:
        msg = (
            "Skill URL returned an unsupported redirect to: "
            f"{redact_skill_url(final_url)}"
        )
        raise ValueError(msg)
    return final_scheme
