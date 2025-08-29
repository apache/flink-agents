from __future__ import annotations

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
from typing import TYPE_CHECKING, Any, Callable, Dict, Generator, Tuple

import pytest

from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.plan.function import (
    PythonFunction,
    PythonGeneratorWrapper,
    _is_function_cacheable,
    call_python_function,
    clear_python_function_cache,
    get_python_function_cache_keys,
    get_python_function_cache_size,
)

if TYPE_CHECKING:
    from flink_agents.plan.function import Function


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


# Helper function for caching tests
def function_for_caching(value: int) -> int:
    """Test function that returns the input value doubled."""
    return value * 2


# Functions to test selective caching
def generator_function(n: int) -> Generator[int, None, None]:
    """Generator function - should not be cached."""
    yield from range(n)


def function_with_mutable_default(items: list[int] | None = None) -> list[int]:
    """Function with mutable default - should not be cached."""
    if items is None:
        items = []
    items.append(1)
    return items


def make_closure(multiplier: int) -> Callable[[int], int]:
    """Creates a closure - the returned function should not be cached."""

    def inner(value: int) -> int:
        return value * multiplier

    return inner


def simple_pure_function(x: int, y: int) -> int:
    """Simple pure function - should be cached."""
    return x + y


def test_call_python_function_basic() -> None:
    """Test basic functionality of call_python_function."""
    # Clear cache before testing
    clear_python_function_cache()

    result = call_python_function(
        "flink_agents.plan.tests.test_function", "function_for_caching", (5,)
    )
    assert result == 10


def test_call_python_function_caching() -> None:
    """Test that call_python_function reuses cached instances."""
    # Clear cache before testing
    clear_python_function_cache()

    assert get_python_function_cache_size() == 0

    result1 = call_python_function(
        "flink_agents.plan.tests.test_function", "function_for_caching", (3,)
    )
    assert result1 == 6
    assert get_python_function_cache_size() == 1

    result2 = call_python_function(
        "flink_agents.plan.tests.test_function", "function_for_caching", (4,)
    )
    assert result2 == 8
    assert get_python_function_cache_size() == 1  # Cache size should remain 1

    result3 = call_python_function(
        "flink_agents.plan.tests.test_function",
        "check_class",
        (InputEvent(input="test"), OutputEvent(output="test")),
    )
    assert result3 is None
    assert get_python_function_cache_size() == 2


def test_call_python_function_different_modules() -> None:
    """Test caching with different modules."""
    clear_python_function_cache()

    call_python_function(
        "flink_agents.plan.tests.test_function", "function_for_caching", (1,)
    )
    call_python_function(
        "flink_agents.plan.tests.test_function",
        "check_class",
        (InputEvent(input="test"), OutputEvent(output="test")),
    )

    assert get_python_function_cache_size() == 2

    cache_keys = get_python_function_cache_keys()
    expected_keys = [
        ("flink_agents.plan.tests.test_function", "function_for_caching"),
        ("flink_agents.plan.tests.test_function", "check_class"),
    ]
    assert sorted(cache_keys) == sorted(expected_keys)


def test_clear_python_function_cache() -> None:
    """Test clearing the cache."""
    clear_python_function_cache()

    call_python_function(
        "flink_agents.plan.tests.test_function", "function_for_caching", (1,)
    )
    call_python_function(
        "flink_agents.plan.tests.test_function",
        "check_class",
        (InputEvent(input="test"), OutputEvent(output="test")),
    )

    assert get_python_function_cache_size() == 2
    clear_python_function_cache()
    assert get_python_function_cache_size() == 0
    assert get_python_function_cache_keys() == []


def test_cache_performance_benefit() -> None:
    """Test that caching provides performance benefits."""
    clear_python_function_cache()

    # This test verifies that the same PythonFunction instance is reused
    # We can't easily test performance directly, but we can verify that
    # the cache key mechanism works correctly

    # First call creates cache entry
    call_python_function(
        "flink_agents.plan.tests.test_function", "function_for_caching", (1,)
    )
    first_cache_size = get_python_function_cache_size()

    # Multiple subsequent calls should not increase cache size
    for i in range(10):
        call_python_function(
            "flink_agents.plan.tests.test_function", "function_for_caching", (i,)
        )

    assert get_python_function_cache_size() == first_cache_size

    for i in range(5):
        result = call_python_function(
            "flink_agents.plan.tests.test_function", "function_for_caching", (i,)
        )
        assert result == i * 2


def test_selective_caching_pure_functions() -> None:
    """Test that pure functions are cached."""
    clear_python_function_cache()

    # Pure functions should be cached
    call_python_function(
        "flink_agents.plan.tests.test_function", "simple_pure_function", (1, 2)
    )
    call_python_function(
        "flink_agents.plan.tests.test_function", "function_for_caching", (5,)
    )

    # Both should be in cache
    assert get_python_function_cache_size() == 2
    cache_keys = get_python_function_cache_keys()
    assert (
        "flink_agents.plan.tests.test_function",
        "simple_pure_function",
    ) in cache_keys
    assert (
        "flink_agents.plan.tests.test_function",
        "function_for_caching",
    ) in cache_keys


def test_selective_caching_generator_functions() -> None:
    """Test that generator functions are not cached."""
    clear_python_function_cache()

    # Generator function should not be cached
    result = call_python_function(
        "flink_agents.plan.tests.test_function", "generator_function", (3,)
    )
    assert isinstance(result, PythonGeneratorWrapper)
    # Convert generator to list for testing
    result_list = list(result.generator)
    assert result_list == [0, 1, 2]

    # Should not be cached
    assert get_python_function_cache_size() == 0


def test_selective_caching_mutable_defaults() -> None:
    """Test that functions with mutable defaults are not cached."""
    clear_python_function_cache()

    # Function with mutable default should not be cached
    result1 = call_python_function(
        "flink_agents.plan.tests.test_function", "function_with_mutable_default", ()
    )
    result2 = call_python_function(
        "flink_agents.plan.tests.test_function", "function_with_mutable_default", ()
    )

    # Should not be cached (each call creates a new function instance)
    assert get_python_function_cache_size() == 0

    # Results should be different if function is correctly not cached
    # (mutable default behavior depends on not caching)
    assert isinstance(result1, list)
    assert isinstance(result2, list)


def test_is_function_cacheable() -> None:
    """Test the _is_function_cacheable function directly."""
    # Pure functions should be cacheable
    assert _is_function_cacheable(simple_pure_function) is True
    assert _is_function_cacheable(function_for_caching) is True

    # Generator functions should not be cacheable
    assert _is_function_cacheable(generator_function) is False

    # Functions with mutable defaults should not be cacheable
    assert _is_function_cacheable(function_with_mutable_default) is False

    # Closures should not be cacheable
    closure_func = make_closure(5)
    assert _is_function_cacheable(closure_func) is False

    # None should not be cacheable
    assert _is_function_cacheable(None) is False


def test_python_function_cacheability_optimization() -> None:
    """Test that PythonFunction caches the cacheability check result."""
    # Test cacheable function
    cacheable_func = PythonFunction.from_callable(simple_pure_function)

    # First call should compute and cache the result
    assert cacheable_func.is_cacheable() is True

    # Second call should use cached result (we can't directly test this,
    # but we can verify it returns the same result)
    assert cacheable_func.is_cacheable() is True

    # Test non-cacheable function
    non_cacheable_func = PythonFunction.from_callable(generator_function)

    # First call should compute and cache the result
    assert non_cacheable_func.is_cacheable() is False

    # Second call should use cached result
    assert non_cacheable_func.is_cacheable() is False

    # Test that the cacheability check is consistent with direct _is_function_cacheable
    assert cacheable_func.is_cacheable() == _is_function_cacheable(simple_pure_function)
    assert non_cacheable_func.is_cacheable() == _is_function_cacheable(
        generator_function
    )
