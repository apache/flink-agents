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
"""IDE-discoverable constants for built-in event types, plus a tiny registry
for user-defined event classes.

Built-in events are exposed as :class:`EventType` attributes; user events
declare ``EVENT_TYPE: ClassVar[str]`` and are registered via
:func:`EventType.register`::

    @action(EventType.InputEvent, EventType.OutputEvent)
    def handle(...): ...

    EventType.register(MyCustomEvent)
    EventType.lookup("MyCustomEvent")  # -> "_my_custom_event"
"""

from __future__ import annotations

import threading
from typing import Dict, Type

# Hard-coded to avoid an event_type -> event -> event_type circular import.
# A consistency test asserts each value matches XxxEvent.EVENT_TYPE.
_BUILTIN: Dict[str, str] = {
    "InputEvent": "_input_event",
    "OutputEvent": "_output_event",
    "ChatRequestEvent": "_chat_request_event",
    "ChatResponseEvent": "_chat_response_event",
    "ToolRequestEvent": "_tool_request_event",
    "ToolResponseEvent": "_tool_response_event",
    "ContextRetrievalRequestEvent": "_context_retrieval_request_event",
    "ContextRetrievalResponseEvent": "_context_retrieval_response_event",
}

_USER_REGISTERED: Dict[str, str] = {}
_LOCK = threading.Lock()


def register(event_class: Type) -> None:
    """Register a user-defined event class.

    The class must declare ``EVENT_TYPE: ClassVar[str]`` as a non-empty string.
    Re-registering the same ``(class_name, EVENT_TYPE)`` pair is a no-op.

    Raises:
        ValueError: if ``event_class`` is None, lacks a non-empty ``EVENT_TYPE``,
            or its ``__name__`` collides with a built-in.
        RuntimeError: if the same name is already bound to a different
            ``EVENT_TYPE`` value.
    """
    if event_class is None:
        msg = "event_class must not be None"
        raise ValueError(msg)
    name = event_class.__name__
    if name in _BUILTIN:
        msg = f"Short name {name!r} collides with a built-in EventType"
        raise ValueError(msg)
    event_type = getattr(event_class, "EVENT_TYPE", None)
    if not isinstance(event_type, str) or not event_type:
        msg = (
            f"{event_class.__module__}.{event_class.__name__} must declare "
            f"EVENT_TYPE as a non-empty string"
        )
        raise ValueError(msg)
    with _LOCK:
        existing = _USER_REGISTERED.get(name)
        if existing is None:
            _USER_REGISTERED[name] = event_type
            return
        if existing != event_type:
            msg = (
                f"Short name {name!r} already registered with EVENT_TYPE={existing!r}; "
                f"cannot re-register with EVENT_TYPE={event_type!r}"
            )
            raise RuntimeError(msg)


def lookup(name: str | None) -> str | None:
    """Return the ``EVENT_TYPE`` string for a registered short name, else ``None``.

    Built-in names take precedence over user-registered ones.
    """
    if name is None:
        return None
    builtin = _BUILTIN.get(name)
    if builtin is not None:
        return builtin
    return _USER_REGISTERED.get(name)


def lookup_or_self(name: str) -> str:
    """Like :func:`lookup`, but returns ``name`` unchanged when not registered."""
    v = lookup(name)
    return v if v is not None else name


def is_known(name: str | None) -> bool:
    """Return ``True`` if ``name`` is a registered short name."""
    return lookup(name) is not None


def all_registered() -> Dict[str, str]:
    """Return a snapshot of all registrations (built-in + user-registered)."""
    snapshot = dict(_BUILTIN)
    snapshot.update(_USER_REGISTERED)
    return snapshot


def _clear_user_registered_for_testing() -> None:
    """Test-only: drop user registrations between unit tests."""
    with _LOCK:
        _USER_REGISTERED.clear()


class EventType:
    """Namespace of built-in event-type constants.

    Each constant is byte-equal to the corresponding ``XxxEvent.EVENT_TYPE``
    and is meant to be used inside ``trigger_conditions``::

        @action(EventType.InputEvent)

    For user-defined events, call :func:`register` first, then :func:`lookup`.
    """

    InputEvent: str = _BUILTIN["InputEvent"]
    OutputEvent: str = _BUILTIN["OutputEvent"]
    ChatRequestEvent: str = _BUILTIN["ChatRequestEvent"]
    ChatResponseEvent: str = _BUILTIN["ChatResponseEvent"]
    ToolRequestEvent: str = _BUILTIN["ToolRequestEvent"]
    ToolResponseEvent: str = _BUILTIN["ToolResponseEvent"]
    ContextRetrievalRequestEvent: str = _BUILTIN["ContextRetrievalRequestEvent"]
    ContextRetrievalResponseEvent: str = _BUILTIN["ContextRetrievalResponseEvent"]

    register = staticmethod(register)
    lookup = staticmethod(lookup)
    lookup_or_self = staticmethod(lookup_or_self)
    is_known = staticmethod(is_known)
    all_registered = staticmethod(all_registered)

    def __init__(self) -> None:
        """Reject instantiation; ``EventType`` is a namespace, not a class."""
        msg = "EventType is a namespace; do not instantiate"
        raise TypeError(msg)
