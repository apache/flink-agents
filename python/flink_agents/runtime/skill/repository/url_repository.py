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

Downloads a zip from an http(s) URL into a temp file, extracts it into a
process-local temp directory, and reads skills from there.
"""

from __future__ import annotations

from flink_agents.runtime.skill.repository._materialize import (
    download_to_tempfile,
    extract_zip_safely,
)
from flink_agents.runtime.skill.repository.materialized_skill_repository import (
    MaterializedSkillRepository,
)

_REQUEST_TIMEOUT_SEC = 90


class URLSkillRepository(MaterializedSkillRepository):
    """Skill repository backed by an http(s) URL pointing to a zip.

    The zip is downloaded then extracted into a process-local temp directory
    (released eagerly via :meth:`close` or at process exit).
    """

    def __init__(self, url: str) -> None:
        """Download and extract the zip at ``url``.

        Raises:
            ValueError: If the URL is not http(s).
            urllib.error.HTTPError / URLError: On transport/HTTP failures.
        """
        if not url.startswith(("http://", "https://")):
            msg = f"Only http(s) URLs are supported: {url}"
            raise ValueError(msg)

        self._url = url
        tmp_zip = download_to_tempfile(url, timeout=_REQUEST_TIMEOUT_SEC)
        try:
            materialization = extract_zip_safely(tmp_zip)
        finally:
            tmp_zip.unlink(missing_ok=True)
        super().__init__(materialization)

    @property
    def url(self) -> str:
        """Source URL this repo was loaded from."""
        return self._url
