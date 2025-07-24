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
from typing import Dict, List, Set

from flink_agents.runtime.local_memory_object import LocalMemoryObject


def create_memory() -> LocalMemoryObject:
    """Return a MemoryObject for every test case."""
    return LocalMemoryObject({})


class User:  # noqa: D101
    def __init__(self, name: str, age: int) -> None:
        """Store for later comparison."""
        self.name = name
        self.age = age

    def __eq__(self, other: object) -> bool:
        return (
            isinstance(other, User)
            and other.name == self.name
            and other.age == self.age
        )


def test_basic_set_get_various_types() -> None:  # noqa: D103
    mem = create_memory()

    # int / float / str
    mem.set("int", 1)
    assert mem.get_field_names() == ["int"]
    assert mem.get_fields()["int"] == 1
    mem.set("float", 3.14)
    mem.set("str", "hello")
    assert mem.get("int") == 1
    assert mem.get("float") == 3.14
    assert mem.get("str") == "hello"

    # list
    lst: List[str] = ["a", "b"]
    mem.set("list", lst)
    assert mem.get("list") == lst

    # dict
    d: Dict[str, int] = {"x": 10}
    mem.set("dict", d)
    assert mem.get("dict") == d

    # set
    s: Set[int] = {1, 2, 3}
    mem.set("set", s)
    assert mem.get("set") == s

    # custom object
    user = User("Alice", 20)
    mem.set("user", user)
    assert mem.get("user") == user


def test_nested_set_and_get() -> None:  # noqa: D103
    mem = create_memory()
    mem.set("a.b.c", True)
    tmp_obj = mem.get("a.b")
    tmp_obj.set("tmp", 10)
    assert mem.get("a.b.tmp") == 10
    assert "tmp" in mem.get("a.b").get_field_names()
    assert mem.get("a").get("b").get("c") is True
    assert "b" in mem.get("a").get_field_names()
    assert mem.get("a").get_fields()["b"] == "NestedObject"
    assert mem.get("a.b.c") is True
    assert mem.get("a.b").get("c") is True


def test_new_object_and_is_exist() -> None:  # noqa: D103
    mem = create_memory()
    mem.new_object("foo.bar")
    assert mem.is_exist("foo")
    assert mem.is_exist("foo.bar")

    fields = mem.get("foo").get_fields()
    assert fields["bar"] == "NestedObject"


def test_overwrite_behavior() -> None:  # noqa: D103
    mem = create_memory()
    mem.set("profile", "active")

    try:
        mem.new_object("profile")
        msg = "Should raise when creating object on primitive"
        raise AssertionError(msg)
    except ValueError:
        pass

    mem.new_object("profile", overwrite=True).set("status", "ok")
    assert mem.get("profile.status") == "ok"


def test_auto_parent_fill_and_children() -> None:  # noqa: D103
    mem = create_memory()
    mem.new_object("x.y.z")

    assert mem.is_exist("x")
    assert mem.is_exist("x.y")
    assert mem.is_exist("x.y.z")

    root_fields = mem.get_fields()
    assert root_fields["x"] == "NestedObject"


def test_disallow_overwrite_object_with_primitive() -> None:  # noqa: D103
    mem = create_memory()
    mem.new_object("obj")
    try:
        mem.set("obj", 123)
        msg = "Should raise when overwriting object with primitive"
        raise AssertionError(msg)
    except ValueError:
        pass
