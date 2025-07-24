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
from typing import Any, Dict, Tuple

import pytest

from flink_agents.api.event import Event, InputEvent, OutputEvent
from flink_agents.plan.function import Function, PythonFunction


def check_class(input_event: InputEvent, output_event: OutputEvent) -> None:  # noqa: D103
    pass


def test_function_signature_same_class() -> None:  # noqa: D103
    func = PythonFunction.from_callable(check_class)
    func.check_signature(InputEvent, OutputEvent)


def test_function_signature_subclass() -> None:  # noqa: D103
    func = PythonFunction.from_callable(check_class)
    func.check_signature(Event, Event)


def test_function_signature_mismatch_class() -> None:  # noqa: D103
    func = PythonFunction.from_callable(check_class)
    with pytest.raises(TypeError):
        func.check_signature(OutputEvent, InputEvent)


def test_function_signature_mismatch_args_num() -> None:  # noqa: D103
    func = PythonFunction.from_callable(check_class)
    with pytest.raises(TypeError):
        func.check_signature(InputEvent)


def check_primitive(value: int) -> None:  # noqa: D103
    pass


def test_function_signature_same_primitive() -> None:  # noqa: D103
    func = PythonFunction.from_callable(check_primitive)
    func.check_signature(int)


def test_function_signature_mismatch_primitive() -> None:  # noqa: D103
    func = PythonFunction.from_callable(check_primitive)
    with pytest.raises(TypeError):
        func.check_signature(float)


def check_mix(a: int, b: InputEvent) -> None:  # noqa: D103
    pass


def test_function_signature_match_mix() -> None:  # noqa: D103
    func = PythonFunction.from_callable(check_mix)
    func.check_signature(int, Event)


def test_function_signature_mismatch_mix() -> None:  # noqa: D103
    func = PythonFunction.from_callable(check_mix)
    with pytest.raises(TypeError):
        func.check_signature(Event, int)


def check_generic_type(*args: Tuple[Any, ...], **kwargs: Dict[str, Any]) -> None:  # noqa: D103
    pass


def test_function_signature_generic_type_same() -> None:  # noqa: D103
    func = PythonFunction.from_callable(check_generic_type)
    func.check_signature(Tuple[Any, ...], Dict[str, Any])


def test_function_signature_generic_type_match() -> None:  # noqa: D103
    func = PythonFunction.from_callable(check_generic_type)
    func.check_signature(tuple, dict)


def test_function_signature_generic_type_mismatch() -> None:  # noqa: D103
    func = PythonFunction.from_callable(check_generic_type)
    with pytest.raises(TypeError):
        func.check_signature(Tuple[str, ...], Dict[str, Any])


current_dir = Path(__file__).parent


@pytest.fixture(scope="module")
def func() -> Function:  # noqa: D103
    return PythonFunction.from_callable(check_class)


def test_python_function_serialize(func: Function) -> None:  # noqa: D103
    json_value = func.model_dump_json(serialize_as_any=True)
    with Path.open(Path(f"{current_dir}/resources/python_function.json")) as f:
        expected_json = f.read()
    actual = json.loads(json_value)
    expected = json.loads(expected_json)
    assert actual == expected


def test_python_function_deserialize(func: Function) -> None:  # noqa: D103
    with Path.open(Path(f"{current_dir}/resources/python_function.json")) as f:
        expected_json = f.read()
    deserialized_func = PythonFunction.model_validate_json(expected_json)
    assert deserialized_func == func
