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
"""Skills configuration resource for agent skills discovery.

Each :class:`Skills` resource carries a single ordered list of
:class:`SkillSourceSpec` entries. Each entry has a ``scheme`` (e.g.
``"local"``, ``"url"``, ``"package"``) and a scheme-specific ``params`` map.
Use one of the factory methods to construct a :class:`Skills` resource:

* :meth:`Skills.from_local_dir` for local directories or local ``.zip`` files
* :meth:`Skills.from_url` for HTTPS URLs pointing to a ``.zip``
* :meth:`Skills.from_package` for resources inside installed packages

Example::

    @skills
    @staticmethod
    def my_skills() -> Skills:
        return Skills.from_local_dir("./skills")


    @skills
    @staticmethod
    def remote_skills() -> Skills:
        return Skills.from_url("https://example.com/skills.zip")


    @skills
    @staticmethod
    def packaged_skills() -> Skills:
        return Skills.from_package(("my_skills_pkg", "skills"))

The ``"classpath"`` scheme is Java-only; a plan written by Java with
``scheme=classpath`` deserializes successfully on Python but
:class:`SkillManager` will fail fast at load time with the registered-scheme
list.

Declare more than one ``@skills`` function on the same agent to combine
sources; the runtime merges them and de-duplicates identical
:class:`SkillSourceSpec` entries.
"""

from __future__ import annotations

import re
from ipaddress import AddressValueError, IPv6Address
from typing import Dict, List, Tuple
from urllib.parse import urlparse, urlsplit, urlunsplit

from pydantic import BaseModel, ConfigDict, Field, field_validator
from typing_extensions import override

from flink_agents.api.resource import ResourceType, SerializableResource

_INVALID_URI_CHARACTER = re.compile(r'[\x00-\x20\x7f<>"{}|\\^`]')
_INVALID_PERCENT_ESCAPE = re.compile(r"%(?![0-9a-fA-F]{2})")
_HOST_LABEL = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")


def redact_skill_url(url: str) -> str:
    """Return a skill URL without user info, query parameters, or a fragment.

    Internal contract shared with the runtime; not a stable public API.
    """
    try:
        parts = urlsplit(url)
        if not parts.scheme or not parts.netloc:
            return "<redacted>"
        netloc = parts.netloc.rsplit("@", 1)[-1]
        if not netloc:
            return "<redacted>"
        return urlunsplit((parts.scheme, netloc, parts.path, "", ""))
    except ValueError:
        return "<redacted>"


def validate_skill_url(url: str, *, allow_insecure_http: bool) -> str:
    """Validate a skill URL using the contract shared with the Java API.

    Internal contract shared with the runtime; not a stable public API.
    """
    if not isinstance(url, str):
        msg = "skill URL must be a string"
        raise TypeError(msg)
    try:
        parsed = urlparse(url)
    except ValueError:
        msg = f"Invalid skill URL: {redact_skill_url(url)}"
        raise ValueError(msg) from None
    if _INVALID_URI_CHARACTER.search(url) or _INVALID_PERCENT_ESCAPE.search(url):
        msg = f"Invalid skill URL: {redact_skill_url(url)}"
        raise ValueError(msg)
    # Java's URI rejects raw brackets in the path (but not in the query or
    # fragment); encoded %5B/%5D and IPv6 authority brackets stay valid.
    if any(c in f"{parsed.path};{parsed.params}" for c in "[]"):
        msg = f"Invalid skill URL: {redact_skill_url(url)}"
        raise ValueError(msg)
    scheme = parsed.scheme.lower()
    if scheme not in {"http", "https"}:
        msg = f"Only HTTP(S) skill URLs are supported: {redact_skill_url(url)}"
        raise ValueError(msg)
    try:
        hostname = parsed.hostname
        _ = parsed.port
    except ValueError:
        msg = (
            "Skill URL must include a valid host and, when present, a valid port: "
            f"{redact_skill_url(url)}"
        )
        raise ValueError(msg) from None
    if parsed.username is not None:
        msg = f"Skill URL must not include user info: {redact_skill_url(url)}"
        raise ValueError(msg)
    bracketed_host = parsed.netloc.rsplit("@", 1)[-1].startswith("[")
    if (
        not hostname
        or (bracketed_host and ":" not in hostname)
        or not _is_valid_hostname(hostname)
    ):
        msg = f"Skill URL must include a valid host: {redact_skill_url(url)}"
        raise ValueError(msg)
    if scheme == "http" and not allow_insecure_http:
        msg = (
            "Plain HTTP skill URLs are disabled by default; use HTTPS or "
            "explicitly allow insecure HTTP for this source: "
            f"{redact_skill_url(url)}"
        )
        raise ValueError(msg)
    return scheme


def _is_valid_hostname(hostname: str) -> bool:
    """Match the host syntax accepted by Java URI.parseServerAuthority()."""
    if ":" in hostname:
        try:
            IPv6Address(hostname)
        except AddressValueError:
            return False
        return True
    if not hostname.isascii():
        return False
    dns_name = hostname[:-1] if hostname.endswith(".") else hostname
    if not dns_name:
        return False
    if re.fullmatch(r"[0-9]+(?:\.[0-9]+){3}", dns_name):
        return not hostname.endswith(".") and all(
            int(part) <= 255 for part in dns_name.split(".")
        )
    labels = dns_name.split(".")
    if any(not label for label in labels):
        return False
    return not (len(labels) > 1 and labels[-1][0].isdigit()) and all(
        _HOST_LABEL.fullmatch(label) for label in labels
    )


class SkillSourceSpec(BaseModel):
    """One entry in :attr:`Skills.sources`.

    ``scheme`` identifies the source type; ``params`` carries the
    scheme-specific configuration. The ``scheme`` is normalized to lowercase.
    Unknown schemes deserialize successfully — the registry is the fail point
    at load time.
    """

    scheme: str
    params: Dict[str, str] = Field(default_factory=dict)

    model_config = ConfigDict(frozen=True)

    @field_validator("scheme")
    @classmethod
    def _lower(cls, v: str) -> str:
        return v.lower()

    def __hash__(self) -> int:
        return hash((self.scheme, tuple(sorted(self.params.items()))))


class Skills(SerializableResource):
    """A resource describing where to load agent skills from.

    Use one of the ``from_*`` factory methods to construct — direct field
    construction is reserved for internal serialization and not part of the
    public API.
    """

    sources: List[SkillSourceSpec] = Field(default_factory=list)

    @classmethod
    def from_local_dir(cls, *paths: str) -> Skills:
        """Create a Skills resource from one or more local paths.

        Each path may be a directory or a ``.zip`` file. For a directory, its
        immediate subdirectories must each contain a ``SKILL.md`` file. For
        a zip, its top-level entries are the skill subdirectories.
        """
        return cls(
            sources=[SkillSourceSpec(scheme="local", params={"path": p}) for p in paths]
        )

    @classmethod
    def from_url(cls, *urls: str) -> Skills:
        """Create a Skills resource from one or more HTTPS URLs.

        Each URL must point to a ``.zip`` whose top level is the baseDir
        (i.e. skill subdirectories sit at the top of the zip).
        """
        for url in urls:
            cls._require_url(url, allow_insecure_http=False)
        return cls(
            sources=[SkillSourceSpec(scheme="url", params={"url": u}) for u in urls]
        )

    @classmethod
    def from_url_with_sha256(cls, url: str, sha256: str) -> Skills:
        """Create an HTTPS URL source pinned to a SHA-256 archive digest."""
        cls._require_url(url, allow_insecure_http=False)
        cls._require_sha256(sha256)
        return cls(
            sources=[
                SkillSourceSpec(scheme="url", params={"url": url, "sha256": sha256})
            ]
        )

    @classmethod
    def from_url_unsafe(cls, *urls: str) -> Skills:
        """Create URL sources that explicitly permit plain HTTP transport.

        This compatibility escape hatch should be used only on trusted networks.
        Prefer :meth:`from_url` with HTTPS.
        """
        for url in urls:
            cls._require_url(url, allow_insecure_http=True)
        return cls(
            sources=[
                SkillSourceSpec(
                    scheme="url",
                    params={"url": url, "allow_insecure_http": "true"},
                )
                for url in urls
            ]
        )

    @classmethod
    def from_url_unsafe_with_sha256(cls, url: str, sha256: str) -> Skills:
        """Create a digest-pinned source that explicitly permits plain HTTP."""
        cls._require_url(url, allow_insecure_http=True)
        cls._require_sha256(sha256)
        return cls(
            sources=[
                SkillSourceSpec(
                    scheme="url",
                    params={
                        "url": url,
                        "sha256": sha256,
                        "allow_insecure_http": "true",
                    },
                )
            ]
        )

    @staticmethod
    def _require_url(url: str, *, allow_insecure_http: bool) -> None:
        validate_skill_url(url, allow_insecure_http=allow_insecure_http)

    @staticmethod
    def _require_sha256(sha256: str) -> None:
        if not isinstance(sha256, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", sha256):
            msg = "sha256 must contain exactly 64 hexadecimal characters"
            raise ValueError(msg)

    @classmethod
    def from_package(cls, *pairs: Tuple[str, str]) -> Skills:
        """Create a Skills resource from resources inside installed packages.

        Args:
            *pairs: One or more ``(package, resource)`` tuples. ``package`` is
                a dotted Python package name (e.g. ``"my_skills_pkg"``);
                ``resource`` is a path inside the package, relative to the
                package root. The resource may refer to a directory or a
                ``.zip`` file.
        """
        return cls(
            sources=[
                SkillSourceSpec(
                    scheme="package", params={"package": pkg, "resource": res}
                )
                for pkg, res in pairs
            ]
        )

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.SKILLS


# name of built-in tools needed by using skills
LOAD_SKILL_TOOL = "load_skill"
BASH_TOOL = "bash"
