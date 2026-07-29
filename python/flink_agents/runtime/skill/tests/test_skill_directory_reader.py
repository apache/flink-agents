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
"""Unit tests for SkillDirectoryReader."""

import base64
from pathlib import Path

import pytest

from flink_agents.runtime.skill.repository.skill_directory_reader import (
    SkillDirectoryReader,
)


def _write_skill(base_dir: Path, name: str, body: str) -> None:
    skill_dir = base_dir / name
    skill_dir.mkdir(parents=True)
    (skill_dir / "SKILL.md").write_text(
        f"---\nname: {name}\ndescription: dummy skill\n---\n{body}"
    )


class TestSkillDirectoryReader:
    def test_rejects_none(self) -> None:
        with pytest.raises(ValueError):
            SkillDirectoryReader(None)  # type: ignore[arg-type]

    def test_rejects_nonexistent_path(self, tmp_path: Path) -> None:
        with pytest.raises(ValueError):
            SkillDirectoryReader(tmp_path / "missing")

    def test_rejects_non_directory(self, tmp_path: Path) -> None:
        file_path = tmp_path / "not-a-dir.txt"
        file_path.write_text("x")
        with pytest.raises(ValueError):
            SkillDirectoryReader(file_path)

    def test_get_skill_dir_returns_base_slash_name(self, tmp_path: Path) -> None:
        reader = SkillDirectoryReader(tmp_path)
        # Returns even for missing names — caller verifies existence.
        assert reader.get_skill_dir("anything") == (tmp_path.resolve() / "anything")

    def test_get_skill_returns_none_for_missing(self, tmp_path: Path) -> None:
        reader = SkillDirectoryReader(tmp_path)
        assert reader.get_skill("missing") is None

    def test_get_skills_lists_every_skill_subdir(self, tmp_path: Path) -> None:
        _write_skill(tmp_path, "alpha", "body alpha")
        _write_skill(tmp_path, "beta", "body beta")
        # A non-skill subdirectory must be ignored.
        (tmp_path / "not-a-skill").mkdir()

        reader = SkillDirectoryReader(tmp_path)
        names = [s.name for s in reader.get_skills()]
        assert names == ["alpha", "beta"]

    def test_get_resources_exposes_non_skill_md_files(self, tmp_path: Path) -> None:
        _write_skill(tmp_path, "gamma", "body gamma")
        skill_dir = tmp_path / "gamma"
        (skill_dir / "notes.txt").write_text("hello")
        (skill_dir / "scripts").mkdir()
        (skill_dir / "scripts" / "run.sh").write_text("echo hi")

        reader = SkillDirectoryReader(tmp_path)
        resources = reader.get_resources("gamma")
        assert resources == {
            "notes.txt": "hello",
            "scripts/run.sh": "echo hi",
        }

    def test_get_resources_empty_for_missing_skill(self, tmp_path: Path) -> None:
        reader = SkillDirectoryReader(tmp_path)
        assert reader.get_resources("missing") == {}

    def test_binary_resource_round_trips_through_base64(self, tmp_path: Path) -> None:
        # PNG signature + a few non-UTF-8 bytes — must come back identical after
        # b64-decoding the value with the "base64: " prefix stripped, otherwise
        # the encoder is silently emitting something other than base64
        # (e.g. the f-string-of-bytes-repr bug that survived the first review).
        _write_skill(tmp_path, "binary-skill", "body")
        original = bytes(
            [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0xF8, 0x88, 0x80, 0x80]
        )
        (tmp_path / "binary-skill" / "blob.bin").write_bytes(original)

        reader = SkillDirectoryReader(tmp_path)
        encoded = reader.get_resources("binary-skill")["blob.bin"]

        assert encoded.startswith("base64: ")
        decoded = base64.b64decode(encoded.removeprefix("base64: "))
        assert decoded == original
