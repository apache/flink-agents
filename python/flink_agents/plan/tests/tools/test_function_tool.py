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

import pytest

from flink_agents.plan.tools.function_tool import FunctionTool, from_callable

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
def func_tool() -> FunctionTool:  # noqa: D103
    return from_callable("foo", foo)


def test_serialize_function_tool(func_tool: FunctionTool) -> None:  # noqa: D103
    json_value = func_tool.model_dump_json(serialize_as_any=True, indent=4)
    with Path(f"{current_dir}/resources/function_tool.json").open() as f:
        expected_json = f.read()
    actual = json.loads(json_value)
    expected = json.loads(expected_json)
    assert actual == expected


def test_deserialize_function_tool(func_tool: FunctionTool) -> None:  # noqa: D103
    with Path(f"{current_dir}/resources/function_tool.json").open() as f:
        json_value = f.read()
    actual_func_tool = FunctionTool.model_validate_json(json_value)
    assert actual_func_tool == func_tool
