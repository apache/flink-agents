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
import json
from typing import Annotated, Any, List

import pytest
from pydantic import BaseModel, Field, ValidationError

from flink_agents.api.tools.utils import (
    create_java_tool_schema_str_from_model,
    create_model_from_java_tool_schema_str,
    create_model_from_schema,
    create_schema_from_function,
)

# ---- create_schema_from_function ---------------------------------------------


def _sample_function(
    a: int,
    b: str = "x",
    c=5,
    d: Annotated[int, "annotated desc"] = 0,
) -> None:
    """Sample function for schema extraction.

    Parameters
    ----------
    a : int
        the a param
    b : str
        the b param
    """
    raise NotImplementedError


def test_schema_from_function_required_param() -> None:
    field = create_schema_from_function("sample", _sample_function).model_fields["a"]
    assert field.annotation is int
    assert field.is_required()
    assert field.description == "the a param"


def test_schema_from_function_default_makes_param_optional() -> None:
    field = create_schema_from_function("sample", _sample_function).model_fields["b"]
    assert field.annotation is str
    assert not field.is_required()
    assert field.default == "x"
    assert field.description == "the b param"


def test_schema_from_function_unannotated_param_is_any() -> None:
    field = create_schema_from_function("sample", _sample_function).model_fields["c"]
    # No annotation falls back to Any, and the missing docstring entry to a
    # placeholder description.
    assert field.annotation is Any
    assert field.default == 5
    assert field.description == "Parameter: c"


def test_schema_from_function_annotated_metadata_is_description() -> None:
    field = create_schema_from_function("sample", _sample_function).model_fields["d"]
    # Annotated[int, "annotated desc"] keeps the int type and uses the metadata
    # string as the field description.
    assert field.annotation is int
    assert field.description == "annotated desc"
    assert field.default == 0


# ---- create_model_from_schema ------------------------------------------------


class _Inner(BaseModel):
    x: int


class _Outer(BaseModel):
    name: str = Field(description="the name")
    count: int = Field(ge=0, le=10, default=1)
    tags: List[str] = []
    maybe: int | None = None
    inner: _Inner


@pytest.fixture(scope="module")
def rebuilt_model() -> type[BaseModel]:
    return create_model_from_schema("Rebuilt", _Outer.model_json_schema())


def test_model_from_schema_scalar_field(rebuilt_model: type[BaseModel]) -> None:
    field = rebuilt_model.model_fields["name"]
    assert field.annotation is str
    assert field.is_required()
    assert field.description == "the name"


def test_model_from_schema_typed_array(rebuilt_model: type[BaseModel]) -> None:
    assert rebuilt_model.model_fields["tags"].annotation == list[str]


def test_model_from_schema_optional_field(rebuilt_model: type[BaseModel]) -> None:
    assert not rebuilt_model.model_fields["maybe"].is_required()
    assert rebuilt_model(name="a", inner={"x": 1}).maybe is None


def test_model_from_schema_nested_model(rebuilt_model: type[BaseModel]) -> None:
    inner_field = rebuilt_model.model_fields["inner"]
    assert isinstance(inner_field.annotation, type)
    assert issubclass(inner_field.annotation, BaseModel)
    with pytest.raises(ValidationError):
        rebuilt_model(name="a", inner={})  # nested 'x' is required


def test_model_from_schema_enforces_constraints(rebuilt_model: type[BaseModel]) -> None:
    # ge=0, le=10 from the source schema are kept on the rebuilt model.
    assert rebuilt_model(name="a", count=5, inner={"x": 1}).count == 5
    with pytest.raises(ValidationError):
        rebuilt_model(name="a", count=99, inner={"x": 1})


# ---- create_model_from_java_tool_schema_str ----------------------------------


def test_model_from_java_schema_type_mapping() -> None:
    schema_str = json.dumps(
        {
            "properties": {
                "s": {"type": "string", "description": "d"},
                "i": {"type": "integer", "description": "d"},
                "f": {"type": "number", "description": "d"},
                "b": {"type": "boolean", "description": "d"},
                "arr": {"type": "array", "description": "d"},
                "obj": {"type": "object", "description": "d"},
            }
        }
    )
    fields = create_model_from_java_tool_schema_str("J", schema_str).model_fields
    assert fields["s"].annotation is str
    assert fields["i"].annotation is int
    assert fields["f"].annotation is float
    assert fields["b"].annotation is bool
    assert fields["arr"].annotation is list
    assert fields["obj"].annotation is dict


def test_model_from_java_schema_preserves_description() -> None:
    schema_str = json.dumps(
        {"properties": {"id": {"type": "string", "description": "the id"}}}
    )
    field = create_model_from_java_tool_schema_str("J", schema_str).model_fields["id"]
    assert field.description == "the id"


def test_model_from_java_schema_fields_are_required() -> None:
    schema_str = json.dumps(
        {"properties": {"a": {"type": "string", "description": "d"}}}
    )
    field = create_model_from_java_tool_schema_str("J", schema_str).model_fields["a"]
    assert field.is_required()


# ---- create_java_tool_schema_str_from_model ----------------------------------


class _JavaSourceModel(BaseModel):
    id: str = Field(description="the id")
    n: int = Field(description="a number")
    opt: str | None = Field(default=None, description="optional one")


def test_java_schema_from_model_types_and_descriptions() -> None:
    props = json.loads(create_java_tool_schema_str_from_model(_JavaSourceModel))[
        "properties"
    ]
    assert props["id"] == {"type": "string", "description": "the id"}
    assert props["n"] == {"type": "integer", "description": "a number"}
    # `str | None` is unwrapped to its inner "string" type.
    assert props["opt"]["type"] == "string"


def test_java_schema_from_model_required_list() -> None:
    schema = json.loads(create_java_tool_schema_str_from_model(_JavaSourceModel))
    assert set(schema["required"]) == {"id", "n"}
    assert "opt" not in schema["required"]


# ---- round trip --------------------------------------------------------------


class _RoundTripModel(BaseModel):
    id: str = Field(description="the id")
    n: int = Field(description="a number")


def test_java_schema_round_trip_preserves_fields() -> None:
    # create_model_from_java_tool_schema_str is the documented inverse of
    # create_java_tool_schema_str_from_model for names, types and descriptions.
    schema_str = create_java_tool_schema_str_from_model(_RoundTripModel)
    rebuilt = create_model_from_java_tool_schema_str("RoundTrip", schema_str)

    original = _RoundTripModel.model_fields
    result = rebuilt.model_fields
    assert set(result) == set(original)
    for name, field in original.items():
        assert result[name].annotation is field.annotation
        assert result[name].description == field.description
