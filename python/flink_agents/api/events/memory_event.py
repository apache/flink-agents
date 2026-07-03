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
"""Memory observation events, mirroring the Java MemoryEvent hierarchy."""

import base64
import json
from typing import Any, ClassVar, Dict, List

from typing_extensions import override

from flink_agents.api.events.event import Event


def _observation_attributes(key: Any, value: Any) -> Dict[str, Any]:
    """Build attributes shared by memory-operation and run-begin events."""
    if not isinstance(key, str):
        msg = "Memory observation attribute 'key' must be a string"
        raise TypeError(msg)
    if not isinstance(value, dict):
        msg = "Memory observation attribute 'value' must be a dict"
        raise TypeError(msg)
    try:
        normalized = json.loads(
            json.dumps(
                value,
                ensure_ascii=False,
                allow_nan=False,
                default=_encode_json_value,
            )
        )
    except (TypeError, ValueError) as error:
        msg = "Memory observation value must be JSON serializable"
        raise ValueError(msg) from error
    return {"key": key, "value": normalized}


def _encode_json_value(value: Any) -> Any:
    """Mirror Jackson's byte[] wire representation; reject other unknown values."""
    if isinstance(value, bytes | bytearray):
        return base64.b64encode(bytes(value)).decode("ascii")
    msg = f"Object of type {type(value).__name__} is not JSON serializable"
    raise TypeError(msg)


class MemoryEvent(Event):
    """Base class of the memory observation events, emitted at the action
    finish boundary. Each concrete subclass pins one of the operation kinds
     as its ``EVENT_TYPE``; instantiate a subclass, not this base class.

    Attributes ``key`` (the String Flink key) and ``value`` (the operation's
    folded JSON value map) live inside ``attributes``:
    ``{id, type, attributes: {key, value}}``.
    """

    EVENT_TYPE: ClassVar[str | None] = None  # pinned by each concrete subclass

    def __init__(self, *, key: str, value: Dict[str, Any]) -> None:
        """Create a memory event; the type comes from the subclass EVENT_TYPE."""
        event_type = type(self).EVENT_TYPE
        if event_type is None:
            msg = "MemoryEvent is abstract; instantiate one of its subclasses"
            raise TypeError(msg)
        super().__init__(
            type=event_type, attributes=_observation_attributes(key, value)
        )

    @classmethod
    def is_memory_type(cls, type: str | None) -> bool:
        """Return True iff type is one of the seven memory operation types."""
        return type in _TYPE_TO_CLASS

    @classmethod
    @override
    def from_event(cls, event: Event) -> "MemoryEvent":
        """Reconstruct the typed subclass view from a base Event."""
        subclass = _TYPE_TO_CLASS.get(event.type)
        if subclass is None:
            msg = f"Not a memory event type: {event.type}"
            raise ValueError(msg)
        kwargs = {
            "key": event.attributes.get("key"),
            "value": event.attributes.get("value"),
        }
        if subclass is LongTermUpdateEvent:
            kwargs["cleared_sets"] = event.attributes.get("cleared_sets", [])
        result = subclass(**kwargs)
        result.id = event.id
        return result

    @property
    def key(self) -> str:
        """Return the String Flink key this operation belongs to."""
        return self.get_attr("key")

    @property
    def value(self) -> Dict[str, Any]:
        """Return this operation's folded JSON map."""
        return self.get_attr("value")


class ShortTermWriteEvent(MemoryEvent):
    """Memory observation event: a write to short-term memory."""

    EVENT_TYPE: ClassVar[str] = "_short_term_write_event"


class ShortTermReadEvent(MemoryEvent):
    """Memory observation event: a read from short-term memory."""

    EVENT_TYPE: ClassVar[str] = "_short_term_read_event"


class SensoryWriteEvent(MemoryEvent):
    """Memory observation event: a write to sensory memory."""

    EVENT_TYPE: ClassVar[str] = "_sensory_write_event"


class SensoryReadEvent(MemoryEvent):
    """Memory observation event: a read from sensory memory."""

    EVENT_TYPE: ClassVar[str] = "_sensory_read_event"


class LongTermUpdateEvent(MemoryEvent):
    """Long-term memory adds, updates, item deletes, and set clears."""

    EVENT_TYPE: ClassVar[str] = "_long_term_update_event"

    def __init__(
        self,
        *,
        key: str,
        value: Dict[str, Any],
        cleared_sets: List[str] | None = None,
    ) -> None:
        """Create an update event with item changes and explicit set clears."""
        if cleared_sets is not None and not isinstance(cleared_sets, list):
            msg = "Long-term update 'cleared_sets' must be a list"
            raise TypeError(msg)
        if cleared_sets is not None and not all(
            isinstance(memory_set, str) for memory_set in cleared_sets
        ):
            msg = "Long-term update 'cleared_sets' must contain only strings"
            raise TypeError(msg)
        super().__init__(key=key, value=value)
        self.attributes = {
            **self.attributes,
            "cleared_sets": list(cleared_sets) if cleared_sets is not None else [],
        }

    @property
    def cleared_sets(self) -> List[str]:
        """Memory sets cleared before any later folded writes in this event."""
        return self.get_attr("cleared_sets")


class LongTermGetEvent(MemoryEvent):
    """Memory observation event: a long-term memory read by id."""

    EVENT_TYPE: ClassVar[str] = "_long_term_get_event"


class LongTermSearchEvent(MemoryEvent):
    """Memory observation event: a semantic search over long-term memory.

    ``value`` maps each memory set to a map from query string to ordered hit
    list; ``results`` exposes that shape.
    """

    EVENT_TYPE: ClassVar[str] = "_long_term_search_event"

    @property
    def results(self) -> Dict[str, Dict[str, List[Dict[str, Any]]]]:
        """Typed view: memory set → query → ordered hit list."""
        return self.get_attr("value")


_TYPE_TO_CLASS: Dict[str, type[MemoryEvent]] = {
    subclass.EVENT_TYPE: subclass
    for subclass in (
        ShortTermWriteEvent,
        ShortTermReadEvent,
        SensoryWriteEvent,
        SensoryReadEvent,
        LongTermUpdateEvent,
        LongTermGetEvent,
        LongTermSearchEvent,
    )
}
