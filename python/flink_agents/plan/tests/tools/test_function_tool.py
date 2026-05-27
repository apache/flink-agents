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
import json
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from flink_agents.plan.function import JavaFunction, PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool

current_dir = Path(__file__).parent


def foo(bar: int, baz: str) -> str:
    """Function for testing ToolMetadata.

    Parameters
    ----------
    bar : int
        The bar value.
    baz : str
        The baz value.

    Returns:
    -------
    str
        Response string value.
    """
    raise NotImplementedError


@pytest.fixture(scope="module")
def func_tool() -> FunctionTool:
    return FunctionTool(func=PythonFunction.from_callable(foo))


def test_serialize_function_tool(func_tool: FunctionTool) -> None:
    json_value = func_tool.model_dump_json(serialize_as_any=True, indent=4)
    with Path(f"{current_dir}/resources/function_tool.json").open() as f:
        expected_json = f.read()
    actual = json.loads(json_value)
    expected = json.loads(expected_json)
    assert actual == expected


def test_deserialize_function_tool(func_tool: FunctionTool) -> None:
    with Path(f"{current_dir}/resources/function_tool.json").open() as f:
        json_value = f.read()
    actual_func_tool = FunctionTool.model_validate_json(json_value)
    # ``PythonFunction`` carries a private ``__func`` cache that is only
    # populated once the callable has been resolved (e.g. via the eager
    # metadata derivation in the fixture). The deserialized instance hasn't
    # resolved the callable yet, so a full BaseModel ``==`` would differ on
    # the cache. Compare the public, serialized state instead.
    assert actual_func_tool.metadata == func_tool.metadata
    assert actual_func_tool.func.module == func_tool.func.module
    assert actual_func_tool.func.qualname == func_tool.func.qualname


def test_python_function_tool_metadata_filled_eagerly() -> None:
    # ``PythonFunction`` metadata can be derived without external context,
    # so ``FunctionTool`` fills it during model validation. The field is
    # therefore already populated immediately after construction.
    tool = FunctionTool(func=PythonFunction.from_callable(foo))
    assert tool.metadata is not None
    assert tool.metadata.name == "foo"


# ---- Java function tool path -------------------------------------------------


def _java_func() -> JavaFunction:
    # Fresh instance per test — the adapter now lives on JavaFunction, so
    # sharing one would leak state between tests.
    return JavaFunction(
        qualname="com.example.Tools",
        method_name="add",
        parameter_types=["int", "int"],
    )

_FAKE_JAVA_SCHEMA = json.dumps(
    {
        "type": "object",
        "properties": {
            "a": {"type": "integer", "description": "First operand."},
            "b": {"type": "integer", "description": "Second operand."},
        },
        "required": ["a", "b"],
        "title": "add",
    }
)


def _fake_adapter() -> MagicMock:
    """Build a mock ``_j_resource_adapter`` that mirrors the Java
    ``JavaResourceAdapter`` surface used by ``plan.FunctionTool``.

    ``getJavaToolMetadata`` returns a flat ``Map<String, String>`` (see
    ``JavaResourceAdapter.getJavaToolMetadata`` Java side for why),
    so mock it as a plain Python dict.
    """
    adapter = MagicMock()
    adapter.getJavaToolMetadata.return_value = {
        "name": "add",
        "description": "Add two ints.",
        "inputSchema": _FAKE_JAVA_SCHEMA,
    }
    adapter.invokeJavaTool.return_value = 1065
    return adapter


def test_java_function_tool_constructs_without_adapter() -> None:
    # Plan compile time: no JVM adapter yet. Construction (and its
    # SerializableResource self-validation) must not call into the adapter.
    # ``metadata`` stays ``None`` until the adapter is injected.
    tool = FunctionTool(func=_java_func())
    assert tool.func._j_resource_adapter is None
    assert isinstance(tool.func, JavaFunction)
    assert tool.metadata is None


def test_java_function_tool_metadata_filled_on_adapter_injection() -> None:
    tool = FunctionTool(func=_java_func())
    adapter = _fake_adapter()

    tool.set_java_resource_adapter(adapter)

    # Adapter is consulted exactly once at injection time and the result is
    # stored in the regular ``metadata`` field. Subsequent accesses just read
    # the field, so the adapter is not hit again.
    adapter.getJavaToolMetadata.assert_called_once_with(
        "com.example.Tools", "add", ["int", "int"]
    )
    assert tool.metadata is not None
    assert tool.metadata.name == "add"
    assert tool.metadata.description == "Add two ints."
    assert set(tool.metadata.args_schema.model_fields) == {"a", "b"}
    _ = tool.metadata
    adapter.getJavaToolMetadata.assert_called_once()


def test_java_function_tool_metadata_is_none_without_adapter() -> None:
    # Before the runtime injects the adapter, the metadata is intentionally
    # absent — this is the only legal window where ``Tool.metadata`` is
    # ``None``. Accessing the field must not raise.
    tool = FunctionTool(func=_java_func())
    assert tool.metadata is None


def test_java_function_tool_call_dispatches_through_adapter() -> None:
    tool = FunctionTool(func=_java_func())
    adapter = _fake_adapter()
    tool.set_java_resource_adapter(adapter)

    result = tool.call(a=377, b=688)

    assert result == 1065
    adapter.invokeJavaTool.assert_called_once_with(
        "com.example.Tools",
        "add",
        ["int", "int"],
        {"a": 377, "b": 688},
    )


def test_java_function_tool_call_without_adapter_raises() -> None:
    tool = FunctionTool(func=_java_func())
    with pytest.raises(RuntimeError, match="JVM resource adapter"):
        tool.call(a=1, b=2)
