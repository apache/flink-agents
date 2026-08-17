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
from __future__ import annotations

import hashlib
from typing import TYPE_CHECKING, Any

from flink_agents.api.events.event import OutputEvent
from flink_agents.api.memory_object import MemoryType, validate_memory_value
from flink_agents.api.memory_reference import MemoryRef

if TYPE_CHECKING:
    from uuid import UUID

    from flink_agents.api.events.event import Event
    from flink_agents.api.runner_context import RunnerContext

_ATTACHMENT_ROOT = "__event_attachments__"


class EventAttachmentError(RuntimeError):
    """Raised when an Event attachment cannot be stored or loaded."""


def _hash_attachment_key(key: str) -> str:
    return hashlib.sha256(key.encode("UTF-8")).hexdigest()


def build_attachment_path(event_id: UUID, key: str) -> str:
    """Build the canonical SensoryMemory path for one attachment."""
    return f"{_ATTACHMENT_ROOT}.{event_id}.{_hash_attachment_key(key)}"


def _attachment_context(event: Event, key: str, path: str | None = None) -> str:
    suffix = f", path={path}" if path is not None else ""
    return f"event_id={event.id}, event_type={event.type}, key={key}{suffix}"


def store_event_attachments(event: Event, ctx: RunnerContext) -> None:
    """Store concrete attachment values in SensoryMemory and replace them with refs."""
    if not event.attachments:
        return

    if event.type == OutputEvent.EVENT_TYPE:
        keys = ", ".join(sorted(event.attachments))
        msg = f"Output events cannot carry attachments: {_attachment_context(event, keys)}"
        raise EventAttachmentError(msg)

    pending: list[tuple[str, str, Any]] = []
    for key, value in event.attachments.items():
        if isinstance(value, MemoryRef):
            if value.memory_type != MemoryType.SENSORY:
                msg = (
                    "Event attachments must use sensory memory references: "
                    f"{_attachment_context(event, key, value.path)}"
                )
                raise EventAttachmentError(msg)
            continue
        path = build_attachment_path(event.id, key)
        try:
            validate_memory_value(path, value)
        except Exception as exc:
            msg = f"Invalid event attachment value: {_attachment_context(event, key, path)}"
            raise EventAttachmentError(msg) from exc
        pending.append((key, path, value))

    for key, path, value in pending:
        event.attachments[key] = ctx.sensory_memory.set(path, value)


def load_event_attachments(event: Event, ctx: RunnerContext) -> None:
    """Load sensory refs in place immediately before a Python Action runs."""
    for key, value in list(event.attachments.items()):
        if not isinstance(value, MemoryRef):
            continue

        try:
            attachment = ctx.sensory_memory.get(value)
        except Exception as exc:
            msg = f"Failed to load event attachment: {_attachment_context(event, key, value.path)}"
            raise EventAttachmentError(msg) from exc

        if attachment is None:
            msg = (
                "Event attachment does not exist in sensory memory: "
                f"{_attachment_context(event, key, value.path)}"
            )
            raise EventAttachmentError(msg)
        event.attachments[key] = attachment
