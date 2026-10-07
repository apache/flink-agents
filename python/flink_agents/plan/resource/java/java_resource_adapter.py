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
from typing import Any, Protocol


class JavaResourceAdapter(Protocol):
    """Services supplied by Runtime to Java-backed Plan resources."""

    def getResource(self, name: str, resource_type: str) -> Any:
        """Resolve a resource through its owning runtime."""
        ...

    def fromPythonChatMessage(
        self, role: str, blocks: list[dict], tool_calls: list, extra_args: dict
    ) -> Any:
        """Create a Java message from Python values."""
        ...

    def fromPythonDocument(
        self, content: str, metadata: dict, document_id: str, embedding: Any, score: Any
    ) -> Any:
        """Create a Java document from Python values."""
        ...

    def set_metric_group(self, resource: Any, metric_group: Any) -> None:
        """Bind runtime-specific metrics without exposing their implementation."""
        ...

    def generateAvailableSkillsPrompt(self, skill_names: list[str]) -> str:
        """Generate skill discovery text."""
        ...

    def getSkillDirs(self, skill_names: list[str]) -> list[str]:
        """Resolve materialized skill directories."""
        ...
