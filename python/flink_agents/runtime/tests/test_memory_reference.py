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

    @property
    def short_term_memory(self) -> LocalMemoryObject:  # noqa D102
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

    # Test cases: (path, value, type_name)
    test_cases = [
        ("my_int", 1, "int"),
        ("my_float", 3.14, "float"),
        ("my_str", "hello", "str"),
        ("my_list", ["a", "b"], "list"),
        ("my_dict", {"x": 10}, "dict"),
        ("my_set", {1, 2, 3}, "set"),
        ("my_user", User("Alice", 30), "User"),
    ]

    for path, value, _expected_type_name in test_cases:
        ref = mem.set(path, value)
        assert isinstance(ref, MemoryRef)
        assert ref.path == path

        retrieved_value = mem.get(ref)
        assert retrieved_value == value


def test_memory_ref_create() -> None:  # noqa: D103
    path = "a.b.c"
    ref = MemoryRef.create(path)

    assert isinstance(ref, MemoryRef)
    assert ref.path == path


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

    ref = MemoryRef.create("a")

    resolved_obj = mem.get(ref)
    assert isinstance(resolved_obj, LocalMemoryObject)
    assert resolved_obj.get("b.c") == 10


def test_get_with_non_existent_ref() -> None:  # noqa: D103
    mem = create_memory()

    non_existent_ref = MemoryRef.create("this.path.does.not.exist")

    assert mem.get(non_existent_ref) is None


def test_ref_equality_and_hashing() -> None:  # noqa: D103
    ref1 = MemoryRef(path="a.b")
    ref2 = MemoryRef(path="a.b")
    ref3 = MemoryRef(path="a.c")

    assert ref1 == ref2
    assert ref1 != ref3

    assert hash(ref1) == hash(ref2)
    assert hash(ref1) != hash(ref3)

    ref_set = {ref1, ref2, ref3}
    assert len(ref_set) == 2
    assert ref1 in ref_set
    assert ref3 in ref_set
