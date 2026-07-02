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
import uuid
from enum import Enum
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel

from flink_agents.api.memory_object import MemoryType, validate_memory_value
from flink_agents.runtime.flink_memory_object import (
    FlinkMemoryObject,
    MemoryObjectError,
)


class _Model(BaseModel):
    name: str


class _StrEnum(str, Enum):
    A = "a"


class _Plain:
    pass


class _BytesSub(bytes):
    pass


def test_accepts_none_and_scalars() -> None:
    for value in (None, True, False, 0, 1, -3, 3.14, "", "hello", b"", b"hello"):
        validate_memory_value("p", value)


def test_accepts_nested_list_and_dict() -> None:
    validate_memory_value("p", [1, "a", [2, 3], {"k": [4, None]}])
    validate_memory_value("p", {"a": 1, "b": {"c": [True, "x"]}})
    validate_memory_value("p", [b"x", {"k": b"y"}])


def test_rejects_pydantic_model() -> None:
    with pytest.raises(TypeError, match="model_dump"):
        validate_memory_value("p", _Model(name="x"))


def test_rejects_uuid() -> None:
    with pytest.raises(TypeError, match=r"str\(value\)"):
        validate_memory_value("p", uuid.uuid4())


def test_rejects_tuple_set_frozenset() -> None:
    for value in ((1, 2), {1, 2}, frozenset({1, 2})):
        with pytest.raises(TypeError, match=r"list\(value\)"):
            validate_memory_value("p", value)


def test_rejects_bytearray_and_bytes_subclass() -> None:
    # Exact `bytes` is accepted, but bytearray and bytes-subclasses are PyObject-wrapped
    # by Pemja; the exact-type check must reject them.
    for value in (bytearray(b"x"), _BytesSub(b"x")):
        with pytest.raises(TypeError, match="not checkpoint-stable"):
            validate_memory_value("p", value)


def test_rejects_str_enum() -> None:
    # str-Enum passes isinstance(str) but is PyObject-wrapped by Pemja; the
    # exact-type check must reject it.
    with pytest.raises(TypeError, match="not checkpoint-stable"):
        validate_memory_value("p", _StrEnum.A)


def test_rejects_custom_class() -> None:
    with pytest.raises(TypeError, match="not checkpoint-stable"):
        validate_memory_value("p", _Plain())


def test_rejects_non_str_dict_key() -> None:
    with pytest.raises(TypeError, match="non-str key"):
        validate_memory_value("p", {1: "v"})


def test_rejects_nested_value_reports_breadcrumb() -> None:
    with pytest.raises(TypeError, match=r"\[2\]\['bad'\]"):
        validate_memory_value("p", [1, 2, {"bad": object()}])


def test_memory_object_value_suggests_new_object() -> None:
    inner = FlinkMemoryObject(MemoryType.SHORT_TERM, MagicMock())
    with pytest.raises(TypeError, match="new_object"):
        validate_memory_value("p", inner)


def test_flink_set_raises_raw_type_error() -> None:
    j_obj = MagicMock()
    mem = FlinkMemoryObject(MemoryType.SHORT_TERM, j_obj)
    with pytest.raises(TypeError) as exc_info:
        mem.set("p", uuid.uuid4())
    # Validation fires before the Java call, raising a raw TypeError.
    assert not isinstance(exc_info.value, MemoryObjectError)
    j_obj.set.assert_not_called()
