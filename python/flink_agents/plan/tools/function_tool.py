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

from pydantic import Field, PrivateAttr, model_validator
from typing_extensions import override

from flink_agents.api.tools.tool import Tool, ToolMetadata, ToolType
from flink_agents.api.tools.tool_parameter_injection import (
    InjectedArg,
    merge_injected_args,
    normalize_injected_args,
    validate_injected_arg_names,
)
from flink_agents.plan.function import (
    JavaFunction,
    PythonFunction,
)
from flink_agents.plan.tools.function_schema import FunctionSchema


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
    _schema: FunctionSchema | None = PrivateAttr(default=None)

    @model_validator(mode="before")
    @classmethod
    def _normalize_injected_args(cls, data: dict) -> dict:
        if isinstance(data, dict) and "injected_args" in data:
            data = dict(data)
            data["injected_args"] = normalize_injected_args(data["injected_args"])
        return data

    @model_validator(mode="after")
    def _bind_function(self) -> "FunctionTool":
        if isinstance(self.func, PythonFunction):
            callable_ = self.func.as_callable()
            self.injected_args = merge_injected_args(
                getattr(callable_, "_injected_args", None),
                self.injected_args,
                tool_name=callable_.__qualname__,
            )
            validate_injected_arg_names(callable_, self.injected_args)
            self._schema = FunctionSchema(callable_, list(self.injected_args))
            derived = self._schema.metadata
            self.metadata = ToolMetadata(
                name=self.metadata.name if self.metadata else derived.name,
                description=self.metadata.description
                if self.metadata
                else derived.description,
                args_schema=derived.args_schema,
            )
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
        metadata, annotated_args = _java_metadata(adapter, self.func, list(self.injected_args))
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
        """Validate and execute the complete arguments supplied by the caller."""
        if args:
            if self._schema is None:
                msg = "Java tools require named arguments"
                raise TypeError(msg)
            kwargs = self._schema.signature.bind_partial(*args, **kwargs).arguments
        if self._schema is not None:
            values = self._schema.bind(kwargs)
            positional = []
            for name, parameter in self._schema.signature.parameters.items():
                if parameter.kind is parameter.POSITIONAL_ONLY:
                    positional.append(values.pop(name))
            return self.func(*positional, **values)
        return self.func(**kwargs)


def _java_metadata(
    adapter: Any, func: JavaFunction, injected_names: list[str]
) -> tuple[ToolMetadata, dict[str, InjectedArg]]:
    flat = adapter.getJavaToolMetadata(
        func.qualname, func.method_name, func.parameter_types, injected_names
    )
    name = flat["name"]
    metadata = ToolMetadata(
        name=name,
        description=flat.get("description", ""),
        args_schema=json.loads(flat.get("inputSchema", "{}")),
    )
    return metadata, _parse_injected_args_json(flat.get("injectedArgs"))


def _parse_injected_args_json(payload: str | None) -> dict[str, InjectedArg]:
    if not payload:
        return {}
    raw = json.loads(payload)
    return normalize_injected_args(raw)
