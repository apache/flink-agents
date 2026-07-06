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
import json
from typing import Any

from docstring_parser import parse
from pydantic import Field, model_validator
from typing_extensions import override

from flink_agents.api.tools.tool import Tool, ToolMetadata, ToolType
from flink_agents.api.tools.tool_parameter_injection import (
    InjectedArg,
    merge_injected_args,
    normalize_injected_args,
    validate_injected_arg_names,
)
from flink_agents.api.tools.utils import (
    create_model_from_java_tool_schema_str,
    create_schema_from_function,
)
from flink_agents.plan.function import JavaFunction, PythonFunction


class FunctionTool(Tool):
    """Executable function tool.

    ``metadata`` is filled eagerly as soon as the value is derivable —
    during model validation for ``PythonFunction`` (from the callable's
    docstring/signature), and inside :meth:`set_java_resource_adapter`
    once the runtime injects the JVM bridge for ``JavaFunction``. Until
    that injection the field stays ``None``.
    """

    func: PythonFunction | JavaFunction
    injected_args: dict[str, InjectedArg] = Field(default_factory=dict)

    @model_validator(mode="before")
    @classmethod
    def _normalize_injected_args(cls, data: dict) -> dict:
        if isinstance(data, dict) and "injected_args" in data:
            data = dict(data)
            data["injected_args"] = normalize_injected_args(data["injected_args"])
        return data

    @model_validator(mode="after")
    def _eager_derive_python_metadata(self) -> "FunctionTool":
        if isinstance(self.func, PythonFunction):
            callable_ = self.func.as_callable()
            self.injected_args = merge_injected_args(
                getattr(callable_, "_injected_args", None),
                self.injected_args,
                tool_name=callable_.__qualname__,
            )
            validate_injected_arg_names(callable_, self.injected_args)
        if self.metadata is None and isinstance(self.func, PythonFunction):
            self.metadata = _python_metadata(self.func, list(self.injected_args))
        return self

    def set_java_resource_adapter(self, adapter: Any) -> None:
        """Inject the JVM resource adapter and derive ``metadata``. Called
        by the runtime resource cache when the tool is first materialised.
        Java-declared injected args returned by the bridge are merged into this
        tool so Python ``tool_call_action`` can inject them at execution time.
        No-op when ``func`` is not a ``JavaFunction``.
        """
        if not isinstance(self.func, JavaFunction):
            return
        self.func.set_java_resource_adapter(adapter)
        metadata, annotated_args = _java_metadata(self.func, list(self.injected_args))
        self.injected_args = merge_injected_args(
            annotated_args,
            self.injected_args,
            tool_name=metadata.name,
        )
        self.metadata = metadata

    @classmethod
    @override
    def tool_type(cls) -> ToolType:
        """Get the tool type."""
        return ToolType.FUNCTION

    @override
    def call(self, *args: Any, **kwargs: Any) -> Any:
        """Invoke the underlying function."""
        return self.func(*args, **kwargs)


def _python_metadata(func: PythonFunction, injected_args: list[str] | None = None) -> ToolMetadata:
    callable_ = func.as_callable()
    description = parse(callable_.__doc__).description or ""
    return ToolMetadata(
        name=callable_.__name__,
        description=description,
        args_schema=create_schema_from_function(
            callable_.__name__, func=callable_, injected_args=injected_args
        ),
    )


def _java_metadata(
    func: JavaFunction, injected_args: list[str] | None = None
) -> tuple[ToolMetadata, dict[str, InjectedArg]]:
    adapter = func._j_resource_adapter
    if adapter is None:
        msg = (
            "Java function tool metadata requires the JVM resource adapter; "
            "not set on the underlying JavaFunction. The runtime should "
            "inject it via FunctionTool.set_java_resource_adapter before "
            "metadata access."
        )
        raise RuntimeError(msg)
    flat = adapter.getJavaToolMetadata(
        func.qualname,
        func.method_name,
        func.parameter_types,
        injected_args or [],
    )
    name = flat["name"]
    metadata = ToolMetadata(
        name=name,
        description=flat.get("description", ""),
        args_schema=create_model_from_java_tool_schema_str(
            name, flat.get("inputSchema", "{}")
        ),
    )
    return metadata, _parse_injected_args_json(flat.get("injectedArgs"))


def _parse_injected_args_json(payload: str | None) -> dict[str, InjectedArg]:
    if not payload:
        return {}
    raw = json.loads(payload)
    return normalize_injected_args(raw)
