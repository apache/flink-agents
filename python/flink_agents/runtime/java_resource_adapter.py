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
from typing import Any

from flink_agents.runtime.flink_metric_group import FlinkMetricGroup


class JavaResourceAdapterImpl:
    """Adapt the Java bridge and Python runtime services to the Plan contract."""

    def __init__(self, bridge: Any) -> None:
        """Bind the Java bridge injected by the runtime."""
        self._bridge = bridge

    def __getattr__(self, name: str) -> Any:
        return getattr(self._bridge, name)

    def getResource(self, name: str, resource_type: str) -> Any:
        """Resolve a resource from the Java resource context."""
        return self._bridge.getResource(name, resource_type)

    def fromPythonChatMessage(
        self, role: str, blocks: list[dict], tool_calls: list, extra_args: dict
    ) -> Any:
        """Convert message fields through the Java bridge."""
        return self._bridge.fromPythonChatMessage(role, blocks, tool_calls, extra_args)

    def fromPythonDocument(
        self, content: str, metadata: dict, document_id: str, embedding: Any, score: Any
    ) -> Any:
        """Convert document fields through the Java bridge."""
        return self._bridge.fromPythonDocument(
            content, metadata, document_id, embedding, score
        )

    def generateAvailableSkillsPrompt(self, skill_names: list[str]) -> str:
        """Generate the prompt for Java-hosted skills."""
        return self._bridge.generateAvailableSkillsPrompt(skill_names)

    def getSkillDirs(self, skill_names: list[str]) -> list[str]:
        """Resolve the directories of Java-hosted skills."""
        return list(self._bridge.getSkillDirs(skill_names) or [])

    def set_metric_group(self, resource: Any, metric_group: Any) -> None:
        """Forward the Java metric group without exposing runtime types to Plan."""
        if resource is None:
            return
        if metric_group is None:
            java_group = None
        elif isinstance(metric_group, FlinkMetricGroup):
            java_group = metric_group._j_metric_group
        else:
            msg = "Java resource metric groups must be FlinkMetricGroup or None."
            raise TypeError(msg)
        resource.setMetricGroup(java_group)
