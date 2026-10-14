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
"""Real function tools used by Java/Python contract tests."""

import json
from typing import Annotated

from pydantic import BaseModel, Field


class Options(BaseModel):
    name: Annotated[str, Field(min_length=1)]


calls = 0


def contract_tool(
    query: Annotated[str, Field(min_length=1)],
    options: Options,
    tenant_id: str,
    limit: Annotated[int, Field(ge=1, le=100)] = 10,
    note: str | None = None,
) -> str:
    global calls
    calls += 1
    assert isinstance(options, Options)
    return f"{query}:{options.name}:{limit}:{tenant_id}:{note or ''}"


def reset_calls():
    global calls
    calls = 0


def get_calls():
    return calls


def call_java(adapter, class_name: str, payload: str) -> str:
    from flink_agents.api.tools.tool_parameter_injection import InjectedArg
    from flink_agents.plan.function import JavaFunction
    from flink_agents.plan.tools.function_tool import FunctionTool

    tool = FunctionTool(
        func=JavaFunction(
            qualname=class_name,
            method_name="contractTool",
            parameter_types=[
                "java.lang.String",
                class_name + "$Options",
                "java.lang.String",
                "int",
                "java.lang.String",
            ],
        ),
        injected_args={"tenant_id": InjectedArg.from_sensory_memory("tenant_id")},
    )
    tool.set_java_resource_adapter(adapter)
    arguments = json.loads(payload)
    arguments["tenant_id"] = "tenant"
    result = tool.call(**arguments)
    return json.dumps(
        {
            "success": result.is_success(),
            "result": result.get_text() if result.is_success() else None,
            "error": result.error_message,
        }
    )
