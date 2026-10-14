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
from pathlib import Path
from typing import Annotated, Any

import pytest
from pydantic import Field

from flink_agents.api.tools.tool import ToolMetadata
from flink_agents.api.tools.tool_parameter_injection import InjectedArg
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_schema import FunctionSchema
from flink_agents.plan.tools.function_tool import FunctionTool
from flink_agents.runtime.python_java_utils import (
    get_python_tool_metadata,
    invoke_python_tool,
)
from flink_agents.runtime.tests import function_schema_bridge as bridge_resources
from flink_agents.runtime.tests.function_schema_bridge import contract_tool

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
    field = FunctionSchema(_sample_function).model.model_fields["a"]
    assert field.annotation is int
    assert field.is_required()
    assert field.description == "the a param"


def test_schema_from_function_default_makes_param_optional() -> None:
    field = FunctionSchema(_sample_function).model.model_fields["b"]
    assert field.annotation is str
    assert not field.is_required()
    assert field.default == "x"
    assert field.description == "the b param"


def test_schema_from_function_unannotated_param_is_any() -> None:
    field = FunctionSchema(_sample_function).model.model_fields["c"]
    # No annotation falls back to Any, and the missing docstring entry to a
    # placeholder description.
    assert field.annotation is Any
    assert field.default == 5
    assert field.description == "Parameter: c"


def test_schema_from_function_annotated_metadata_is_description() -> None:
    field = FunctionSchema(_sample_function).model.model_fields["d"]
    # Annotated[int, "annotated desc"] keeps the int type and uses the metadata
    # string as the field description.
    assert field.annotation is int
    assert field.description == "annotated desc"
    assert field.default == 0


CASES = json.loads(
    (
        Path(__file__).resolve().parents[5] / "e2e-test/function-schema-cases.json"
    ).read_text()
)


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["name"])
@pytest.mark.parametrize("bridge", [False, True])
def test_function_contract(case: dict, bridge: bool) -> None:
    bridge_resources.calls = 0
    arguments = {**case["arguments"], "tenant_id": "tenant"}
    tool = FunctionTool(
        func=PythonFunction.from_callable(contract_tool),
        injected_args={"tenant_id": InjectedArg.from_config("tenant")},
    )

    def invoke() -> Any:
        if bridge:
            return invoke_python_tool(
                contract_tool.__module__,
                "contract_tool",
                arguments,
            )["blocks"][0]["text"]
        return tool.call(**arguments)

    if "error" in case:
        with pytest.raises(ValueError) as error:
            invoke()
        assert str(error.value) == case["error"]
        assert bridge_resources.calls == 0
    else:
        assert invoke() == case["result"]
        assert bridge_resources.calls == 1


def test_metadata_generation_and_roundtrip_are_lossless() -> None:
    schema = FunctionSchema(contract_tool, ["tenant_id"]).metadata
    bridge = get_python_tool_metadata(
        contract_tool.__module__, "contract_tool", ["tenant_id"]
    )
    assert json.loads(bridge["inputSchema"]) == schema.args_schema
    restored = ToolMetadata.model_validate_json(schema.model_dump_json())
    assert restored.get_parameters_dict() == schema.args_schema
    assert restored.args_schema["additionalProperties"] is False
    assert restored.args_schema["properties"]["limit"]["minimum"] == 1
    assert "tenant_id" not in restored.args_schema["properties"]


def invalid_default(value: Annotated[int, Field(gt=0)] = -1) -> int:
    return value


def test_invalid_default_fails_at_compilation() -> None:
    with pytest.raises(ValueError, match="Invalid default"):
        FunctionSchema(invalid_default)


def constrained(value: Annotated[int, Field(gt=0)]) -> int:
    return value


def test_direct_call_uses_the_same_validation() -> None:
    tool = FunctionTool(func=PythonFunction.from_callable(constrained))
    with pytest.raises(ValueError, match="exclusiveMinimum"):
        tool.call(value=0)
    assert tool.call(value=1) == 1


@pytest.mark.parametrize("arguments", [None, [], "{}"])
def test_rejects_non_object_arguments(arguments: Any) -> None:
    with pytest.raises(ValueError, match="INVALID_ARGUMENT /: type"):
        FunctionSchema(constrained).bind(arguments)


def json_object(value: dict[str, Any]) -> dict:
    return value


def test_rejects_non_json_keys_and_non_finite_numbers() -> None:
    schema = FunctionSchema(json_object)
    for value in [{1: "value"}, {"value": float("nan")}, {"value": float("inf")}]:
        with pytest.raises(ValueError, match="non-JSON"):
            schema.bind({"value": value})


def default_object(value: dict[str, Any] = {"properties": {}}) -> dict:  # noqa: B006 - test JSON defaults
    return value


def test_schema_annotations_do_not_mutate_default_payloads() -> None:
    schema = FunctionSchema(default_object)
    assert schema.bind({}) == {"value": {"properties": {}}}
    assert schema.metadata.args_schema["properties"]["value"]["default"] == {
        "properties": {}
    }


@pytest.mark.parametrize("injection", [{}, {"tenant_id": None}])
def test_call_validates_declared_injection(injection: dict) -> None:
    bridge_resources.calls = 0
    tool = FunctionTool(
        func=PythonFunction.from_callable(contract_tool),
        injected_args={"tenant_id": InjectedArg.from_config("tenant")},
    )
    with pytest.raises(ValueError, match="INVALID_ARGUMENT"):
        tool.call(query="flink", options={"name": "alice"}, **injection)
    assert bridge_resources.calls == 0


def constrained_argument(value: Annotated[int, Field(ge=1)] = 2) -> int:
    return value


def test_hidden_parameters_keep_the_complete_argument_contract() -> None:
    visible = FunctionSchema(constrained_argument)
    hidden = FunctionSchema(constrained_argument, ["value"])
    assert "value" in visible.metadata.args_schema["properties"]
    assert "value" not in hidden.metadata.args_schema["properties"]
    for schema in (visible, hidden):
        assert schema.bind({}) == {"value": 2}
        assert schema.bind({"value": 3}) == {"value": 3}
        with pytest.raises(ValueError, match="INVALID_ARGUMENT /value: minimum"):
            schema.bind({"value": 0})
        with pytest.raises(ValueError, match="INVALID_ARGUMENT /value: type"):
            schema.bind({"value": "3"})
