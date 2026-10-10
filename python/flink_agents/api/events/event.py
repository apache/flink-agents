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
from dataclasses import dataclass
from enum import Enum
from typing import Any, ClassVar, Dict, List, Set, Tuple

try:
    from typing import Self, override
except ImportError:
    from typing_extensions import Self, override
from uuid import UUID, uuid4

from pydantic import (
    BaseModel,
    Field,
    ValidationError,
    field_validator,
    model_validator,
)
from pydantic_core import PydanticCustomError, PydanticSerializationError
from pyflink.common import Row

from flink_agents.api.memory_reference import MemoryRef


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


class _AttrKind(Enum):
    """The shape a built-in attribute value takes at the JSON boundary."""

    SCALAR = "scalar"
    LIST = "list"
    UUID = "uuid"


@dataclass(frozen=True, slots=True)
class BuiltInAttribute:
    """One attribute of a built-in event's fixed cross-language schema.

    Mirrors the declarative ``requiredOptions()`` / ``optionalOptions()`` style
    of Flink connector factories: a built-in event states its attributes once as
    an ordered tuple of these, and :meth:`Event._validate_attribute_schema` enforces
    presence, unknown-key rejection, and per-attribute type in a single pass at
    the JSON boundary. Create instances through the static factories below.
    """

    name: str
    is_required: bool
    kind: _AttrKind
    types: Tuple[type, ...]
    element_description: str | None = None

    @staticmethod
    def required(name: str, expected: type) -> "BuiltInAttribute":
        """A required scalar attribute that must be an instance of ``expected``."""
        return BuiltInAttribute(name, True, _AttrKind.SCALAR, (expected,))

    @staticmethod
    def optional(name: str, expected: type) -> "BuiltInAttribute":
        """An optional scalar attribute that, when present, must be ``expected``."""
        return BuiltInAttribute(name, False, _AttrKind.SCALAR, (expected,))

    @staticmethod
    def optional_untyped(name: str) -> "BuiltInAttribute":
        """An optional attribute only checked for being a known key, any type."""
        return BuiltInAttribute(name, False, _AttrKind.SCALAR, ())

    @staticmethod
    def required_untyped(name: str) -> "BuiltInAttribute":
        """A required attribute only checked for presence and being a known key."""
        return BuiltInAttribute(name, True, _AttrKind.SCALAR, ())

    @staticmethod
    def required_list(
        name: str, element_description: str, allowed: Tuple[type, ...]
    ) -> "BuiltInAttribute":
        """A required list whose elements must each match a type in ``allowed``."""
        return BuiltInAttribute(
            name, True, _AttrKind.LIST, allowed, element_description
        )

    @staticmethod
    def required_uuid(name: str) -> "BuiltInAttribute":
        """A required UUID attribute, accepted as a UUID or a UUID string."""
        return BuiltInAttribute(name, True, _AttrKind.UUID, ())


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
        Random version 4 UUID generated when the Event is created. An omitted or
        explicit ``None`` id is replaced with a new UUID, matching Java.
    type : str
        Event type string used for routing. Required for all events.
    attributes : Dict[str, Any]
        Key-value properties for the event data.
    attachments : Dict[str, Any]
        Key-value data passed between actions through sensory memory.
    """

    id: UUID = Field(default_factory=uuid4, frozen=True)
    type: str
    attributes: Dict[str, Any] = Field(default_factory=dict)
    attachments: Dict[str, Any] = Field(default_factory=dict)

    @field_validator("id", mode="before")
    @classmethod
    def generate_id_when_explicitly_none(cls, value: Any) -> Any:
        """Treat explicit None like an omitted id and mint a per-occurrence UUID."""
        return uuid4() if value is None else value

    @field_validator("attachments", mode="before")
    @classmethod
    def _deserialize_memory_ref_attachments(cls, attachments: Any) -> Any:
        """Restore explicitly tagged memory-reference attachment values."""
        if not isinstance(attachments, dict):
            return attachments
        return {
            key: MemoryRef.model_validate(value)
            if isinstance(value, dict)
            and value.get(MemoryRef.TYPE_FIELD) == MemoryRef.TYPE_VALUE
            else value
            for key, value in attachments.items()
        }

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

    @model_validator(mode="after")
    def validate_serializable_fields(self) -> "Event":
        """Validate JSON event fields without serializing raw attachments."""
        self.model_dump_json(exclude={"attachments"})
        return self

    def __setattr__(self, name: str, value: Any) -> None:
        # Pydantic's own frozen-field error would not say how to keep an identity.
        if name == "id":
            raise ValidationError.from_exception_data(
                type(self).__name__,
                [
                    {
                        "type": PydanticCustomError(
                            "frozen_event_id",
                            "Field is frozen. An Event's id is fixed when it is "
                            "created; to give a typed Event the identity of an "
                            "existing Event, use typed = typed.reconstruct_from(existing).",
                        ),
                        "loc": ("id",),
                        "input": value,
                    }
                ],
            )
        super().__setattr__(name, value)
        # Raw attachments are offloaded to sensory memory before sending. Validate every
        # other field here without serializing those payloads.
        self.model_dump_json(exclude={"attachments"})

    def reconstruct_from(self, source: "Event") -> Self:
        """Return a typed copy representing the same Event occurrence as source."""
        return self.model_copy(
            update={
                "id": source.id,
                "attachments": dict(source.attachments),
            }
        )

    def get_type(self) -> str:
        """Return the event type string used for routing."""
        return self.type

    def get_attr(self, name: str) -> Any:
        """Get an attribute value from the attributes map."""
        return self.attributes.get(name)

    def set_attr(self, name: str, value: Any) -> None:
        """Set an attribute value in the attributes map."""
        self.attributes[name] = value

    def get_attachment(self, name: str) -> Any:
        """Get an attachment value from the attachments map."""
        return self.attachments.get(name)

    def set_attachment(self, name: str, value: Any) -> None:
        """Set an attachment value in the attachments map.

        If ``value`` is a :class:`MemoryRef`, it must reference sensory memory and
        will not be wrapped again.
        """
        self.attachments = {**self.attachments, name: value}

    @classmethod
    def from_event(cls, event: "Event") -> "Event":
        """Reconstruct a typed event from a base Event.

        Subclasses override this to validate attributes and return a
        properly typed instance.
        """
        return event

    @staticmethod
    def _validate_built_in_attributes(
        type_name: str,
        attributes: Dict[str, Any],
        required: Set[str],
        known: Set[str],
    ) -> None:
        """Validate a built-in event's attributes against its fixed schema.

        Each built-in event's ``from_event`` reconstruction method calls this at
        the JSON / cross-language boundary (:meth:`Event.from_json` ->
        ``restore``), so a malformed built-in event fails clearly instead of
        being reconstructed with silently dropped or defaulted fields. It is
        deliberately not called from ``__init__``: durable/checkpoint recovery
        rebuilds concrete events directly and must keep tolerating
        framework-internal attributes outside the cross-language schema.

        Raises:
            ValueError: If a required attribute is absent or an unknown
                attribute is present.
        """
        for name in required:
            if name not in attributes:
                msg = (
                    f"Missing required attribute '{name}' for built-in event "
                    f"type '{type_name}'."
                )
                raise ValueError(msg)
        unknown = set(attributes) - known
        if unknown:
            msg = (
                f"Unknown attribute(s) {sorted(unknown)} for built-in event "
                f"type '{type_name}'; allowed attributes are {sorted(known)}."
            )
            raise ValueError(msg)

    @staticmethod
    def _require_built_in_attribute(
        type_name: str, attributes: Dict[str, Any], name: str, expected: type
    ) -> Any:
        """Return a required built-in attribute, asserting its runtime type.

        Raises:
            TypeError: If the attribute is missing or not an instance of
                ``expected``.
        """
        value = attributes.get(name)
        if not isinstance(value, expected):
            msg = (
                f"Attribute '{name}' of built-in event type '{type_name}' must "
                f"be a {expected.__name__}, but was {type(value).__name__}."
            )
            raise TypeError(msg)
        return value

    @staticmethod
    def _require_built_in_list_attribute(
        type_name: str,
        attributes: Dict[str, Any],
        name: str,
        element_description: str,
        allowed: Tuple[type, ...],
    ) -> List[Any]:
        """Return a required built-in list attribute, asserting each element's type.

        A nested typed value crosses the JSON boundary as either its concrete
        type or its serialized dict, so callers typically allow both.

        Raises:
            TypeError: If the attribute is missing, is not a list, or holds an
                element that is not an instance of any type in ``allowed``.
        """
        values = Event._require_built_in_attribute(type_name, attributes, name, list)
        for element in values:
            if not isinstance(element, allowed):
                msg = (
                    f"Each '{name}' element of built-in event type "
                    f"'{type_name}' must be {element_description}, but was "
                    f"{type(element).__name__}."
                )
                raise TypeError(msg)
        return values

    @staticmethod
    def _check_built_in_attribute_type(
        type_name: str, attributes: Dict[str, Any], name: str, expected: type
    ) -> None:
        """Assert the type of an optional built-in attribute when present.

        Raises:
            TypeError: If the attribute is present (non-null) but not an
                instance of ``expected``.
        """
        value = attributes.get(name)
        if value is not None and not isinstance(value, expected):
            msg = (
                f"Attribute '{name}' of built-in event type '{type_name}' must "
                f"be a {expected.__name__}, but was {type(value).__name__}."
            )
            raise TypeError(msg)

    @staticmethod
    def _require_uuid_built_in_attribute(
        type_name: str, attributes: Dict[str, Any], name: str
    ) -> None:
        """Assert a required built-in attribute is a UUID or a UUID string.

        Raises:
            TypeError: If the attribute is missing or not a UUID / UUID string.
        """
        value = attributes.get(name)
        if isinstance(value, UUID):
            return
        if isinstance(value, str):
            try:
                UUID(value)
            except ValueError:
                pass
            else:
                return
        msg = (
            f"Attribute '{name}' of built-in event type '{type_name}' must be a "
            f"UUID or UUID string, but was {type(value).__name__}."
        )
        raise TypeError(msg)

    @staticmethod
    def _validate_attribute_schema(
        type_name: str,
        attributes: Dict[str, Any],
        schema: Tuple["BuiltInAttribute", ...],
    ) -> None:
        """Validate attributes against a declared attribute schema in one pass.

        The declarative counterpart to calling
        :meth:`_validate_built_in_attributes` plus the per-attribute type checks
        by hand: presence and unknown keys are enforced first, then each present
        attribute is checked against its declared shape. It delegates to those
        low-level helpers, so the messages and the ``ValueError`` (shape) versus
        ``TypeError`` (type) split are unchanged.

        Raises:
            ValueError: If a required attribute is absent or an unknown
                attribute is present.
            TypeError: If a present attribute has an unexpected type.
        """
        required = {attribute.name for attribute in schema if attribute.is_required}
        known = {attribute.name for attribute in schema}
        Event._validate_built_in_attributes(type_name, attributes, required, known)
        for attribute in schema:
            if not attribute.is_required and attribute.name not in attributes:
                continue
            if attribute.kind == _AttrKind.SCALAR:
                if not attribute.types:
                    continue
                if attribute.is_required:
                    Event._require_built_in_attribute(
                        type_name, attributes, attribute.name, attribute.types[0]
                    )
                else:
                    Event._check_built_in_attribute_type(
                        type_name, attributes, attribute.name, attribute.types[0]
                    )
            elif attribute.kind == _AttrKind.LIST:
                Event._require_built_in_list_attribute(
                    type_name,
                    attributes,
                    attribute.name,
                    attribute.element_description,
                    attribute.types,
                )
            elif attribute.kind == _AttrKind.UUID:
                Event._require_uuid_built_in_attribute(
                    type_name, attributes, attribute.name
                )

    @classmethod
    def from_json(cls, json_str: str) -> "Event":
        """Deserialize an event from a JSON string.

        Known built-in event types are restored to their concrete subclass, so
        nested typed values survive the cross-language boundary; unknown or
        user-defined types are returned as a generic ``Event``.

        Parameters
        ----------
        json_str : str
            JSON string containing at least a ``type`` field.

        Returns:
        -------
        Event
            The deserialized event, or its concrete built-in subclass.

        Raises:
        ------
        ValueError
            If the ``type`` field is missing or empty, or if a built-in event is
            malformed and cannot be reconstructed.
        """
        data = json.loads(json_str)
        if not data.get("type"):
            msg = "Event JSON must contain a non-empty 'type' field."
            raise ValueError(msg)
        event = cls.model_validate(data)
        for key in list(event.attributes):
            event.attributes[key] = _reconstruct_row_if_needed(event.attributes[key])
        # Imported lazily: built_in_events imports the concrete subclasses, which
        # import this module, so a top-level import here would be circular.
        from flink_agents.api.events.built_in_events import restore

        return restore(event)


class InputEvent(Event):
    """Event generated by the framework, carrying an input data that
    arrives at the agent.

    Attributes:
    ----------
    input : Any
        The input data arriving at the agent.
    """

    EVENT_TYPE: ClassVar[str] = "_input_event"

    _ATTRIBUTE_SCHEMA: ClassVar[Tuple[BuiltInAttribute, ...]] = (
        BuiltInAttribute.required_untyped("input"),
    )

    def __init__(self, input: Any) -> None:
        """Create an InputEvent with the given input data."""
        super().__init__(
            type=InputEvent.EVENT_TYPE,
            attributes={"input": input},
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "InputEvent":
        cls._validate_attribute_schema(
            cls.EVENT_TYPE, event.attributes, cls._ATTRIBUTE_SCHEMA
        )
        result = InputEvent(input=event.attributes["input"])
        return result.reconstruct_from(event)

    @property
    def input(self) -> Any:
        """Return the input data."""
        return self.get_attr("input")


class OutputEvent(Event):
    """Event representing a result from agent. By generating an OutputEvent,
    actions can emit output data.

    Attachments are only supported on events passed between actions and cannot
    be carried by an OutputEvent.

    Attributes:
    ----------
    output : Any
        The output result returned by the agent.
    """

    EVENT_TYPE: ClassVar[str] = "_output_event"

    _ATTRIBUTE_SCHEMA: ClassVar[Tuple[BuiltInAttribute, ...]] = (
        BuiltInAttribute.required_untyped("output"),
    )

    def __init__(self, output: Any) -> None:
        """Create an OutputEvent with the given output data."""
        super().__init__(
            type=OutputEvent.EVENT_TYPE,
            attributes={"output": output},
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "OutputEvent":
        if event.attachments:
            msg = "OutputEvent cannot carry attachments."
            raise ValueError(msg)
        cls._validate_attribute_schema(
            cls.EVENT_TYPE, event.attributes, cls._ATTRIBUTE_SCHEMA
        )
        result = OutputEvent(output=event.attributes["output"])
        return result.reconstruct_from(event)

    @property
    def output(self) -> Any:
        """Return the output data."""
        return self.get_attr("output")
