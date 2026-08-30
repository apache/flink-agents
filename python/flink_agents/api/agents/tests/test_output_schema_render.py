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
from typing import Any, Callable

import pytest
from pydantic import BaseModel
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


class Renderable(BaseModel):
    x: int


def test_unrenderable_member_raises_naming_the_model() -> None:
    """A member no JSON Schema can express is reported as a failure to render it.

    The wording separates that from a renderer refusing a model the model's own
    render accepts, which is a different failure carrying a different remedy.
    """
    with pytest.raises(
        TypeError, match="Unrenderable cannot be rendered as a JSON Schema"
    ) as exc_info:
        render_output_schema(Unrenderable, _render)

    assert "pass no output schema" in str(exc_info.value)
    assert isinstance(exc_info.value.__cause__, PydanticInvalidForJsonSchema)


def test_nested_unrenderable_member_raises() -> None:
    """The failure of a nested member surfaces as the same clear error."""
    with pytest.raises(
        TypeError, match="NestsUnrenderable cannot be rendered as a JSON Schema"
    ):
        render_output_schema(NestsUnrenderable, _render)


def test_renderer_returning_no_document_raises() -> None:
    """A renderer yielding something other than a document fails as a TypeError."""
    with pytest.raises(TypeError, match="rather than a JSON Schema document"):
        render_output_schema(Renderable, lambda model: "{}")


def test_field_less_model_is_returned_as_rendered() -> None:
    """A model declaring no fields renders and is returned as rendered.

    Whether such a document is usable belongs to whatever consumes it, so it is
    not refused here.
    """
    assert render_output_schema(FieldLess, _render) == _render(FieldLess)


def test_return_value_is_the_renderer_output_not_the_model_schema() -> None:
    """Callers receive the wire format they asked for, not the model's own schema."""
    schema = render_output_schema(Renderable, _render_strict)

    assert schema == _render_strict(Renderable)
    assert schema != _render(Renderable)


def test_renderer_failure_raises_chained() -> None:
    """A renderer that fails is reported against the model, with the cause kept.

    The wording separates that from a model no JSON Schema can express, which is a
    different failure carrying a different remedy.
    """
    with pytest.raises(
        TypeError, match="Renderable cannot be translated by the renderer in use"
    ) as exc_info:
        render_output_schema(Renderable, _render_failing)

    assert isinstance(exc_info.value.__cause__, ValueError)
