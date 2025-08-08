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


def test_input_event_ignore_row_unserializable() -> None:  # noqa D103
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
    event = InputEvent(input={
        "normal_data": {"key": "value"},
        "row_data": Row({"test": "data"}),
        "list_data": [1, 2, 3],
        "nested_row": {"inner": Row({"nested": True})}
    })

    json_str = event.model_dump_json()

    import json
    parsed = json.loads(json_str)

    # Normal data should be serialized normally
    assert parsed["input"]["normal_data"]["key"] == "value"
    assert parsed["input"]["list_data"] == [1, 2, 3]

    # Row data should use fallback serializer
    assert parsed["input"]["row_data"]["type"] == "Row"
    assert parsed["input"]["nested_row"]["inner"]["type"] == "Row"


def test_input_event_ignore_row_unserializable() -> None:  # noqa D103
    InputEvent(input=Row({"a": 1}))

