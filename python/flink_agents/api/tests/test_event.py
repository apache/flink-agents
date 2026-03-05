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
from typing import Any, Type

import pytest
from pydantic import ValidationError
from pydantic_core import PydanticSerializationError
from pyflink.common import Row

from flink_agents.api.events.event import Event, InputEvent, OutputEvent


def test_event_init_serializable() -> None:  # noqa D103
    Event(a=1, b=InputEvent(input=1), c=OutputEvent(output="111"))


def test_event_init_non_serializable() -> None:  # noqa D103
    with pytest.raises(ValidationError):
        Event(a=1, b=Type[InputEvent])


def test_event_setattr_serializable() -> None:  # noqa D103
    event = Event(a=1)
    event.c = Event()


def test_event_setattr_non_serializable() -> None:  # noqa D103
    event = Event(a=1)
    with pytest.raises(PydanticSerializationError):
        event.c = Type[InputEvent]


def test_input_event_ignore_row_unserializable_basic() -> None:  # noqa D103
    InputEvent(input=Row({"a": 1}))


def test_event_row_with_non_serializable_fails() -> None:  # noqa D103
    with pytest.raises(ValidationError):
        Event(row_field=Row({"a": 1}), non_serializable_field=Type[InputEvent])


def test_event_multiple_rows_serializable() -> None:  # noqa D103
    Event(row1=Row({"a": 1}), row2=Row({"b": 2}), normal_field="test")


def test_event_setattr_row_serializable() -> None:  # noqa D103
    event = Event(a=1)
    event.row_field = Row({"key": "value"})


def test_event_json_serialization_with_row() -> None:  # noqa D103
    event = InputEvent(input=Row({"test": "data"}))
    json_str = event.model_dump_json()
    assert "test" in json_str
    assert "Row" in json_str


def test_efficient_row_serialization_with_fallback() -> None:
    """Test that the new fallback-based serialization works efficiently."""
    row_data = {"a": 1, "b": "test", "c": [1, 2, 3]}
    event = InputEvent(input=Row(row_data))

    json_str = event.model_dump_json()
    import json

    parsed = json.loads(json_str)

    assert parsed["input"]["type"] == "Row"
    assert parsed["input"]["values"] == [row_data]
    assert "id" in parsed  # UUID should be present

    def custom_fallback(obj: Any) -> dict[str, Any]:
        if isinstance(obj, Row):
            return {"custom_type": "CustomRow", "data": obj._values}
        msg = "Unknown type"
        raise ValueError(msg)

    custom_json = event.model_dump_json(fallback=custom_fallback)
    custom_parsed = json.loads(custom_json)

    assert custom_parsed["input"]["custom_type"] == "CustomRow"
    assert custom_parsed["input"]["data"] == [row_data]


def test_event_with_mixed_serializable_types() -> None:
    """Test event with mix of normal and Row types."""
    event = InputEvent(
        input={
            "normal_data": {"key": "value"},
            "row_data": Row({"test": "data"}),
            "list_data": [1, 2, 3],
            "nested_row": {"inner": Row({"nested": True})},
        }
    )

    json_str = event.model_dump_json()

    import json

    parsed = json.loads(json_str)

    # Normal data should be serialized normally
    assert parsed["input"]["normal_data"]["key"] == "value"
    assert parsed["input"]["list_data"] == [1, 2, 3]

    # Row data should use fallback serializer
    assert parsed["input"]["row_data"]["type"] == "Row"
    assert parsed["input"]["nested_row"]["inner"]["type"] == "Row"


def test_input_event_ignore_row_unserializable_duplicate() -> None:  # noqa D103
    InputEvent(input=Row({"a": 1}))


# ── Unified Event tests ──────────────────────────────────────────────────


def test_unified_event_creation() -> None:
    """Test creating a unified event with type and attributes."""
    event = Event(type="MyEvent", attributes={"field1": "test", "field2": 42})
    assert event.type == "MyEvent"
    assert event.attributes == {"field1": "test", "field2": 42}
    assert event.get_type() == "MyEvent"


def test_unified_event_get_type_falls_back_to_class_name() -> None:
    """Test that get_type() falls back to FQN class name for subclasses."""
    event = InputEvent(input="hello")
    assert event.type is None
    assert event.get_type() == (
        f"{InputEvent.__module__}.{InputEvent.__qualname__}"
    )


def test_unified_event_base_get_type_no_type_set() -> None:
    """Test that get_type() returns FQN class name for base Event without type."""
    event = Event(a=1)
    assert event.type is None
    assert event.get_type() == f"{Event.__module__}.{Event.__qualname__}"


def test_unified_event_get_attr_set_attr() -> None:
    """Test get_attr and set_attr convenience methods."""
    event = Event(type="TestEvent")
    event.set_attr("key", "value")
    assert event.get_attr("key") == "value"
    assert event.get_attr("missing") is None


def test_unified_event_from_json() -> None:
    """Test deserializing a unified event from JSON."""
    import json

    data = {"type": "MyEvent", "attributes": {"x": 1}}
    event = Event.from_json(json.dumps(data))
    assert event.type == "MyEvent"
    assert event.attributes == {"x": 1}


def test_unified_event_from_json_missing_type() -> None:
    """Test that from_json raises ValueError when type is missing."""
    import json

    with pytest.raises(ValueError, match="type"):
        Event.from_json(json.dumps({"attributes": {}}))


def test_unified_event_serialization_roundtrip() -> None:
    """Test that unified events survive JSON serialization/deserialization."""
    import json

    original = Event(type="RoundTrip", attributes={"a": 1, "b": "two"})
    json_str = original.model_dump_json()
    parsed = json.loads(json_str)
    assert parsed["type"] == "RoundTrip"
    assert parsed["attributes"] == {"a": 1, "b": "two"}
    restored = Event.model_validate(parsed)
    assert restored.type == "RoundTrip"
    assert restored.attributes == {"a": 1, "b": "two"}
