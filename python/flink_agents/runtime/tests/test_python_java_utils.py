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
import cloudpickle

from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.runtime.python_java_utils import python_event_to_string


def test_python_event_to_string_with_input_event() -> None:
    """Test converting InputEvent to string."""
    event = InputEvent(input="test data")
    event_bytes = cloudpickle.dumps(event)

    result = python_event_to_string(event_bytes)

    assert isinstance(result, str)
    assert "test data" in result
    assert "input=" in result


def test_python_event_to_string_with_output_event() -> None:
    """Test converting OutputEvent to string."""
    event = OutputEvent(output={"key": "value", "count": 42})
    event_bytes = cloudpickle.dumps(event)

    result = python_event_to_string(event_bytes)

    assert isinstance(result, str)
    assert "output=" in result
    assert "key" in result
    assert "value" in result


def test_python_event_to_string_with_complex_input() -> None:
    """Test converting InputEvent with complex nested data."""
    complex_data = {
        "user": {"name": "Alice", "age": 30},
        "items": [1, 2, 3],
        "active": True,
    }
    event = InputEvent(input=complex_data)
    event_bytes = cloudpickle.dumps(event)

    result = python_event_to_string(event_bytes)

    assert isinstance(result, str)
    assert "input=" in result


def test_python_event_to_string_with_none_input() -> None:
    """Test converting InputEvent with None input."""
    event = InputEvent(input=None)
    event_bytes = cloudpickle.dumps(event)

    result = python_event_to_string(event_bytes)

    assert isinstance(result, str)
    assert "input=None" in result


def test_python_event_to_string_returns_string_type() -> None:
    """Verify the return type is always string."""
    event = InputEvent(input=12345)
    event_bytes = cloudpickle.dumps(event)

    result = python_event_to_string(event_bytes)

    assert isinstance(result, str)
