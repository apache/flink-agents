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
################################################################################
"""URL-based :class:`SkillRepository`.

Downloads a zip from an HTTPS URL into a temp file, extracts it into a
process-local temp directory, and reads skills from there.
"""

from __future__ import annotations

import hashlib
import re

from flink_agents.api.skills import redact_skill_url, validate_skill_url
from flink_agents.runtime.skill.repository._materialize import (
    download_to_tempfile,
    extract_zip_safely,
)
from flink_agents.runtime.skill.repository.materialized_skill_repository import (
    MaterializedSkillRepository,
)

_REQUEST_TIMEOUT_SEC = 90


class URLSkillRepository(MaterializedSkillRepository):
    """Skill repository backed by an HTTPS URL pointing to a zip.

    The zip is downloaded then extracted into a process-local temp directory
    (released eagerly via :meth:`close` or at process exit). Plain HTTP is
    rejected unless explicitly enabled, and an optional SHA-256 digest is
    verified before extraction.
    """

    def __init__(
        self,
        url: str,
        *,
        sha256: str | None = None,
        allow_insecure_http: bool = False,
    ) -> None:
        """Download and extract the zip at ``url``.

        Raises:
            ValueError: If the URL transport or SHA-256 value is invalid.
            urllib.error.HTTPError / URLError: On transport/HTTP failures.
        """
        validate_skill_url(url, allow_insecure_http=allow_insecure_http)
        if sha256 is not None and not isinstance(sha256, str):
            msg = "sha256 must contain exactly 64 hexadecimal characters"
            raise ValueError(msg)
        normalized_sha256 = sha256.lower() if sha256 is not None else None
        if normalized_sha256 is not None and not re.fullmatch(
            r"[0-9a-f]{64}", normalized_sha256
        ):
            msg = "sha256 must contain exactly 64 hexadecimal characters"
            raise ValueError(msg)

        self._url = url
        tmp_zip = download_to_tempfile(
            url,
            timeout=_REQUEST_TIMEOUT_SEC,
            allow_insecure_http=allow_insecure_http,
        )
        try:
            if normalized_sha256 is not None:
                digest = hashlib.sha256()
                with tmp_zip.open("rb") as archive:
                    for chunk in iter(lambda: archive.read(8192), b""):
                        digest.update(chunk)
                actual = digest.hexdigest()
                if actual != normalized_sha256:
                    msg = (
                        "SHA-256 mismatch for skill archive at "
                        f"{redact_skill_url(url)}: expected "
                        f"{normalized_sha256}, got {actual}"
                    )
                    raise ValueError(msg)
            materialization = extract_zip_safely(tmp_zip)
        finally:
            tmp_zip.unlink(missing_ok=True)
        super().__init__(materialization)

    @property
    def url(self) -> str:
        """Source URL this repo was loaded from."""
        return self._url
