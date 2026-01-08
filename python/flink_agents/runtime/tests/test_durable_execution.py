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
"""Tests for durable execution helper functions."""

import cloudpickle

from flink_agents.runtime.flink_runner_context import (
    _compute_args_digest,
    _compute_function_id,
)


def sample_function(x: int, y: int) -> int:
    """A sample function for testing."""
    return x + y


class SampleClass:
    """A sample class for testing method function IDs."""

    def instance_method(self, x: int) -> int:
        """An instance method."""
        return x * 2

    @staticmethod
    def static_method(x: int) -> int:
        """A static method."""
        return x * 3

    @classmethod
    def class_method(cls, x: int) -> int:
        """A class method."""
        return x * 4


def test_compute_function_id_for_function() -> None:
    """Test function ID computation for regular functions."""
    func_id = _compute_function_id(sample_function)
    assert "sample_function" in func_id
    assert "test_durable_execution" in func_id


def test_compute_function_id_for_lambda() -> None:
    """Test function ID computation for lambda functions."""
    lambda_func = lambda x: x + 1  # noqa: E731
    func_id = _compute_function_id(lambda_func)
    assert "<lambda>" in func_id


def test_compute_function_id_for_method() -> None:
    """Test function ID computation for instance methods."""
    obj = SampleClass()
    func_id = _compute_function_id(obj.instance_method)
    assert "instance_method" in func_id
    assert "SampleClass" in func_id


def test_compute_function_id_for_static_method() -> None:
    """Test function ID computation for static methods."""
    func_id = _compute_function_id(SampleClass.static_method)
    assert "static_method" in func_id


def test_compute_function_id_for_class_method() -> None:
    """Test function ID computation for class methods."""
    func_id = _compute_function_id(SampleClass.class_method)
    assert "class_method" in func_id


def test_compute_args_digest_basic() -> None:
    """Test args digest computation for basic types."""
    digest1 = _compute_args_digest((1, 2), {"key": "value"})
    digest2 = _compute_args_digest((1, 2), {"key": "value"})
    # Same arguments should produce same digest
    assert digest1 == digest2

    # Different arguments should produce different digest
    digest3 = _compute_args_digest((1, 3), {"key": "value"})
    assert digest1 != digest3


def test_compute_args_digest_empty() -> None:
    """Test args digest computation for empty arguments."""
    digest = _compute_args_digest((), {})
    assert len(digest) == 16  # SHA256 truncated to 16 chars


def test_compute_args_digest_complex_types() -> None:
    """Test args digest computation for complex types."""
    complex_args = (
        {"nested": {"key": [1, 2, 3]}},
        [1, 2, {"inner": "value"}],
    )
    complex_kwargs = {"data": {"x": 1, "y": 2}}

    digest1 = _compute_args_digest(complex_args, complex_kwargs)
    digest2 = _compute_args_digest(complex_args, complex_kwargs)
    assert digest1 == digest2


def test_compute_args_digest_order_matters() -> None:
    """Test that argument order affects the digest."""
    digest1 = _compute_args_digest((1, 2), {})
    digest2 = _compute_args_digest((2, 1), {})
    assert digest1 != digest2


def test_compute_args_digest_kwargs_vs_args() -> None:
    """Test that kwargs and args produce different digests."""
    digest1 = _compute_args_digest((1,), {"y": 2})
    digest2 = _compute_args_digest((1, 2), {})
    assert digest1 != digest2


def test_cloudpickle_serialization() -> None:
    """Test that results can be serialized and deserialized with cloudpickle."""
    # Test basic types
    original = {"key": "value", "number": 42, "list": [1, 2, 3]}
    serialized = cloudpickle.dumps(original)
    deserialized = cloudpickle.loads(serialized)
    assert deserialized == original

    # Test exception
    def raise_test_error() -> None:
        error_message = "test error"
        raise ValueError(error_message)

    try:
        raise_test_error()
    except ValueError as e:
        serialized_exc = cloudpickle.dumps(e)
        deserialized_exc = cloudpickle.loads(serialized_exc)
        assert str(deserialized_exc) == "test error"
        assert isinstance(deserialized_exc, ValueError)

