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

"""Custom PEP 517 build backend that downloads JARs from Maven Central.

Wraps ``setuptools.build_meta`` and overrides ``build_wheel()`` to download
JAR files before the standard setuptools build runs.  This ensures that
``pip install flink-agents`` (from sdist) produces a wheel that already
contains the required JARs.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import urllib.request
from pathlib import Path

from setuptools.build_meta import (
    build_sdist,
    get_requires_for_build_sdist,
    get_requires_for_build_wheel,
    prepare_metadata_for_build_wheel,
)
from setuptools.build_meta import (
    build_wheel as _setuptools_build_wheel,
)

__all__ = [
    "build_sdist",
    "build_wheel",
    "get_requires_for_build_sdist",
    "get_requires_for_build_wheel",
    "prepare_metadata_for_build_wheel",
]

# PEP 660 editable install hooks (setuptools >= 64)
try:
    from setuptools.build_meta import (
        build_editable,
        get_requires_for_build_editable,
        prepare_metadata_for_build_editable,
    )

    __all__ += [
        "build_editable",
        "get_requires_for_build_editable",
        "prepare_metadata_for_build_editable",
    ]
except ImportError:
    pass

logger = logging.getLogger(__name__)

_MANIFEST_FILE = "jar_manifest.json"
_SKIP_ENV_VAR = "FLINK_AGENTS_SKIP_JAR_DOWNLOAD"
_MIRROR_ENV_VAR = "FLINK_AGENTS_MAVEN_MIRROR"
_TRUTHY_VALUES = frozenset({"1", "true", "yes", "on"})


# ---------------------------------------------------------------------------
# Public PEP 517 hook
# ---------------------------------------------------------------------------


def build_wheel(
    wheel_directory: str,
    config_settings: dict | None = None,
    metadata_directory: str | None = None,
) -> str:
    """Build wheel after downloading JARs from Maven Central."""
    _ensure_jars()
    return _setuptools_build_wheel(
        wheel_directory,
        config_settings=config_settings,
        metadata_directory=metadata_directory,
    )


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _ensure_jars() -> None:
    """Download JARs listed in *jar_manifest.json* if they are not present."""
    manifest_path = Path(_MANIFEST_FILE)
    if not manifest_path.exists():
        logger.info("No %s found - skipping JAR download.", _MANIFEST_FILE)
        return

    if os.environ.get(_SKIP_ENV_VAR, "").lower() in _TRUTHY_VALUES:
        logger.info(
            "%s is set - skipping JAR download.",
            _SKIP_ENV_VAR,
        )
        return

    manifest = _load_manifest(manifest_path)
    maven_base_url = os.environ.get(_MIRROR_ENV_VAR) or manifest["maven_base_url"]
    maven_base_url = maven_base_url.rstrip("/")
    group_path = manifest["group_id"].replace(".", "/")
    version = manifest["version"]

    for jar_entry in manifest["jars"]:
        _download_jar(jar_entry, maven_base_url, group_path, version)


def _load_manifest(path: Path) -> dict:
    """Read and parse *jar_manifest.json*."""
    with path.open() as f:
        return json.load(f)


def _jar_filename(jar_entry: dict, version: str) -> str:
    """Construct the JAR filename from manifest entry fields."""
    artifact_id = jar_entry["artifact_id"]
    classifier = jar_entry.get("classifier")
    if classifier:
        return f"{artifact_id}-{version}-{classifier}.jar"
    return f"{artifact_id}-{version}.jar"


def _download_jar(
    jar_entry: dict,
    maven_base_url: str,
    group_path: str,
    version: str,
) -> None:
    """Download a single JAR if it does not already exist locally."""
    filename = _jar_filename(jar_entry, version)
    dest_dir = Path(jar_entry["dest"])
    dest_path = dest_dir / filename

    if dest_path.exists():
        logger.info("JAR already exists: %s - skipping download.", dest_path)
        return

    artifact_id = jar_entry["artifact_id"]
    url = f"{maven_base_url}/{group_path}/{artifact_id}/{version}/{filename}"

    logger.info("Downloading %s ...", url)
    dest_dir.mkdir(parents=True, exist_ok=True)

    try:
        urllib.request.urlretrieve(url, dest_path)
    except Exception:
        # Clean up partial download
        dest_path.unlink(missing_ok=True)
        raise

    _verify_checksum(dest_path, jar_entry["sha256"])
    logger.info("Downloaded and verified: %s", dest_path)


def _verify_checksum(path: Path, expected_sha256: str) -> None:
    """Verify the SHA-256 checksum of a downloaded file."""
    sha256 = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            sha256.update(chunk)
    actual = sha256.hexdigest()
    if actual != expected_sha256:
        path.unlink(missing_ok=True)
        msg = f"SHA-256 mismatch for {path}: expected {expected_sha256}, got {actual}"
        raise ValueError(msg)
