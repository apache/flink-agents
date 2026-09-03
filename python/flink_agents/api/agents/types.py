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
