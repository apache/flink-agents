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
"""A Python agent reading the skills a Java chat model resolved.

The Java setup expands the ``skills_sources`` marker during its own ``open()``
and writes the names into the Java object. The Python wrapper is what the
Python-side readers see (``chat_model_action._inject_bash_tool_args`` builds the
bash ``allowed_script_dirs`` from ``chat_model.skills``), so ``open()`` has to
mirror the resolved names back — a source-only declaration carries no explicit
``skills`` argument and would otherwise stay ``None``.
"""

from typing import Any

from flink_agents.plan.resource.java.java_chat_model import JavaChatModelSetup
from flink_agents.runtime.java_resource_adapter import JavaResourceAdapterImpl


class _JavaChatModelSetup:
    """Stand-in for the Java setup object reached through pemja."""

    def __init__(self, skills: list[str] | None) -> None:
        self._skills = skills
        self.opened = False

    def open(self) -> None:
        self.opened = True

    def getSkills(self) -> list[str] | None:
        return self._skills


def _wrapper(java_setup: _JavaChatModelSetup, **kwargs: Any) -> JavaChatModelSetup:
    return JavaChatModelSetup(
        j_resource=java_setup,
        j_resource_adapter=JavaResourceAdapterImpl(None),
        connection="connection",
        model="model",
        **kwargs,
    )


def test_open_mirrors_the_skills_resolved_on_the_java_side():
    java_setup = _JavaChatModelSetup(["demo", "extra"])

    wrapper = _wrapper(java_setup)
    wrapper.open()

    assert java_setup.opened
    assert wrapper.skills == ["demo", "extra"]


def test_open_clears_stale_skills_when_the_java_side_reports_none():
    java_setup = _JavaChatModelSetup(None)

    wrapper = _wrapper(java_setup, skills=["stale"])
    wrapper.open()

    assert wrapper.skills is None
