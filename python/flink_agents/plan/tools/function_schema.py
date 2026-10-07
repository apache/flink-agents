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
"""Compile function metadata and executable argument binding together."""

import inspect
import json
from copy import deepcopy
from typing import Annotated, Any, Callable, get_args, get_origin, get_type_hints

from docstring_parser import parse
from jsonschema import Draft202012Validator
from pydantic import ConfigDict, TypeAdapter, ValidationError, create_model
from pydantic.fields import FieldInfo

from flink_agents.api.tools.tool import ToolMetadata


class FunctionSchema:
    """A function's model-visible schema and native parameter binding.

    Compiled instances belong to the interpreter that owns the callable. They
    are not serialized or shared across interpreters.
    """

    def __init__(
        self, function: Callable, injected_args: list[str] | None = None
    ) -> None:
        """Compile the full signature and hide injected fields in model metadata."""
        self.signature = inspect.signature(function)
        hidden_names = frozenset(injected_args or ())
        unknown = hidden_names - self.signature.parameters.keys()
        if unknown:
            msg = f"Unknown injected parameters: {sorted(unknown)}"
            raise ValueError(msg)
        hints = get_type_hints(function, include_extras=True)
        doc = parse(function.__doc__ or "")
        descriptions = {p.arg_name: p.description for p in doc.params}
        fields = {}
        for name, parameter in self.signature.parameters.items():
            if parameter.kind in (parameter.VAR_POSITIONAL, parameter.VAR_KEYWORD):
                msg = f"Variadic tool parameter is unsupported: {name}"
                raise ValueError(msg)
            annotation = hints.get(name, Any)
            # Description strings in Annotated are supported without discarding
            # Field constraints or any other metadata.
            description = descriptions.get(name, f"Parameter: {name}")
            if get_origin(annotation) is Annotated:
                arguments = get_args(annotation)
                description = next(
                    (x for x in arguments[1:] if isinstance(x, str)), description
                )
            default = parameter.default
            if default is inspect.Parameter.empty:
                default = ...
            info = FieldInfo.from_annotated_attribute(annotation, default)
            if info.alias or info.validation_alias or info.serialization_alias:
                msg = f"Tool parameter aliases are unsupported: {name}"
                raise ValueError(msg)
            if info.default_factory is not None:
                msg = f"Tool defaults must be static: {name}"
                raise ValueError(msg)
            if info.description is None:
                info.description = description
            fields[name] = (annotation, info)
        self.model = create_model(
            function.__name__,
            __config__=ConfigDict(extra="forbid", validate_default=True),
            **fields,
        )
        schema = self.model.model_json_schema()
        _close_objects(schema)
        Draft202012Validator.check_schema(schema)
        self.validator = Draft202012Validator(schema)
        visible_schema = deepcopy(schema)
        for name in hidden_names:
            visible_schema["properties"].pop(name)
        if "required" in visible_schema:
            visible_schema["required"] = [
                name for name in visible_schema["required"] if name not in hidden_names
            ]
        self.metadata = ToolMetadata(
            name=function.__name__,
            description=doc.description or "",
            args_schema=visible_schema,
        )
        # Reject invalid static defaults while compiling, not on the first call.
        for name, field in self.model.model_fields.items():
            if not field.is_required():
                value = TypeAdapter(field.rebuild_annotation()).dump_python(
                    field.default, mode="json"
                )
                node = schema["properties"][name]
                errors = list(self.validator.evolve(schema=node).iter_errors(value))
                if errors:
                    msg = f"Invalid default for {name}: {errors[0].message}"
                    raise ValueError(msg)

    def bind(self, arguments: dict) -> dict:
        """Validate JSON arguments and return native values for the callable."""
        if not isinstance(arguments, dict):
            msg = "INVALID_ARGUMENT /: type (expected object)"
            raise ValueError(msg)  # noqa: TRY004 - stable argument error contract
        values = deepcopy(arguments)
        # Reject NaN/Infinity before a validator or native binder can accept them.
        try:
            _check_json_keys(values)
            json.dumps(values, allow_nan=False)
        except (TypeError, ValueError) as error:
            msg = "INVALID_ARGUMENT /: non-JSON value"
            raise ValueError(msg) from error
        _apply_defaults(values, self.validator.schema, self.validator.schema)
        errors = sorted(
            self.validator.iter_errors(values),
            key=lambda e: (tuple(map(str, e.absolute_path)), str(e.validator)),
        )
        if errors:
            error = errors[0]
            path = "/" + "/".join(
                str(p).replace("~", "~0").replace("/", "~1")
                for p in error.absolute_path
            )
            msg = f"INVALID_ARGUMENT {path}: {error.validator}"
            raise ValueError(msg)
        try:
            bound = self.model.model_validate(values)
        except ValidationError as error:
            msg = f"BINDING_ERROR: {error}"
            raise ValueError(msg) from error
        # Do not model_dump: nested model instances must reach the callable intact.
        return {name: getattr(bound, name) for name in self.model.model_fields}


def _check_json_keys(value: Any) -> None:
    if isinstance(value, dict):
        if any(not isinstance(key, str) for key in value):
            msg = "Object keys must be strings"
            raise ValueError(msg)
        for item in value.values():
            _check_json_keys(item)
    elif isinstance(value, list):
        for item in value:
            _check_json_keys(item)


def _close_objects(node: Any) -> None:
    if isinstance(node, dict):
        if "properties" in node:
            node.setdefault("additionalProperties", False)
        for key, value in node.items():
            # Annotation payloads are data, not schemas (a default may itself
            # contain a key named "properties").
            if key not in {"default", "examples", "enum", "const"}:
                _close_objects(value)
    elif isinstance(node, list):
        for value in node:
            _close_objects(value)


def _apply_defaults(value: Any, schema: dict, root: dict) -> None:
    if "$ref" in schema:
        reference = schema["$ref"]
        if not reference.startswith("#/"):
            msg = "External schema references are unsupported"
            raise ValueError(msg)
        target = root
        for part in reference[2:].split("/"):
            target = target[part.replace("~1", "/").replace("~0", "~")]
        _apply_defaults(value, target, root)
    if isinstance(value, dict):
        for name, field in schema.get("properties", {}).items():
            if name not in value and "default" in field:
                value[name] = deepcopy(field["default"])
            if name in value:
                _apply_defaults(value[name], field, root)
        additional = schema.get("additionalProperties")
        if isinstance(additional, dict):
            for name in value.keys() - schema.get("properties", {}).keys():
                _apply_defaults(value[name], additional, root)
    elif isinstance(value, list) and isinstance(schema.get("items"), dict):
        for item in value:
            _apply_defaults(item, schema["items"], root)
    # Nullable model/collection schemas have exactly one non-null branch.
    branches = [s for s in schema.get("anyOf", []) if s.get("type") != "null"]
    if value is not None and len(branches) == 1:
        _apply_defaults(value, branches[0], root)
