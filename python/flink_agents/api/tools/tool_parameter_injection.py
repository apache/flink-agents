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
import inspect
from collections.abc import Callable
from enum import Enum

from pydantic import BaseModel


class ToolParameterSource(str, Enum):
    """Source for a framework-injected tool parameter."""

    CONFIG = "config"
    SENSORY_MEMORY = "sensory_memory"
    SHORT_TERM_MEMORY = "short_term_memory"

    @classmethod
    def _missing_(cls, value: object) -> "ToolParameterSource | None":
        if not isinstance(value, str):
            return None
        normalized = value.lower()
        for source in cls:
            if source.value == normalized:
                return source
        return None


class InjectedArg(BaseModel):
    """Declarative source binding for a framework-injected tool parameter."""

    source: ToolParameterSource = ToolParameterSource.SENSORY_MEMORY
    key: str | None = None

    @staticmethod
    def from_config(key: str) -> "InjectedArg":
        """Create an injected argument read from global agent config."""
        return InjectedArg(source=ToolParameterSource.CONFIG, key=key)

    @staticmethod
    def from_sensory_memory(path: str) -> "InjectedArg":
        """Create an injected argument read from sensory memory."""
        return InjectedArg(source=ToolParameterSource.SENSORY_MEMORY, key=path)

    @staticmethod
    def from_short_term_memory(path: str) -> "InjectedArg":
        """Create an injected argument read from short-term memory."""
        return InjectedArg(source=ToolParameterSource.SHORT_TERM_MEMORY, key=path)

    def with_default_key(self, key: str) -> "InjectedArg":
        """Use the tool parameter name when no explicit key is configured."""
        if self.key:
            return self
        return InjectedArg(source=self.source, key=key)


def normalize_injected_args(
    injected_args: dict[str, InjectedArg | dict] | None,
) -> dict[str, InjectedArg]:
    """Normalize public injected_args forms into a param -> source map."""
    if injected_args is None:
        return {}
    if not isinstance(injected_args, dict):
        msg = "'injected_args' must be a dict mapping parameter names to injection specs."
        raise TypeError(msg)
    result: dict[str, InjectedArg] = {}
    for name, spec in injected_args.items():
        if isinstance(spec, InjectedArg):
            result[name] = spec.with_default_key(name)
        elif isinstance(spec, dict):
            result[name] = InjectedArg.model_validate(spec).with_default_key(name)
        else:
            msg = f"Unsupported injected arg spec for {name!r}: {spec!r}"
            raise TypeError(msg)
    return result


def merge_injected_args(
    annotated_args: dict[str, InjectedArg] | None,
    declared_args: dict[str, InjectedArg] | None,
    *,
    tool_name: str,
) -> dict[str, InjectedArg]:
    """Merge decorator-declared and descriptor-declared injected args."""
    merged = dict(annotated_args or {})
    for name, spec in (declared_args or {}).items():
        existing = merged.get(name)
        if existing is not None and existing != spec:
            msg = (
                f"Tool {tool_name!r}: injected_args conflict for parameter "
                f"{name!r} between @tool and descriptor."
            )
            raise ValueError(msg)
        merged[name] = spec
    return merged


def validate_injected_arg_names(
    func: Callable, injected_args: dict[str, InjectedArg]
) -> None:
    """Validate that declarative injected args target real callable parameters."""
    if not injected_args:
        return
    signature = inspect.signature(func)
    parameters = signature.parameters
    if any(
        parameter.kind is inspect.Parameter.VAR_KEYWORD
        for parameter in parameters.values()
    ):
        return
    unknown = [name for name in injected_args if name not in parameters]
    if unknown:
        msg = (
            "Injected tool parameter(s) "
            f"{', '.join(sorted(unknown))} do not match function "
            f"{func.__qualname__} parameters."
        )
        raise ValueError(msg)
