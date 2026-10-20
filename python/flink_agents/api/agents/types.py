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
import importlib
import json
from typing import Any, Callable

from pydantic import BaseModel, ConfigDict, model_serializer, model_validator
from pyflink.common.typeinfo import BasicType, BasicTypeInfo, RowTypeInfo


class OutputSchema(BaseModel):
    """Util class to help serialize and deserialize output schema json."""

    model_config = ConfigDict(arbitrary_types_allowed=True)
    output_schema: type[BaseModel] | RowTypeInfo

    @model_serializer
    def __custom_serializer(self) -> dict[str, Any]:
        if isinstance(self.output_schema, RowTypeInfo):
            data = {
                "output_schema": {
                    "names": self.output_schema.get_field_names(),
                    "types": [
                        type._basic_type.value
                        for type in self.output_schema.get_field_types()
                    ],
                },
            }
        else:
            data = {
                "output_schema": {
                    "module": self.output_schema.__module__,
                    "class": self.output_schema.__name__,
                }
            }
        return data

    @model_validator(mode="before")
    def __custom_deserialize(self) -> "OutputSchema":
        output_schema = self["output_schema"]
        if isinstance(output_schema, dict):
            if "names" in output_schema:
                self["output_schema"] = RowTypeInfo(
                    field_types=[
                        BasicTypeInfo(BasicType(type))
                        for type in output_schema["types"]
                    ],
                    field_names=output_schema["names"],
                )
            else:
                module = importlib.import_module(output_schema["module"])
                self["output_schema"] = getattr(module, output_schema["class"])
        return self


def native_output_model(output_schema: OutputSchema | None) -> type[BaseModel] | None:
    """The Pydantic model an output schema would be sent natively as, or ``None``.

    Kept apart from ``render_output_schema`` because a connection makes two checks
    before rendering: whether native structured output can apply, and whether the
    schema conflicts with a caller-supplied response format. Neither check renders
    the schema. Rendering raises on a schema it cannot express, so the check would
    report a render failure instead of its own answer.

    Args:
        output_schema: The output schema requested for the response, if any.

    Returns:
        The ``BaseModel`` subclass the schema holds. ``None`` covers both no schema at
        all and a ``RowTypeInfo``, which has no native translation and keeps the
        prompt-engineering fallback.
    """
    model = (
        output_schema.output_schema if isinstance(output_schema, OutputSchema) else None
    )
    if not (isinstance(model, type) and issubclass(model, BaseModel)):
        return None
    return model


def render_output_schema(
    model: type[BaseModel], render: Callable[[type[BaseModel]], dict[str, Any]]
) -> dict[str, Any]:
    """Render an output schema, reporting a render failure clearly.

    The caller supplies ``render`` because callers differ in the wire format they
    need, and this module cannot depend on the renderers that produce it.

    The model's own render runs first so that a model no JSON Schema can express is
    reported as exactly that. ``render`` renders the model itself, so without that
    first attempt the same failure would surface worded as a translation failure.
    The call to ``render`` is wrapped in turn because a renderer can reject a model
    the model's own render accepts, such as one carrying an untyped member that
    renders to a document with no ``type``.

    Whatever ``render`` produces is returned as produced. A document that declares
    no fields is not refused here: whether it is usable belongs to the caller that
    consumes it, and refusing it would fail a request that succeeds today.

    Args:
        model: The model class describing the shape the response must take.
        render: Renders ``model`` in the wire format the caller expects.

    Returns:
        The document ``render`` produced.

    Raises:
        TypeError: If ``model`` has no JSON Schema, or if ``render`` fails or returns
            something other than a document.
    """
    try:
        # Run for its failure, not for its value.
        model.model_json_schema()
    except Exception as e:
        msg = (
            f"Output schema {model.__module__}.{model.__qualname__} cannot be"
            " rendered as a JSON Schema, so it cannot constrain the response. Use a"
            " schema whose fields are all JSON-Schema-renderable, or pass no output"
            f" schema. Rendering it reported: {e}"
        )
        raise TypeError(msg) from e

    try:
        schema = render(model)
    except Exception as e:
        msg = (
            f"Output schema {model.__module__}.{model.__qualname__} cannot be"
            " translated by the renderer in use, so it cannot constrain the response."
            " Use a schema whose fields are all JSON-Schema-renderable, or pass no"
            f" output schema. The renderer reported: {e}"
        )
        raise TypeError(msg) from e
    if not isinstance(schema, dict):
        msg = (
            f"Output schema {model.__module__}.{model.__qualname__} rendered to"
            f" {type(schema).__name__} rather than a JSON Schema document, so it"
            " cannot constrain the response. Supply a renderer that returns a JSON"
            " Schema document, or pass no output schema."
        )
        raise TypeError(msg)
    return schema


def contains_open_object(model: type[BaseModel]) -> bool:
    """Whether ``model``'s JSON Schema admits keys it does not declare, at any depth.

    A ``dict`` field does, wherever it sits, and so does a model configured with
    ``extra="allow"``: their keys are not known in advance. A strict native schema
    requires every object to be closed, so it cannot state either, and a connection
    whose native form is strict keeps the prompt path for such a model.

    Only the keywords that hold subschemas are followed. Keys under ``properties`` and
    ``$defs`` are names, so a field named ``additionalProperties`` does not count.

    Args:
        model: The model class describing the shape the response must take.

    Returns:
        ``True`` if some object in the schema sets ``additionalProperties`` to
        anything but ``False``, or sets ``patternProperties``. ``False`` when the
        model has no JSON Schema, leaving that failure to ``render_output_schema``,
        which reports it clearly.
    """
    try:
        schema = model.model_json_schema()
    except Exception:
        return False
    return _admits_undeclared_keys(schema)


def _admits_undeclared_keys(schema: Any) -> bool:
    if not isinstance(schema, dict):
        return False
    if schema.get("additionalProperties", False) is not False:
        return True
    if "patternProperties" in schema:
        return True
    for keyword in ("properties", "$defs"):
        subschemas = schema.get(keyword)
        if isinstance(subschemas, dict) and any(
            _admits_undeclared_keys(subschema) for subschema in subschemas.values()
        ):
            return True
    for keyword in ("prefixItems", "anyOf", "oneOf", "allOf"):
        subschemas = schema.get(keyword)
        if isinstance(subschemas, list) and any(
            _admits_undeclared_keys(subschema) for subschema in subschemas
        ):
            return True
    return _admits_undeclared_keys(schema.get("items")) or _admits_undeclared_keys(
        schema.get("not")
    )


_ROW_JSON_SCHEMA_TYPES = {
    BasicType.STRING: "string",
    BasicType.BOOLEAN: "boolean",
    BasicType.BYTE: "integer",
    BasicType.SHORT: "integer",
    BasicType.INT: "integer",
    BasicType.LONG: "integer",
    BasicType.BIG_INT: "integer",
    BasicType.FLOAT: "number",
    BasicType.DOUBLE: "number",
    BasicType.BIG_DEC: "number",
}


def render_row_type_json_schema(row_type: RowTypeInfo) -> str | None:
    """Render a flat ``RowTypeInfo`` as a JSON Schema object.

    Every field is required and no additional properties are allowed. The Java
    ``RowTypeJsonSchema`` produces the same text for the same row, so both
    languages describe a row to the model identically.

    Returns ``None`` when a field has no supported mapping in this renderer,
    such as a char or instant field, or when the names are duplicated or don't
    match the types; the caller then describes the row another way.
    """
    json_types = [
        _ROW_JSON_SCHEMA_TYPES.get(getattr(field_type, "_basic_type", None))
        for field_type in row_type.get_field_types()
    ]
    if None in json_types:
        return None
    # get_field_names() starts the JVM for a row built without names; Java names
    # those fields f0, f1, ... too.
    names = row_type._field_names
    if names is None:
        names = [f"f{i}" for i in range(len(json_types))]
    # Java rejects these rows; Python keeps describing them another way.
    if len(names) != len(json_types) or len(set(names)) != len(names):
        return None
    properties = {
        name: {"type": json_type}
        for name, json_type in zip(names, json_types, strict=True)
    }
    schema = {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }
    return json.dumps(schema, separators=(",", ":"), ensure_ascii=False)
