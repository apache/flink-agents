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
"""Pydantic schema for the declarative YAML API.

The models in this module define the file-level wire format. Pydantic
validation is the ground truth for the JSON Schema published in
docs/yaml-schema.json.
"""

import json
import sys
from enum import Enum
from typing import Any, Dict, List, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

Language = Literal["python", "java"]
"""Implementation language of a YAML-declared resource, action, or tool."""


class DescriptorSpec(BaseModel):
    """Schema for any ResourceDescriptor-backed resource.

    Required: ``name`` and ``clazz``. ``type`` selects the implementation
    language (``"python"`` or ``"java"``; ``None`` means Python). All
    remaining fields are forwarded verbatim to ``ResourceDescriptor`` as
    kwargs (or as the Java wrapper's kwargs when ``type: java``); the
    forwarding and language-aware wrapping is done by ``loader._build_descriptor``.
    """

    model_config = ConfigDict(extra="allow")

    name: str
    clazz: str
    type: Language | None = None


class MessageRole(str, Enum):
    """Role of a message in a chat conversation."""

    SYSTEM = "system"
    USER = "user"
    ASSISTANT = "assistant"
    TOOL = "tool"


class PromptMessage(BaseModel):
    """One message in a multi-turn prompt template."""

    model_config = ConfigDict(extra="forbid")

    role: MessageRole = MessageRole.USER
    content: str


class PromptSpec(BaseModel):
    """Declarative prompt: either a single ``text`` template or a list of
    role-tagged ``messages``. Exactly one of the two fields must be set.
    """

    model_config = ConfigDict(extra="forbid")

    name: str
    text: str | None = None
    messages: List[PromptMessage] | None = None

    @model_validator(mode="after")
    def _require_exactly_one(self) -> "PromptSpec":
        # Treat empty string / empty list as "unset" so that ``text: ""`` and
        # ``messages: []`` are rejected rather than silently producing a
        # nonsense empty prompt at load time.
        if bool(self.text) == bool(self.messages):
            msg = "prompt must define exactly one non-empty 'text' or 'messages'"
            raise ValueError(msg)
        return self


class ToolSpec(BaseModel):
    """Points ``function:`` at a callable tool.

    ``function`` is written as ``<module-or-class>:<qualname>`` — the
    colon separates the Python module (or Java class FQN) from the
    attribute path inside it. For Python, the right side may be a
    nested ``Class.method``.

    ``parameter_types`` is required when ``type: java`` and is forbidden
    otherwise (Python tools are reflected from the callable signature).
    The list contains one string per declared parameter of the Java
    method, in declaration order — the loader uses it to disambiguate
    overloaded methods on the Java class. Each string is one of:

    - A Java primitive name: one of ``boolean``, ``byte``, ``short``,
      ``int``, ``long``, ``float``, ``double``, ``char``.
    - A fully-qualified Java reference type (including boxed
      primitives), e.g. ``java.lang.Double``, ``java.lang.String``,
      ``java.util.List``.

    Generic type arguments are not part of the JVM method descriptor
    and must not be included (``java.util.List``, not
    ``java.util.List<String>``).
    """

    model_config = ConfigDict(extra="forbid")

    name: str
    function: str | None = None
    type: Language | None = None
    parameter_types: List[str] | None = None


class PackageSkillSpec(BaseModel):
    """A single ``package`` skill source entry: a Python package name plus a
    resource path relative to that package's root.
    """

    model_config = ConfigDict(extra="forbid")

    package: str
    resource: str


class SkillsSpec(BaseModel):
    """Declarative Skills resource: one or more skill sources grouped by scheme.

    Each list below maps to a skill source scheme:

    - ``paths`` — ``local`` scheme: directories or ``.zip`` files
    - ``urls`` — ``url`` scheme: ``http(s)`` URLs pointing to a ``.zip``
    - ``classpath`` — ``classpath`` scheme (Java-only at runtime): resource
      paths on the Java classpath
    - ``package`` — ``package`` scheme (Python-only at runtime): resources
      inside installed Python packages, given as ``{package, resource}`` pairs

    At least one of the four must be non-empty. ``classpath`` is exposed on
    Python for YAML schema parity with Java — it deserializes successfully
    but ``SkillManager`` on Python will fail at load time because Python does
    not register a ``classpath`` handler.
    """

    model_config = ConfigDict(extra="forbid")

    name: str
    paths: List[str] = Field(default_factory=list)
    urls: List[str] = Field(default_factory=list)
    classpath: List[str] = Field(default_factory=list)
    package: List[PackageSkillSpec] = Field(default_factory=list)

    @model_validator(mode="after")
    def _require_one_source(self) -> "SkillsSpec":
        if not (self.paths or self.urls or self.classpath or self.package):
            msg = (
                f"skills '{self.name}': at least one of "
                "paths/urls/classpath/package must be non-empty."
            )
            raise ValueError(msg)
        return self


class ActionSpec(BaseModel):
    """An action references a user function and the event types it listens to.

    ``function`` is written as ``<module-or-class>:<qualname>`` — the
    colon separates the Python module (or Java class FQN) from the
    attribute path inside it.

    Action signatures are fixed (``(Event, RunnerContext)``), so there is
    no ``parameter_types`` knob — Python doesn't need it, and the Java
    action signature is determined by the action contract.
    """

    model_config = ConfigDict(extra="forbid")

    name: str
    function: str | None = None
    listen_to: List[str] = Field(..., min_length=1)
    config: Dict[str, Any] | None = None
    type: Language | None = None


class AgentSpec(BaseModel):
    """One agent inside a YAML file's ``agents:`` list.

    Holds the agent's own resources and actions. Resources/actions declared
    at the file level (siblings of ``agents:``) are merged in by the loader.
    """

    model_config = ConfigDict(extra="forbid")

    name: str
    description: str | None = None

    prompts: List[PromptSpec] = Field(default_factory=list)
    tools: List[ToolSpec] = Field(default_factory=list)
    skills: List[SkillsSpec] = Field(default_factory=list)
    actions: List[ActionSpec | str] = Field(default_factory=list)

    chat_model_connections: List[DescriptorSpec] = Field(default_factory=list)
    chat_model_setups: List[DescriptorSpec] = Field(default_factory=list)
    embedding_model_connections: List[DescriptorSpec] = Field(default_factory=list)
    embedding_model_setups: List[DescriptorSpec] = Field(default_factory=list)
    vector_stores: List[DescriptorSpec] = Field(default_factory=list)
    mcp_servers: List[DescriptorSpec] = Field(default_factory=list)


class YamlAgentsDocument(BaseModel):
    """Top-level YAML document.

    Always wraps one or more agents under ``agents:``. Resources and
    actions declared at the same level as ``agents:`` are shared:
    resources are registered on the environment; actions can be
    referenced from any agent by name string.
    """

    model_config = ConfigDict(extra="forbid")

    agents: List[AgentSpec]

    prompts: List[PromptSpec] = Field(default_factory=list)
    tools: List[ToolSpec] = Field(default_factory=list)
    skills: List[SkillsSpec] = Field(default_factory=list)
    actions: List[ActionSpec] = Field(default_factory=list)

    chat_model_connections: List[DescriptorSpec] = Field(default_factory=list)
    chat_model_setups: List[DescriptorSpec] = Field(default_factory=list)
    embedding_model_connections: List[DescriptorSpec] = Field(default_factory=list)
    embedding_model_setups: List[DescriptorSpec] = Field(default_factory=list)
    vector_stores: List[DescriptorSpec] = Field(default_factory=list)
    mcp_servers: List[DescriptorSpec] = Field(default_factory=list)


def export() -> str:
    """Return the JSON Schema for the YAML API as a string.

    Pydantic models in this module are the ground truth for the YAML
    file format; this helper serialises them so downstream consumers
    that can't read Python types directly (IDE YAML language servers,
    a future Java-side loader, generated docs) can use the same
    contract. The output is checked in at ``docs/yaml-schema.json``;
    keep it in sync by re-running this helper after editing the specs.
    """
    schema = YamlAgentsDocument.model_json_schema()
    return json.dumps(schema, indent=2, sort_keys=True) + "\n"


if __name__ == "__main__":
    sys.stdout.write(export())
