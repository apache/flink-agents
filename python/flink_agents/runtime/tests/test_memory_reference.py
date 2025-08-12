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
from flink_agents.api.memory_reference import MemoryRef
from flink_agents.runtime.local_memory_object import LocalMemoryObject


class MockRunnerContext:  # noqa D101
    def __init__(self, memory: LocalMemoryObject) -> None:
        """Mock RunnerContext for testing resolve() method."""
        self._memory = memory

    def get_short_term_memory(self) -> LocalMemoryObject:  # noqa D102
        return self._memory


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


def test_set_get_involved_ref() -> None:  # noqa: D103
    mem = create_memory()

    # Test cases: (path, value, expected_type_name)
    test_cases = [
        ("my_int", 1, "int"),
        ("my_float", 3.14, "float"),
        ("my_str", "hello", "str"),
        ("my_list", ["a", "b"], "list"),
        ("my_dict", {"x": 10}, "dict"),
        ("my_set", {1, 2, 3}, "set"),
        ("my_user", User("Alice", 30), "User"),
    ]

    for path, value, expected_type_name in test_cases:
        ref = mem.set(path, value)
        assert isinstance(ref, MemoryRef)
        assert ref.path == path
        assert ref.type_name == expected_type_name

        retrieved_value = mem.get(ref)
        assert retrieved_value == value


def test_memory_ref_create() -> None:  # noqa: D103
    path = "a.b.c"
    type_name = "str"
    ref = MemoryRef.create(path, type_name)

    assert isinstance(ref, MemoryRef)
    assert ref.path == path
    assert ref.type_name == type_name


def test_memory_ref_resolve() -> None:  # noqa: D103
    mem = create_memory()
    ctx = MockRunnerContext(mem)

    test_data = {
        "my_int": 1,
        "my_float": 3.14,
        "my_str": "hello",
        "my_list": ["a", "b"],
        "my_dict": {"x": 10},
        "my_set": {1, 2, 3},
        "my_user": User("Charlie", 50),
    }

    for path, value in test_data.items():
        ref = mem.set(path, value)
        resolved_value = ref.resolve(ctx)
        assert resolved_value == value


def test_get_with_ref_to_nested_object() -> None:  # noqa: D103
    mem = create_memory()
    obj = mem.new_object("a.b")
    obj.set("c", 10)

    ref = MemoryRef.create("a", "NestedObject")

    resolved_obj = mem.get(ref)
    assert isinstance(resolved_obj, LocalMemoryObject)
    assert resolved_obj.get("b.c") == 10


def test_get_with_non_existent_ref() -> None:  # noqa: D103
    mem = create_memory()

    non_existent_ref = MemoryRef.create("this.path.does.not.exist", "str")

    assert mem.get(non_existent_ref) is None


def test_ref_equality_and_hashing() -> None:  # noqa: D103
    ref1 = MemoryRef(path="a.b", type_name="t")
    ref2 = MemoryRef(path="a.b", type_name="t")
    ref3 = MemoryRef(path="a.c", type_name="t")
    ref4 = MemoryRef(path="a.b", type_name="u")

    assert ref1 == ref2
    assert ref1 != ref3
    assert ref1 != ref4

    assert hash(ref1) == hash(ref2)

    ref_set = {ref1, ref2, ref3, ref4}
    assert len(ref_set) == 3
    assert ref1 in ref_set
    assert ref3 in ref_set
    assert ref4 in ref_set
