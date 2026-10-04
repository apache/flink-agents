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
"""Construction-time validation for the api-layer Function descriptors.

A descriptor identifies a cross-language target by its required identifiers, so
an empty identifier can never resolve. Rejecting it at construction makes every
declaration path fail fast -- ``action()``, ``add_action``, direct construction
and deserialization alike -- instead of deferring the failure to runtime.
"""
import pytest

from flink_agents.api.function import JavaFunction, PythonFunction


def test_python_function_accepts_valid_identifiers() -> None:
    fn = PythonFunction(module="pkg.mod", qualname="MyClass.method")
    assert fn.module == "pkg.mod"
    assert fn.qualname == "MyClass.method"


def test_python_function_rejects_empty_module() -> None:
    with pytest.raises(ValueError, match="module"):
        PythonFunction(module="", qualname="handle")


def test_python_function_rejects_empty_qualname() -> None:
    with pytest.raises(ValueError, match="qualname"):
        PythonFunction(module="pkg.mod", qualname="")


def test_java_function_accepts_valid_identifiers() -> None:
    fn = JavaFunction.for_action("com.example.Handlers", "handle")
    assert fn.qualname == "com.example.Handlers"
    assert fn.method_name == "handle"


def test_java_function_rejects_empty_qualname() -> None:
    with pytest.raises(ValueError, match="qualname"):
        JavaFunction(qualname="", method_name="handle", parameter_types=[])


def test_java_function_rejects_empty_method_name() -> None:
    with pytest.raises(ValueError, match="method_name"):
        JavaFunction(qualname="com.example.X", method_name="", parameter_types=[])


def test_java_function_for_action_rejects_empty_qualname() -> None:
    with pytest.raises(ValueError, match="qualname"):
        JavaFunction.for_action("", "handle")
