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


def test_model_from_java_schema_without_description() -> None:
    # Java only emits a "description" key for a @ToolParam with a non-empty
    # description (ToolParam.description() defaults to ""), so a param without
    # one must be tolerated and fall back to a generated description.
    schema_str = json.dumps({"properties": {"a": {"type": "number"}}})
    field = create_model_from_java_tool_schema_str("J", schema_str).model_fields["a"]
    assert field.annotation is float
    assert field.description == "Parameter: a"


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


def test_create_model_preserves_forbidden_extra_fields() -> None:
    """Callable schemas must keep additionalProperties false when reconstructed."""
    rebuilt = create_model_from_schema(
        "StrictPrompt",
        {
            "type": "object",
            "properties": {"prompt": {"type": "string"}},
            "required": ["prompt"],
            "additionalProperties": False,
        },
    )
    assert rebuilt.model_json_schema()["additionalProperties"] is False
    with pytest.raises(ValueError):
        rebuilt(prompt="task", unexpected="extra")


def test_model_from_schema_preserves_required_and_default_independently() -> None:
    """Optional is not nullable, and a default does not override required."""
    rebuilt = create_model_from_schema(
        "RequiredAndDefault",
        {
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "limit": {"type": "integer"},
                "retries": {"type": "integer", "default": 3},
                "mode": {"type": "string", "default": "fast"},
            },
            "required": ["prompt", "mode"],
            "additionalProperties": False,
        },
    )
    advertised = rebuilt.model_json_schema()
    assert advertised["required"] == ["prompt", "mode"]
    assert "default" not in advertised["properties"]["limit"]
    assert advertised["properties"]["mode"]["default"] == "fast"
    assert rebuilt(prompt="task", mode="slow").retries == 3
    assert rebuilt(prompt="task", mode="slow").model_dump(exclude_unset=True) == {
        "prompt": "task",
        "mode": "slow",
    }
    with pytest.raises(ValidationError):
        rebuilt(prompt="task")
    with pytest.raises(ValidationError):
        rebuilt(prompt="task", mode="slow", limit=None)


@pytest.mark.parametrize("use_reference", [False, True])
def test_subagent_callable_preserves_nested_object_schema(use_reference: bool) -> None:
    """The schema advertised to a parent keeps closed nested inputs and arrays."""
    from flink_agents.api.chat_models.subagent_tool import SubagentTool

    request_schema = {
        "type": "object",
        "properties": {
            "prompt": {"type": "string"},
            "limit": {"type": "integer"},
        },
        "required": ["prompt"],
        "additionalProperties": False,
    }
    nested_schema = {"$ref": "#/$defs/Request"} if use_reference else request_schema
    tool = SubagentTool.of(
        "researcher",
        "Research tasks",
        json.dumps(
            {
                "type": "object",
                "properties": {
                    "request": nested_schema,
                    "followups": {"type": "array", "items": nested_schema},
                },
                "required": ["request"],
                "additionalProperties": False,
                "$defs": {"Request": request_schema} if use_reference else {},
            }
        ),
    )
    rebuilt = tool.metadata.args_schema
    advertised = rebuilt.model_json_schema()
    ref = advertised["properties"]["request"]["$ref"].split("/")[-1]
    nested = advertised["$defs"][ref]
    assert nested["required"] == ["prompt"]
    assert nested["additionalProperties"] is False
    assert nested["properties"]["prompt"]["type"] == "string"
    assert rebuilt(request={"prompt": "task"}).request.prompt == "task"
    with pytest.raises(ValidationError):
        rebuilt(request={})
    with pytest.raises(ValidationError):
        rebuilt(request={"prompt": "task", "extra": True})
    with pytest.raises(ValidationError):
        rebuilt(request={"prompt": "task"}, followups=[{"extra": True}])


@pytest.mark.parametrize("additional_properties", [True, {}, {"type": "integer"}])
def test_model_from_schema_keeps_dictionary_fields(additional_properties: Any) -> None:
    """Free-form and typed dictionary fields remain dictionaries."""
    rebuilt = create_model_from_schema(
        "DictionaryInput",
        {
            "type": "object",
            "properties": {
                "values": {
                    "type": "object",
                    "additionalProperties": additional_properties,
                }
            },
            "required": ["values"],
        },
    )
    assert rebuilt(values={"a": 1}).values == {"a": 1}
    if additional_properties == {"type": "integer"}:
        with pytest.raises(ValidationError):
            rebuilt(values={"a": "not an integer"})
    else:
        assert rebuilt(values={"a": {"b": "text"}}).values == {"a": {"b": "text"}}


def test_model_from_schema_keeps_free_form_root_properties() -> None:
    """A root object without declared properties still accepts arbitrary keys."""
    rebuilt = create_model_from_schema(
        "FreeForm", {"type": "object", "additionalProperties": True}
    )
    assert rebuilt(key="value").model_dump() == {"key": "value"}


@pytest.mark.parametrize("nested", [False, True])
@pytest.mark.parametrize(
    ("extra_schema", "valid_extra", "invalid_extras"),
    [
        ({"type": "integer", "minimum": 1}, 2, ["not an integer", 0]),
        (
            {
                "type": "object",
                "properties": {"label": {"type": "string"}},
                "required": ["label"],
                "additionalProperties": {"type": "integer", "minimum": 0},
            },
            {"label": "value", "count": 2},
            [{"count": 2}, {"label": "value", "count": -1}],
        ),
    ],
)
def test_typed_extra_properties_survive_metadata_round_trip(
    nested: bool, extra_schema: dict, valid_extra: Any, invalid_extras: list[Any]
) -> None:
    """Provider schemas and validation retain constraints on unnamed properties."""
    from flink_agents.api.chat_models.subagent_tool import SubagentTool
    from flink_agents.api.tools.tool import ToolMetadata
    from flink_agents.integrations.chat_models.chat_model_utils import to_openai_tool

    object_schema = {
        "type": "object",
        "properties": {"fixed": {"type": "string"}},
        "required": ["fixed"],
        "additionalProperties": extra_schema,
    }
    schema = (
        {
            "type": "object",
            "properties": {"values": object_schema},
            "required": ["values"],
        }
        if nested
        else object_schema
    )
    tool = SubagentTool.of("typed_extras", "Accept typed extras", json.dumps(schema))
    restored = ToolMetadata.model_validate_json(tool.metadata.model_dump_json())
    for metadata in [tool.metadata, restored]:
        advertised = to_openai_tool(metadata=metadata)["function"]["parameters"]
        target = advertised
        if nested:
            reference = advertised["properties"]["values"]["$ref"].split("/")[-1]
            target = advertised["$defs"][reference]
        extra = target["additionalProperties"]
        if "$ref" in extra:
            extra = advertised["$defs"][extra["$ref"].split("/")[-1]]
        for key, value in extra_schema.items():
            if key == "properties":
                assert extra[key]["label"]["type"] == "string"
            else:
                assert extra[key] == value

        valid = {"fixed": "named field", "extra": valid_extra}
        payload = {"values": valid} if nested else valid
        assert metadata.args_schema(**payload).model_dump() == payload
        for invalid_extra in invalid_extras:
            invalid = {"fixed": "named field", "extra": invalid_extra}
            with pytest.raises(ValidationError):
                metadata.args_schema(**({"values": invalid} if nested else invalid))


def test_forward_schema_references_use_json_definition_names() -> None:
    """A later definition can have a name that is not a Python identifier."""
    rebuilt = create_model_from_schema(
        "References",
        {
            "type": "object",
            "properties": {"request": {"$ref": "#/$defs/Request"}},
            "required": ["request"],
            "$defs": {
                "Request": {
                    "type": "object",
                    "additionalProperties": {"$ref": "#/$defs/Extra-Value"},
                },
                "Extra-Value": {
                    "type": "object",
                    "properties": {"count": {"type": "integer"}},
                    "required": ["count"],
                },
            },
        },
    )
    payload = {"request": {"extra": {"count": 2}}}
    assert rebuilt(**payload).model_dump() == payload
    with pytest.raises(ValidationError):
        rebuilt(request={"extra": {"count": "not an integer"}})
