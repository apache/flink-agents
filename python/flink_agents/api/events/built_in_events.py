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
"""Centralized registry that restores built-in event types at the JSON boundary.

Events cross the Python/Java boundary as JSON and are deserialized into the base
:class:`~flink_agents.api.events.event.Event`, whose ``attributes`` is a plain
``Dict[str, Any]``. Nested typed values (for example
:class:`~flink_agents.api.chat_message.ChatMessage`) therefore arrive as generic
dicts, and infrastructure that runs before an action — the event router, the
trace log, and event listeners — observes an untyped event even for a known
built-in type.

:func:`restore` maps each built-in event type to its existing ``from_event``
reconstruction path, so a single call at the lowest-level deserialization entry
point (:meth:`Event.from_json`) covers every downstream boundary. This mirrors
the Java ``BuiltInEvents`` registry.

The contract is:

- A registered built-in type is reconstructed into its concrete subclass,
  preserving the event id, attachments, upstream event id, and upstream action
  name.
- An unknown or user-defined type is returned unchanged as a generic ``Event``.
- Restoration is idempotent: reconstructing an already-typed event is safe, so
  actions that still call ``from_event`` themselves keep working.
- A malformed built-in event fails clearly with a ``ValueError`` rather than
  surfacing an opaque reconstruction error.
"""

from typing import Dict

from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.memory_event import (
    LongTermGetEvent,
    LongTermSearchEvent,
    LongTermUpdateEvent,
    MemoryEvent,
    SensoryReadEvent,
    SensoryWriteEvent,
    ShortTermReadEvent,
    ShortTermWriteEvent,
)
from flink_agents.api.events.run_event import AgentRunBeginEvent
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent

# Built-in event type -> concrete class whose ``from_event`` reconstructs it. The
# memory observation types all dispatch through ``MemoryEvent.from_event``, which
# selects the concrete subclass from the event type. ``ModelRoutingEvent`` is
# intentionally absent: it is a Java-only built-in type with no Python counterpart.
REGISTRY: Dict[str, type[Event]] = {
    InputEvent.EVENT_TYPE: InputEvent,
    OutputEvent.EVENT_TYPE: OutputEvent,
    ChatRequestEvent.EVENT_TYPE: ChatRequestEvent,
    ChatResponseEvent.EVENT_TYPE: ChatResponseEvent,
    ToolRequestEvent.EVENT_TYPE: ToolRequestEvent,
    ToolResponseEvent.EVENT_TYPE: ToolResponseEvent,
    ContextRetrievalRequestEvent.EVENT_TYPE: ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent.EVENT_TYPE: ContextRetrievalResponseEvent,
    AgentRunBeginEvent.EVENT_TYPE: AgentRunBeginEvent,
    ShortTermWriteEvent.EVENT_TYPE: MemoryEvent,
    ShortTermReadEvent.EVENT_TYPE: MemoryEvent,
    SensoryWriteEvent.EVENT_TYPE: MemoryEvent,
    SensoryReadEvent.EVENT_TYPE: MemoryEvent,
    LongTermUpdateEvent.EVENT_TYPE: MemoryEvent,
    LongTermGetEvent.EVENT_TYPE: MemoryEvent,
    LongTermSearchEvent.EVENT_TYPE: MemoryEvent,
}


def restore(event: Event) -> Event:
    """Restore a registered built-in event; unknown types stay generic.

    Args:
        event: The deserialized event, possibly a generic ``Event`` carrying a
            built-in ``type``.

    Returns:
        The reconstructed built-in event, or ``event`` unchanged when its type is
        not registered.

    Raises:
        ValueError: If a registered built-in event cannot be reconstructed.
    """
    cls = REGISTRY.get(event.type)
    if cls is None:
        return event
    try:
        restored = cls.from_event(event)
    except Exception as exc:
        msg = f"Malformed built-in event of type '{event.type}'"
        raise ValueError(msg) from exc
    return restored
