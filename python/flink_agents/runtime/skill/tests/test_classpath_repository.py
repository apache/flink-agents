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
"""Cross-language repository ownership and operator isolation contracts."""

from pathlib import Path

import pytest

from flink_agents.api.resource import ResourceType
from flink_agents.api.skills import Skills
from flink_agents.plan.agent_plan import SKILLS_CONFIG
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.resource_cache import ResourceCache
from flink_agents.runtime.skill.skill_manager import SkillManager
from flink_agents.runtime.skill.tests.utils import default_limits


class Bridge:
    def __init__(self, content: str, *, fail: bool = False) -> None:
        self.content = content
        self.fail = fail
        self.directory = None
        self.calls = 0

    def extractClasspathSkills(
        self,
        resource: str,
        target: str,
        max_download_bytes: int,
        max_extract_entry_bytes: int,
        max_extract_total_bytes: int,
        max_extract_entries: int,
    ) -> None:
        assert resource == "skills"
        self.limits = (
            max_download_bytes,
            max_extract_entry_bytes,
            max_extract_total_bytes,
            max_extract_entries,
        )
        self.calls += 1
        self.directory = Path(target)
        skill = self.directory / "demo"
        skill.mkdir()
        (skill / "SKILL.md").write_text(
            "---\nname: demo\ndescription: Test skill\n---\n" + self.content
        )
        (skill / "example.txt").write_text(self.content)
        if self.fail:
            msg = "Broken JAR"
            raise ValueError(msg)


def test_agent_config_is_forwarded_to_classpath_bridge() -> None:
    bridge = Bridge("configured")
    cache = ResourceCache(
        {},
        AgentConfiguration(
            {
                "skill.max-download-bytes": 11,
                "skill.max-uncompressed-entry-bytes": 22,
                "skill.max-uncompressed-total-bytes": 33,
                "skill.max-archive-entries": 44,
            }
        ),
    )
    cache._cache[ResourceType.SKILLS] = {SKILLS_CONFIG: Skills.from_classpath("skills")}
    cache.set_java_resource_adapter(bridge)
    try:
        assert cache.get_resource_context().get_skill_manager().size == 1
        assert bridge.limits == (11, 22, 33, 44)
    finally:
        cache.close()


def test_bridges_are_operator_scoped_and_directories_are_owned() -> None:
    first, second = Bridge("job-a"), Bridge("job-b")
    with SkillManager(
        Skills.from_classpath("skills"), first, limits=default_limits()
    ) as a:
        with SkillManager(
            Skills.from_classpath("skills"), second, limits=default_limits()
        ) as b:
            assert a.load_skill_resource("demo", "example.txt") == "job-a"
            assert b.load_skill_resource("demo", "example.txt") == "job-b"
            assert a.get_skill("demo").origin.scheme == "classpath"
            assert a.get_skill("demo").origin.location == "skills"
            assert first.calls == second.calls == 1
            original_directory = first.directory
            reopened = a._get_handler("classpath").open({"resource": "skills"})
            try:
                assert reopened.get_resources("demo")["example.txt"] == "job-a"
            finally:
                reopened.close()
            assert not first.directory.exists()
            first.directory = original_directory
            assert first.calls == 2
            assert second.calls == 1
        assert not second.directory.exists()
        assert first.directory.exists()
    assert not first.directory.exists()


def test_failed_materialization_is_cleaned_up() -> None:
    bridge = Bridge("partial", fail=True)
    with pytest.raises(RuntimeError, match="classpath"):
        SkillManager(Skills.from_classpath("skills"), bridge, limits=default_limits())
    assert not bridge.directory.exists()


def test_missing_bridge_has_actionable_error() -> None:
    with pytest.raises(RuntimeError) as error:
        SkillManager(Skills.from_classpath("skills"), limits=default_limits())
    assert "Flink Java runtime bridge" in str(error.value.__cause__)
