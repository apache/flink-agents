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
"""Presenting an AGENT resource to a chat model as a callable."""

import json
from typing import Any

from typing_extensions import override

from flink_agents.api.subagent import CALLABLE_NAME_PREFIX
from flink_agents.api.tools.tool import Tool, ToolMetadata, ToolType
from flink_agents.api.tools.utils import create_model_from_schema


class SubagentTool(Tool):
    """Presents an AGENT resource to a chat model as a callable, so that the
    model can delegate a task by issuing a function call. The callable name
    carries the reserved :data:`CALLABLE_NAME_PREFIX`, which is how the
    executing side tells a delegation apart from a plain tool call.

    Metadata only: it carries the schema the model needs to build the call, and
    nothing else. The call itself is dispatched by resolving the AGENT resource
    at execution time, so :meth:`call` is never reached.
    """

    @staticmethod
    def of(name: str, description: str, input_schema: str) -> "SubagentTool":
        """Build the callable declaration of the named sub-agent.

        Parameters
        ----------
        name : str
            The name the AGENT resource is registered under. The function name
            the model issues is this name behind the reserved prefix.
        description : str
            What the sub-agent is for. Falls back to a generic delegation
            description, so an undescribed sub-agent stays usable. Every
            description ends with the sub-agent marker, which replaces a
            separate listing message: the model learns that the callable is a
            delegation from the description alone.
        input_schema : str
            JSON Schema of the arguments the sub-agent accepts.
        """
        callable_name = CALLABLE_NAME_PREFIX + name
        return SubagentTool(
            metadata=ToolMetadata(
                name=callable_name,
                description=(
                    description or f"Delegate a standalone task to sub-agent {name}"
                )
                + " This is subagent.",
                # The cross-language contract carries a JSON schema string, while
                # tool metadata holds a model type, so it is built here.
                args_schema=create_model_from_schema(
                    callable_name, json.loads(input_schema)
                ),
            )
        )

    @classmethod
    @override
    def tool_type(cls) -> ToolType:
        """Sub-agents are declared to the model as plain functions: the model
        builds the call the same way it builds a tool call, and only the
        executing side tells them apart.
        """
        return ToolType.FUNCTION

    @override
    def call(self, *args: Any, **kwargs: Any) -> Any:
        """Never reached: dispatch resolves the AGENT resource instead."""
        msg = "SubagentTool is metadata-only; resolve the AGENT resource at execution time."
        raise NotImplementedError(msg)
