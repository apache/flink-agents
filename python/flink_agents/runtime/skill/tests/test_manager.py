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
"""Unit tests for SkillManager and skill tools."""

import importlib
import shutil
import sys
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest

from flink_agents.api.skills import Skills, SkillSourceSpec
from flink_agents.runtime.skill.repository.package_repository import (
    PackageSkillRepository,
)
from flink_agents.runtime.skill.skill_manager import SkillManager

base_dir = Path(__file__).parent


class TestSkillManager:
    """Tests for SkillManager class."""

    @pytest.fixture
    def skills_dir(self) -> Path:
        """Create a temporary directory with test skills."""
        return base_dir / "resources" / "skills"

    def test_add_skills_from_path(self, skills_dir: Path) -> None:
        """Test adding skills from path."""
        manager = SkillManager(Skills.from_local_dir(str(skills_dir)))
        skill = manager.get_skill("github")
        assert skill.name == "github"
        assert skill.description == (
            "Interact with GitHub using the `gh` CLI. "
            "Use `gh issue`, `gh pr`, `gh run`, and `gh api` "
            "for issues, PRs, CI runs, and advanced queries."
        )

    def test_generate_discovery_prompt(self, skills_dir: Path) -> None:
        """Test generating discovery prompt."""
        manager = SkillManager(Skills.from_local_dir(str(skills_dir)))

        prompt = manager.generate_discovery_prompt("github", "nano-banana-pro")
        with Path.open(base_dir / "resources" / "skill_discovery_prompt.txt") as f:
            content = f.read()
            assert prompt == content

    def test_get_skill(self, skills_dir: Path) -> None:
        """Test getting a skill."""
        manager = SkillManager(Skills.from_local_dir(str(skills_dir)))

        skill = manager.get_skill("github")
        assert skill is not None
        assert skill.name == "github"
        assert (
            skill.description
            == "Interact with GitHub using the `gh` CLI. Use `gh issue`, `gh pr`, `gh run`, and `gh api` for issues, PRs, CI runs, and advanced queries."
        )

        skill2 = manager.get_skill("nano-banana-pro")
        assert skill2 is not None
        assert skill2.name == "nano-banana-pro"
        assert (
            skill2.description
            == "Generate/edit images with Nano Banana Pro (Gemini 3 Pro Image). Use for image create/modify requests incl. edits. Supports text-to-image + image-to-image; 1K/2K/4K; use --input-image."
        )

    def test_origin_is_attached_after_load(self, skills_dir: Path) -> None:
        manager = SkillManager(Skills.from_local_dir(str(skills_dir)))
        origin = manager.get_skill("github").origin
        assert origin is not None
        assert origin.scheme == "local"
        assert origin.location == str(skills_dir)

    def test_duplicate_skill_name_last_write_wins_with_new_origin(
        self, tmp_path: Path
    ) -> None:
        # Two distinct local source dirs each contain a single skill named "dup".
        dir_a = tmp_path / "a"
        dir_b = tmp_path / "b"
        for d, tag in [(dir_a, "from-a"), (dir_b, "from-b")]:
            skill_dir = d / "dup"
            skill_dir.mkdir(parents=True)
            (skill_dir / "SKILL.md").write_text(
                f"---\nname: dup\ndescription: dummy ({tag})\n---\nbody {tag}"
            )
        config = Skills(
            sources=[
                SkillSourceSpec(scheme="local", params={"path": str(dir_a)}),
                SkillSourceSpec(scheme="local", params={"path": str(dir_b)}),
            ]
        )
        manager = SkillManager(config)
        # Second source wins on collision; check via origin.
        assert manager.get_skill("dup").origin.location == str(dir_b)

    def test_load_skill_resource(self, skills_dir: Path) -> None:
        """Test loading a skill resource."""
        manager = SkillManager(Skills.from_local_dir(str(skills_dir)))

        skill = manager.get_skill("nano-banana-pro")
        content = skill.get_resource("scripts/generate_image.py")
        assert content is not None
        assert "get_api_key" in content

        nonexistent = skill.get_resource("nonexistent")
        assert nonexistent is None

    def test_get_skill_dirs_raises_for_unknown_name(self, skills_dir: Path) -> None:
        manager = SkillManager(Skills.from_local_dir(str(skills_dir)))
        with pytest.raises(ValueError) as exc_info:
            manager.get_skill_dirs("does-not-exist")
        assert "does-not-exist" in str(exc_info.value)
        assert "github" in str(exc_info.value)


def _zip_dir(src: Path, dst_zip: Path) -> None:
    with zipfile.ZipFile(dst_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in src.rglob("*"):
            if path.is_file():
                zf.write(path, arcname=path.relative_to(src))


class _ZipHandler(BaseHTTPRequestHandler):
    zip_bytes: bytes = b""

    def do_GET(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(len(type(self).zip_bytes)))
        self.end_headers()
        self.wfile.write(type(self).zip_bytes)

    def log_message(self, *_args: object) -> None:
        pass


@pytest.fixture
def mixed_sources(tmp_path: Path):
    """Yields (dir_path, zip_url, pkg_name, pkg_resource).

    Spins up a local http.server, builds a tmp package on sys.path with
    a skills/ subdirectory, and zips the existing skills tree for the
    URL fixture. Cleans up all three on teardown.
    """
    src = base_dir / "resources" / "skills"

    dir_path = str(src)

    zip_path = tmp_path / "skills.zip"
    _zip_dir(src, zip_path)
    _ZipHandler.zip_bytes = zip_path.read_bytes()
    server = HTTPServer(("127.0.0.1", 0), _ZipHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    pkg_root = tmp_path / "pkg_root"
    pkg_root.mkdir()
    pkg_name = "_flink_agents_test_mixed_pkg"
    pkg_dir = pkg_root / pkg_name
    pkg_dir.mkdir()
    (pkg_dir / "__init__.py").write_text("")
    shutil.copytree(src, pkg_dir / "skills")
    sys.path.insert(0, str(pkg_root))
    importlib.invalidate_caches()

    try:
        yield (
            dir_path,
            f"http://127.0.0.1:{port}/skills.zip",
            pkg_name,
            "skills",
        )
    finally:
        server.shutdown()
        server.server_close()
        _ZipHandler.zip_bytes = b""
        sys.path.remove(str(pkg_root))
        sys.modules.pop(pkg_name, None)


class TestSkillManagerMixedSources:
    def test_url_only_loads_skills(self, mixed_sources) -> None:
        _dir, url, _pkg, _resource = mixed_sources
        config = Skills.from_url(url)
        manager = SkillManager(config)
        assert set(manager.get_all_skill_names()) == {"github", "nano-banana-pro"}

    def test_package_only_loads_skills(self, mixed_sources) -> None:
        _dir, _url, pkg, resource = mixed_sources
        config = Skills.from_package((pkg, resource))
        manager = SkillManager(config)
        assert set(manager.get_all_skill_names()) == {"github", "nano-banana-pro"}

    def test_loads_from_all_sources(self, mixed_sources) -> None:
        dir_path, url, pkg, resource = mixed_sources
        # All three sources expose the same skills. Dispatch runs in source-list
        # order (local -> url -> package); registration is last-wins, so the
        # final repo for each skill must be a PackageSkillRepository if all
        # three sources actually executed.
        config = Skills(
            sources=[
                SkillSourceSpec(scheme="local", params={"path": dir_path}),
                SkillSourceSpec(scheme="url", params={"url": url}),
                SkillSourceSpec(
                    scheme="package",
                    params={"package": pkg, "resource": resource},
                ),
            ]
        )
        manager = SkillManager(config)
        assert set(manager.get_all_skill_names()) == {"github", "nano-banana-pro"}
        assert all(
            isinstance(r, PackageSkillRepository) for r in manager._repos.values()
        )

    def test_close_releases_url_repo_temp_dir(self, mixed_sources) -> None:
        _dir, url, _pkg, _resource = mixed_sources
        config = Skills.from_url(url)
        with SkillManager(config) as manager:
            skill_dir = manager.get_skill_dir("github")
            assert skill_dir is not None
            extract_root = skill_dir.parent
            assert skill_dir.exists()
            assert extract_root.exists()
        # Context manager exit triggered close.
        assert not extract_root.exists(), (
            "SkillManager.close() must release the URL repo's temp dir"
        )

    def test_unknown_scheme_fails_loud(self) -> None:
        config = Skills(
            sources=[SkillSourceSpec(scheme="future-scheme", params={"k": "v"})]
        )
        with pytest.raises(RuntimeError) as exc_info:
            SkillManager(config)
        assert "future-scheme" in str(exc_info.value)
        # The registered-scheme list comes from the chained ValueError.
        assert "local" in str(exc_info.value.__cause__)
        assert "package" in str(exc_info.value.__cause__)

    def test_close_releases_repo_displaced_by_duplicate_skill_name(self) -> None:
        from typing import Dict, List

        from flink_agents.runtime.skill import skill_source_registry
        from flink_agents.runtime.skill.agent_skill import AgentSkill
        from flink_agents.runtime.skill.skill_repository import SkillRepository

        closed: List[int] = []

        class FakeRepo(SkillRepository):
            def __init__(self, tag: int) -> None:
                self._tag = tag

            def get_skill(self, name: str) -> AgentSkill | None:
                return self.get_skills()[0] if name == "dup" else None

            def get_skills(self) -> List[AgentSkill]:
                return [AgentSkill(name="dup", description="dummy", content="body")]

            def get_resources(self, name: str) -> Dict[str, str]:
                return {}

            def close(self) -> None:
                closed.append(self._tag)

        counter = {"n": 0}

        def opener(params) -> SkillRepository:
            counter["n"] += 1
            return FakeRepo(counter["n"])

        skill_source_registry.register("test-dup-close", opener)

        config = Skills(
            sources=[
                SkillSourceSpec(scheme="test-dup-close", params={}),
                SkillSourceSpec(scheme="test-dup-close", params={}),
            ]
        )
        manager = SkillManager(config)
        manager.close()

        assert sorted(closed) == [1, 2], (
            "duplicate-name registration must not orphan the displaced repo"
        )

    def test_partial_load_failure_closes_already_opened_repos(self) -> None:
        from typing import Dict, List

        from flink_agents.runtime.skill import skill_source_registry
        from flink_agents.runtime.skill.agent_skill import AgentSkill
        from flink_agents.runtime.skill.skill_repository import SkillRepository

        closed: List[str] = []

        class FakeRepo(SkillRepository):
            def __init__(self, skill_name: str) -> None:
                self._skill_name = skill_name

            def get_skill(self, name: str) -> AgentSkill | None:
                return self.get_skills()[0] if name == self._skill_name else None

            def get_skills(self) -> List[AgentSkill]:
                return [
                    AgentSkill(
                        name=self._skill_name, description="dummy", content="body"
                    )
                ]

            def get_resources(self, name: str) -> Dict[str, str]:
                return {}

            def close(self) -> None:
                closed.append(self._skill_name)

        counter = {"n": 0}

        def opener(params) -> SkillRepository:
            counter["n"] += 1
            if counter["n"] == 2:
                msg = "boom"
                raise OSError(msg)
            return FakeRepo(f"skill-{counter['n']}")

        skill_source_registry.register("test-partial-load", opener)

        config = Skills(
            sources=[
                SkillSourceSpec(scheme="test-partial-load", params={}),
                SkillSourceSpec(scheme="test-partial-load", params={}),
            ]
        )

        with pytest.raises(RuntimeError):
            SkillManager(config)
        assert closed == ["skill-1"], (
            "the repo opened before the partial-load failure must be closed"
        )
