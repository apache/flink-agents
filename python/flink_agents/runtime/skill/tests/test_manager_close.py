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
"""Close-path contract tests for the Python SkillManager.

These mirror the Java ``SkillManagerTest`` close tests and the Python
``ResourceCache`` close tests: a failing repository must not strand the
repositories behind it, and the first failure must reach the caller unchanged
rather than being swallowed.
"""

import pytest

from flink_agents.api.skills import Skills, SkillSourceSpec
from flink_agents.runtime.skill.agent_skill import AgentSkill
from flink_agents.runtime.skill.skill_manager import SkillManager
from flink_agents.runtime.skill.skill_repository import SkillRepository
from flink_agents.runtime.skill.skill_source_handler import SkillSourceHandler


class FakeRepo(SkillRepository):
    """Records whether ``close()`` ran, and optionally fails it."""

    def __init__(self, skill_name: str, failure: Exception | None = None) -> None:
        """Create a repository exposing one skill, optionally failing on close."""
        self._skill_name = skill_name
        self._failure = failure
        self.closed = False

    def get_skill(self, name: str) -> AgentSkill | None:
        """Return the single skill when it is the one asked for."""
        return self.get_skills()[0] if name == self._skill_name else None

    def get_skills(self) -> list[AgentSkill]:
        """Return the single skill held by this repository."""
        return [AgentSkill(name=self._skill_name, description="dummy", content="body")]

    def get_resources(self, name: str) -> dict[str, str]:
        """Return no resources for any skill."""
        return {}

    def close(self) -> None:
        """Record the call, then fail if this repository was configured to."""
        self.closed = True
        if self._failure is not None:
            raise self._failure


def _manager_with(monkeypatch: pytest.MonkeyPatch, *repos: FakeRepo) -> SkillManager:
    """Build a SkillManager whose sources open exactly ``repos``, in order."""
    opened = list(repos)

    def opener(params) -> SkillRepository:
        return opened.pop(0)

    monkeypatch.setattr(
        SkillManager,
        "_create_handlers",
        lambda self, bridge: {"test-close": SkillSourceHandler(opener)},
    )
    config = Skills(
        sources=[SkillSourceSpec(scheme="test-close", params={}) for _ in repos]
    )
    return SkillManager(config)


def test_close_attempts_every_repo_and_rethrows_first_failure(monkeypatch) -> None:
    """A failing repository must not strand the ones behind it.

    The failure reaches the caller unchanged in identity, not wrapped, which is
    what makes it usable by the caller that owns the shutdown.
    """
    failure = RuntimeError("repo close failed")
    failing = FakeRepo("first", failure=failure)
    surviving = FakeRepo("second")
    manager = _manager_with(monkeypatch, failing, surviving)

    with pytest.raises(RuntimeError, match="repo close failed") as excinfo:
        manager.close()

    assert excinfo.value is failure
    assert failing.closed
    assert surviving.closed, "a failing repo must not strand the ones behind it"


def test_close_reports_first_failure_when_several_fail(monkeypatch) -> None:
    """The first failure is the one raised; later ones do not replace it."""
    first = RuntimeError("first")
    second = RuntimeError("second")
    manager = _manager_with(
        monkeypatch, FakeRepo("a", failure=first), FakeRepo("b", failure=second)
    )

    with pytest.raises(RuntimeError, match="first") as excinfo:
        manager.close()

    assert excinfo.value is first


def test_close_logs_later_failures(
    monkeypatch, caplog: pytest.LogCaptureFixture
) -> None:
    """Later failures are logged, since Python 3.10 cannot attach them."""
    manager = _manager_with(
        monkeypatch,
        FakeRepo("a", failure=RuntimeError("first")),
        FakeRepo("b", failure=RuntimeError("second")),
    )

    with pytest.raises(RuntimeError, match="first"):
        manager.close()

    assert "Suppressed failure closing skill repository." in caplog.text
    assert "second" in caplog.text


def test_close_returns_normally_when_nothing_fails(monkeypatch) -> None:
    """The healthy path stays a plain no-raise close."""
    surviving = FakeRepo("only")
    manager = _manager_with(monkeypatch, surviving)

    manager.close()

    assert surviving.closed


def test_load_failure_is_not_replaced_by_close_failure(
    monkeypatch, caplog: pytest.LogCaptureFixture
) -> None:
    """A close failure during the failed-load cleanup is logged, not re-raised.

    The load failure is what the caller needs; letting the cleanup failure
    replace it would hide the reason the manager could not be built at all.
    """
    failing = FakeRepo("skill-1", failure=RuntimeError("close boom"))
    counter = {"n": 0}

    def opener(params) -> SkillRepository:
        counter["n"] += 1
        if counter["n"] == 2:
            msg = "open boom"
            raise OSError(msg)
        return failing

    monkeypatch.setattr(
        SkillManager,
        "_create_handlers",
        lambda self, bridge: {"test-load": SkillSourceHandler(opener)},
    )
    config = Skills(
        sources=[
            SkillSourceSpec(scheme="test-load", params={}),
            SkillSourceSpec(scheme="test-load", params={}),
        ]
    )

    with pytest.raises(RuntimeError, match="Failed to load skills"):
        SkillManager(config)

    assert failing.closed
    assert "Suppressed failure closing skill repositories" in caplog.text
    assert "close boom" in caplog.text
