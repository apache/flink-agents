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


def render_output_schema(
    model: type[BaseModel], render: Callable[[type[BaseModel]], dict[str, Any]]
) -> dict[str, Any]:
    """Render an output schema, refusing one that cannot constrain the response.

    Whether a model expresses a constraint is a property of the model, not of a
    provider's wire format, so the check runs against the model's own JSON Schema
    and only the returned document comes from ``render``. That keeps the check
    independent of how any SDK normalizes a schema: one renderer rewrites every
    map-typed member into an empty object, which is indistinguishable from a model
    that declares no fields at all.

    The renderer is supplied by the caller because each chat model translates a
    schema with its own vendor renderer, and this module may not depend on any of
    them. Both renders can fail, and they fail for different reasons: the model's
    own render rejects a model that has no JSON Schema at all, while a vendor
    renderer additionally rejects models it will not accept, such as one carrying
    an untyped member that renders to a document with no ``type``. Neither wrapper
    is therefore removable.

    Args:
        model: The model class describing the shape the response must take.
        render: Renders ``model`` in the wire format the chat model expects.

    Returns:
        The document ``render`` produced.

    Raises:
        TypeError: If ``model`` has no JSON Schema, if it renders to one containing
            an object that declares no properties and so constrains nothing, or if
            ``render`` fails or returns something other than a document.
    """
    try:
        document = model.model_json_schema()
    except Exception as e:
        msg = (
            f"Output schema {model.__module__}.{model.__qualname__} cannot be"
            " rendered as a JSON Schema, so it cannot constrain the response. Use a"
            " schema whose fields are all JSON-Schema-renderable, or pass no output"
            f" schema. Rendering it reported: {e}"
        )
        raise TypeError(msg) from e

    defs = document.get("$defs")
    _reject_empty_objects(
        document, "$", defs if isinstance(defs, dict) else {}, set(), model
    )

    try:
        schema = render(model)
    except Exception as e:
        msg = (
            f"Output schema {model.__module__}.{model.__qualname__} cannot be"
            " translated for this chat model, so it cannot constrain the response."
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


def _reject_empty_objects(
    node: Any,
    path: str,
    defs: dict[str, Any],
    visited: set[int],
    model: type[BaseModel],
) -> None:
    """Raise if any object below ``node`` declares an empty ``properties``.

    ``properties`` present and empty is an object that admits every response and
    rejects none. ``properties`` absent is a free-form map such as
    ``dict[str, str]``, which is a legitimate constraint and is left alone.

    An empty ``properties`` still constrains something when ``additionalProperties``
    carries a schema, which bounds every extra member, or ``True``, which admits
    them deliberately. Only an absent or ``False`` ``additionalProperties`` leaves
    the object expressing nothing. The tests are identity comparisons because a
    JSON Schema document may hold ``{}`` there, which is a legitimate schema and is
    falsy in Python.

    Descends through ``properties``, ``items``, ``prefixItems``, a schema-valued
    ``additionalProperties``, the ``anyOf``/``oneOf``/``allOf`` branches, and any
    ``$defs`` entry a ``$ref`` reaches.
    """
    if not isinstance(node, dict) or id(node) in visited:
        return
    visited.add(id(node))

    ref = node.get("$ref")
    if isinstance(ref, str):
        prefix = "#/$defs/"
        target = defs.get(ref[len(prefix) :]) if ref.startswith(prefix) else None
        _reject_empty_objects(target, path, defs, visited, model)
        return

    properties = node.get("properties")
    additional = node.get("additionalProperties")
    if (
        node.get("type") == "object"
        and (additional is None or additional is False)
        and isinstance(properties, dict)
        and not properties
    ):
        msg = (
            f"Output schema {model.__module__}.{model.__qualname__} renders to a"
            " JSON Schema that cannot constrain the response: the object at path"
            f" {path} has no properties. Use a schema whose objects each declare at"
            " least one field, or pass no output schema."
        )
        raise TypeError(msg)

    if isinstance(properties, dict):
        for name, child in properties.items():
            _reject_empty_objects(child, f"{path}.{name}", defs, visited, model)
    _reject_empty_objects(node.get("items"), path, defs, visited, model)
    _reject_empty_objects(node.get("additionalProperties"), path, defs, visited, model)
    for keyword in ("prefixItems", "anyOf", "oneOf", "allOf"):
        branches = node.get(keyword)
        if isinstance(branches, list):
            for branch in branches:
                _reject_empty_objects(branch, path, defs, visited, model)
