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
from typing import Any, Callable, Dict

import pytest
from pydantic import BaseModel, ConfigDict, Field
from pydantic.errors import PydanticInvalidForJsonSchema

from flink_agents.api.agents.types import render_output_schema


def _render(model: type[BaseModel]) -> dict[str, Any]:
    return model.model_json_schema()


def _render_strict(model: type[BaseModel]) -> dict[str, Any]:
    """Render the way the strict renderers do, closing every object."""
    return _close_objects(model.model_json_schema())


def _close_objects(node: Any) -> Any:
    if isinstance(node, list):
        return [_close_objects(item) for item in node]
    if not isinstance(node, dict):
        return node
    closed = {key: _close_objects(value) for key, value in node.items()}
    if closed.get("type") == "object" and "additionalProperties" not in closed:
        closed["additionalProperties"] = False
    return closed


def _render_anthropic_style(model: type[BaseModel]) -> dict[str, Any]:
    """Empty every map member's properties, as one vendor renderer normalizes them.

    A local stand-in: ``api`` may not depend on an integration's SDK.
    """
    return {
        "type": "object",
        "properties": {
            "m": {"type": "object", "properties": {}, "additionalProperties": False}
        },
        "additionalProperties": False,
    }


def _render_failing(model: type[BaseModel]) -> dict[str, Any]:
    """Fail the way a vendor renderer does on a schema it cannot translate."""
    msg = "unsupported by this provider"
    raise ValueError(msg)


class Unrenderable(BaseModel):
    cb: Callable[[int], int]


class NestsUnrenderable(BaseModel):
    inner: Unrenderable


class FieldLess(BaseModel):
    pass


class NestsFieldLess(BaseModel):
    inner: FieldLess


class ListsFieldLess(BaseModel):
    xs: list[FieldLess]


class TuplesFieldLess(BaseModel):
    t: tuple[FieldLess, int]


class MapsToFieldLess(BaseModel):
    m: dict[str, FieldLess]


_EMPTY_OBJECT = {"type": "object", "properties": {}}


class AnyOfsFieldLess(BaseModel):
    u: FieldLess | int


class OneOfsFieldLess(BaseModel):
    u: int = Field(json_schema_extra={"oneOf": [_EMPTY_OBJECT]})


class AllOfsFieldLess(BaseModel):
    u: int = Field(json_schema_extra={"allOf": [_EMPTY_OBJECT]})


class TypedMap(BaseModel):
    m: dict[str, str]


class FreeFormMap(BaseModel):
    m: dict[str, Any]


class TypedExtras(BaseModel):
    model_config = ConfigDict(extra="allow")
    __pydantic_extra__: Dict[str, str]


class OpenExtras(BaseModel):
    model_config = ConfigDict(
        extra="allow", json_schema_extra={"additionalProperties": {}}
    )


class ObjectTypedMember(BaseModel):
    m: int = Field(json_schema_extra={"type": "object"})


class ForbidsExtra(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ExtraAllowed(BaseModel):
    model_config = ConfigDict(extra="allow")


class Node(BaseModel):
    name: str
    children: list["Node"] = []


class Renderable(BaseModel):
    x: int


def test_unrenderable_member_raises_naming_the_model() -> None:
    """A member the renderer cannot express fails with a clear, chained error."""
    with pytest.raises(TypeError, match="Unrenderable") as exc_info:
        render_output_schema(Unrenderable, _render)

    assert "pass no output schema" in str(exc_info.value)
    assert isinstance(exc_info.value.__cause__, PydanticInvalidForJsonSchema)


def test_nested_unrenderable_member_raises() -> None:
    """The failure of a nested member surfaces as the same clear error."""
    with pytest.raises(TypeError, match="NestsUnrenderable"):
        render_output_schema(NestsUnrenderable, _render)


def test_renderer_returning_no_document_raises() -> None:
    """A renderer yielding something other than a document fails as a TypeError."""
    with pytest.raises(TypeError, match="rather than a JSON Schema document"):
        render_output_schema(Renderable, lambda model: "{}")


def test_field_less_model_raises_naming_the_root_path() -> None:
    """A model with no fields renders to a schema that constrains nothing."""
    with pytest.raises(TypeError, match=r"path \$ has no properties"):
        render_output_schema(FieldLess, _render)


def test_field_less_model_raises_under_a_strict_renderer() -> None:
    """A closed object, which is what three of the render sites emit, still fires."""
    assert _render_strict(FieldLess)["additionalProperties"] is False

    with pytest.raises(TypeError, match=r"path \$ has no properties"):
        render_output_schema(FieldLess, _render_strict)


def test_field_less_model_forbidding_extra_fields_raises() -> None:
    """``extra="forbid"`` closes the object with ``False``; it is still empty."""
    assert _render(ForbidsExtra)["additionalProperties"] is False

    with pytest.raises(TypeError, match=r"path \$ has no properties"):
        render_output_schema(ForbidsExtra, _render)


def test_nested_field_less_model_raises_naming_the_nested_path() -> None:
    """The walker resolves ``$ref`` into ``$defs`` and reports the member path."""
    with pytest.raises(TypeError, match=r"path \$\.inner has no properties"):
        render_output_schema(NestsFieldLess, _render)


def test_field_less_model_in_a_list_raises() -> None:
    """A list member is descended through ``items``."""
    with pytest.raises(TypeError, match=r"path \$\.xs has no properties"):
        render_output_schema(ListsFieldLess, _render)


def test_field_less_model_in_a_tuple_raises() -> None:
    """A tuple member is descended through ``prefixItems``."""
    with pytest.raises(TypeError, match=r"path \$\.t has no properties"):
        render_output_schema(TuplesFieldLess, _render)


def test_field_less_model_as_a_map_value_raises() -> None:
    """A schema-valued ``additionalProperties`` is descended through."""
    with pytest.raises(TypeError, match=r"path \$\.m has no properties"):
        render_output_schema(MapsToFieldLess, _render)


@pytest.mark.parametrize("model", [AnyOfsFieldLess, OneOfsFieldLess, AllOfsFieldLess])
def test_field_less_model_under_a_branch_keyword_raises(
    model: type[BaseModel],
) -> None:
    """Every branch keyword is descended through, not just the one a union emits."""
    with pytest.raises(TypeError, match=r"path \$\.u has no properties"):
        render_output_schema(model, _render)


def test_map_member_is_accepted_under_a_normalizing_renderer() -> None:
    """A renderer that empties a map member must not turn it into a rejection."""
    # Fails if the emptiness check is ever moved back onto the renderer's output.
    assert render_output_schema(TypedMap, _render_anthropic_style) == (
        _render_anthropic_style(TypedMap)
    )


def test_return_value_is_the_renderer_output_not_the_model_schema() -> None:
    """Callers receive the wire format they asked for, not the model's own schema."""
    schema = render_output_schema(Renderable, _render_strict)

    assert schema == _render_strict(Renderable)
    assert schema != _render(Renderable)


def test_renderer_failure_raises_chained() -> None:
    """A renderer that fails is reported against the model, with the cause kept."""
    with pytest.raises(TypeError, match="Renderable") as exc_info:
        render_output_schema(Renderable, _render_failing)

    assert isinstance(exc_info.value.__cause__, ValueError)


def test_typed_map_member_is_accepted() -> None:
    """A ``dict[str, str]`` member omits ``properties``, which is not a defect."""
    assert render_output_schema(TypedMap, _render) == _render(TypedMap)


def test_free_form_map_member_is_accepted() -> None:
    """A ``dict[str, Any]`` member is free-form by intent, not unconstrained."""
    assert render_output_schema(FreeFormMap, _render) == _render(FreeFormMap)


def test_typed_extras_model_is_accepted() -> None:
    """An empty ``properties`` beside a schema-valued map still bounds every field."""
    assert _render(TypedExtras)["additionalProperties"] == {"type": "string"}

    assert render_output_schema(TypedExtras, _render) == _render(TypedExtras)


def test_empty_schema_valued_additional_properties_is_accepted() -> None:
    """``{}`` is a schema, and falsy in Python, so the test must be an identity."""
    assert _render(OpenExtras)["additionalProperties"] == {}

    assert render_output_schema(OpenExtras, _render) == _render(OpenExtras)


def test_object_member_without_properties_is_accepted() -> None:
    """An object that never declares ``properties`` is never the rejected shape."""
    assert "properties" not in _render(ObjectTypedMember)["properties"]["m"]

    assert render_output_schema(ObjectTypedMember, _render) == _render(
        ObjectTypedMember
    )


def test_model_allowing_extra_fields_is_accepted() -> None:
    """``extra="allow"`` renders empty properties beside a permissive map."""
    assert render_output_schema(ExtraAllowed, _render) == _render(ExtraAllowed)


def test_self_referential_model_is_accepted() -> None:
    """A model referencing itself terminates instead of recursing forever."""
    assert render_output_schema(Node, _render) == _render(Node)


def test_renderable_model_returns_the_rendered_schema() -> None:
    """A schema that constrains the response is returned as the renderer built it."""
    assert render_output_schema(Renderable, _render) == _render(Renderable)
