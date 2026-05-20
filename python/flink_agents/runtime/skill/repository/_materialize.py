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
import os
import shutil
import tempfile
import zipfile
from pathlib import Path
from typing import TYPE_CHECKING
from urllib.request import Request, urlopen

if TYPE_CHECKING:
    from typing_extensions import Self

_TEMP_DIR_PREFIX = "flink-agents-skills-"


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


def extract_zip_safely(zip_path: Path) -> Materialized:
    """Extract a zip into a fresh temp dir, returning a :class:`Materialized`.

    Each entry is validated against zip-slip. ``close()`` the returned handle
    to free the dir eagerly; an atexit cleanup is the fallback.

    Args:
        zip_path: Path to the zip file to extract.

    Returns:
        A :class:`Materialized` handle owning the extraction directory.

    Raises:
        ValueError: if any zip entry resolves outside the extraction directory.
    """
    extract_dir = Path(tempfile.mkdtemp(prefix=_TEMP_DIR_PREFIX)).resolve()
    # Construct the handle before validation so the (empty) tempdir is always reclaimed,
    # even if validation raises.
    materialized = Materialized(extract_dir)
    with zipfile.ZipFile(zip_path) as zf:
        for member in zf.infolist():
            target = (extract_dir / member.filename).resolve()
            if not target.is_relative_to(extract_dir):
                msg = f"Unsafe zip entry: {member.filename}"
                raise ValueError(msg)
        zf.extractall(extract_dir)
    return materialized


def download_to_tempfile(url: str, timeout: int = 90) -> Path:
    """Download ``url`` to a temp file and return its path.

    Uses ``urllib.request`` from the standard library. ``timeout`` is the
    socket-level timeout passed to ``urlopen`` and applies to both the
    connection and the read phases.

    Args:
        url: The URL to download.
        timeout: Socket timeout in seconds.

    Returns:
        Path to the downloaded temp file (caller is responsible for deletion).

    Raises:
        urllib.error.HTTPError / URLError on HTTP or transport failures.
    """
    req = Request(url, method="GET")
    # The .zip suffix is load-bearing: FileSystemSkillRepository uses
    # path.suffix == ".zip" to detect zip input. Do not change it.
    fd, tmp_path_str = tempfile.mkstemp(prefix=_TEMP_DIR_PREFIX, suffix=".zip")
    os.close(fd)
    tmp_path = Path(tmp_path_str)
    try:
        with urlopen(req, timeout=timeout) as resp, tmp_path.open("wb") as out:
            shutil.copyfileobj(resp, out)
    except Exception:
        tmp_path.unlink(missing_ok=True)
        raise
    return tmp_path
