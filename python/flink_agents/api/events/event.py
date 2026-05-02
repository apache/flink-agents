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
import hashlib
import json
from typing import Any, ClassVar, Dict

try:
    from typing import override
except ImportError:
    from typing_extensions import override
from uuid import UUID

from pydantic import BaseModel, Field, model_validator
from pydantic_core import PydanticSerializationError
from pyflink.common import Row


def _reconstruct_row_if_needed(data: Any) -> Any:
    """Recursively reconstruct pyflink Row objects from their JSON-serialized dicts.

    Row objects are serialized as ``{"type": "Row", "values": [...], "fields": [...]}``.
    This helper walks dicts and lists to convert any such representation back
    into a ``pyflink.common.Row``.
    """
    if isinstance(data, dict):
        if data.get("type") == "Row" and "values" in data:
            fields = data.get("fields")
            values = data["values"]
            if fields:
                return Row(**dict(zip(fields, values, strict=False)))
            return Row(*values)
        return {k: _reconstruct_row_if_needed(v) for k, v in data.items()}
    if isinstance(data, list):
        return [_reconstruct_row_if_needed(item) for item in data]
    return data


class Event(BaseModel, extra="allow"):
    """Base class for all event types in the system.

    This class serves dual purposes:

    - **Unified events**: Instantiated directly with a user-defined ``type``
      string and arbitrary key-value ``attributes``.  No subclassing required.
    - **Subclassed events**: Concrete subclasses (e.g., :class:`InputEvent`)
      set a fixed ``type`` string and store data in ``attributes``.

    Event allows extra properties, but these must be BaseModel instances or JSON
    serializable.

    Attributes:
    ----------
    id : UUID
        Unique identifier for the event, generated deterministically based on
        event content.
    type : str | None
        Event type string used for routing.
    attributes : Dict[str, Any]
        Key-value properties for the event data.
    """

    id: UUID = Field(default=None)
    type: str | None = Field(default=None)
    attributes: Dict[str, Any] = Field(default_factory=dict)

    @staticmethod
    def __serialize_unknown(field: Any) -> Dict[str, Any]:
        """Handle serialization of unknown types, specifically Row objects."""
        if isinstance(field, Row):
            result: Dict[str, Any] = {"type": "Row", "values": field._values}
            if hasattr(field, "_fields") and field._fields:
                result["fields"] = list(field._fields)
            return result
        else:
            err_msg = f"Unable to serialize unknown type: {field.__class__}"
            raise PydanticSerializationError(err_msg)

    @override
    def model_dump_json(self, **kwargs: Any) -> str:
        """Override model_dump_json to handle Row objects using fallback."""
        # Set fallback if not provided in kwargs
        if "fallback" not in kwargs:
            kwargs["fallback"] = self.__serialize_unknown
        return super().model_dump_json(**kwargs)

    def _generate_content_based_id(self) -> UUID:
        """Generate a deterministic UUID based on event content using MD5 hash.

        Similar to Java's UUID.nameUUIDFromBytes(), uses MD5 for version 3 UUID.
        """
        # Serialize content excluding 'id' to avoid circular dependency
        content_json = super().model_dump_json(
            exclude={"id"}, fallback=self.__serialize_unknown
        )
        md5_hash = hashlib.md5(content_json.encode()).digest()
        return UUID(bytes=md5_hash, version=3)

    @model_validator(mode="after")
    def validate_and_set_id(self) -> "Event":
        """Validate that fields are serializable and generate content-based ID."""
        if self.id is None:
            object.__setattr__(self, "id", self._generate_content_based_id())
        self.model_dump_json()
        return self

    def __setattr__(self, name: str, value: Any) -> None:
        super().__setattr__(name, value)
        # Ensure added property can be serialized.
        self.model_dump_json()
        # Regenerate ID if content changed (but not if setting 'id' itself)
        if name != "id":
            object.__setattr__(self, "id", self._generate_content_based_id())

    def get_type(self) -> str:
        """Return the event type string used for routing."""
        if self.type is None:
            msg = (
                f"{self.__class__.__name__} has no type set. "
                "All events must have an explicit type string."
            )
            raise ValueError(msg)
        return self.type

    def get_attr(self, name: str) -> Any:
        """Get an attribute value from the attributes map."""
        return self.attributes.get(name)

    def set_attr(self, name: str, value: Any) -> None:
        """Set an attribute value in the attributes map."""
        self.attributes[name] = value

    @classmethod
    def from_event(cls, event: "Event") -> "Event":
        """Reconstruct a typed event from a base Event.

        Subclasses override this to validate attributes and return a
        properly typed instance.
        """
        return event

    @classmethod
    def from_json(cls, json_str: str) -> "Event":
        """Deserialize a unified event from a JSON string.

        Parameters
        ----------
        json_str : str
            JSON string containing at least a ``type`` field.

        Returns:
        -------
        Event
            The deserialized event.

        Raises:
        ------
        ValueError
            If the ``type`` field is missing or empty.
        """
        data = json.loads(json_str)
        event = cls.model_validate(data)
        if event.type is None or event.type == "":
            msg = "Event JSON must contain a 'type' field."
            raise ValueError(msg)
        for key in list(event.attributes):
            event.attributes[key] = _reconstruct_row_if_needed(event.attributes[key])
        return event


class InputEvent(Event):
    """Event generated by the framework, carrying an input data that
    arrives at the agent.

    Attributes:
    ----------
    input : Any
        The input data arriving at the agent.
    """

    EVENT_TYPE: ClassVar[str] = "_input_event"

    def __init__(self, input: Any) -> None:
        """Create an InputEvent with the given input data."""
        super().__init__(
            type=InputEvent.EVENT_TYPE,
            attributes={"input": input},
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "InputEvent":
        assert "input" in event.attributes
        return InputEvent(input=event.attributes["input"])

    @property
    def input(self) -> Any:
        """Return the input data."""
        return self.get_attr("input")


class OutputEvent(Event):
    """Event representing a result from agent. By generating an OutputEvent,
    actions can emit output data.

    Attributes:
    ----------
    output : Any
        The output result returned by the agent.
    """

    EVENT_TYPE: ClassVar[str] = "_output_event"

    def __init__(self, output: Any) -> None:
        """Create an OutputEvent with the given output data."""
        super().__init__(
            type=OutputEvent.EVENT_TYPE,
            attributes={"output": output},
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "OutputEvent":
        assert "output" in event.attributes
        return OutputEvent(output=event.attributes["output"])

    @property
    def output(self) -> Any:
        """Return the output data."""
        return self.get_attr("output")
