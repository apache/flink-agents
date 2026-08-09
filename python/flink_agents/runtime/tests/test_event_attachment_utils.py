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
import uuid

import pytest

from flink_agents.api.events.event import Event, OutputEvent
from flink_agents.api.memory_object import MemoryType
from flink_agents.api.memory_reference import MemoryRef
from flink_agents.runtime.memory.event_attachment_utils import (
    EventAttachmentError,
    build_attachment_path,
    load_event_attachments,
    store_event_attachments,
)
from flink_agents.runtime.tests.local_memory_object import LocalMemoryObject


class MockRunnerContext:
    def __init__(self, memory: LocalMemoryObject) -> None:
        self._memory = memory

    @property
    def sensory_memory(self) -> LocalMemoryObject:
        return self._memory


def test_store_event_attachments() -> None:
    sensory_memory = LocalMemoryObject(MemoryType.SENSORY, {})
    ctx = MockRunnerContext(sensory_memory)
    event_id = uuid.uuid4()
    payload = {"value": "original"}
    event = Event.model_construct(
        id=event_id,
        type="AttachmentStep",
        attributes={},
        attachments={"payload": payload},
    )

    store_event_attachments(event, ctx)

    attachment = event.get_attachment("payload")
    assert isinstance(attachment, MemoryRef)
    assert attachment.path == build_attachment_path(event_id, "payload")
    assert sensory_memory.get(attachment) == payload


def test_store_offloads_non_utf8_bytes_from_regular_event() -> None:
    """Test that raw bytes are offloaded before JSON serialization is required."""
    sensory_memory = LocalMemoryObject(MemoryType.SENSORY, {})
    ctx = MockRunnerContext(sensory_memory)
    payload = b"\xff\x00"
    event = Event(type="AttachmentStep", attachments={"payload": payload})

    store_event_attachments(event, ctx)

    attachment = event.get_attachment("payload")
    assert isinstance(attachment, MemoryRef)
    assert sensory_memory.get(attachment) == payload
    event.model_dump_json()


def test_store_rejects_output_event_attachments_before_storing_them() -> None:
    sensory_memory = LocalMemoryObject(MemoryType.SENSORY, {})
    ctx = MockRunnerContext(sensory_memory)
    event_id = uuid.uuid4()
    attachments = {"zeta": {"value": 2}, "alpha": {"value": 1}}
    event = Event.model_construct(
        id=event_id,
        type=OutputEvent.EVENT_TYPE,
        attributes={"output": "result"},
        attachments=dict(attachments),
    )

    with pytest.raises(EventAttachmentError) as exc_info:
        store_event_attachments(event, ctx)

    message = str(exc_info.value)
    assert message.startswith("Output events cannot carry attachments:")


def test_load_event_attachments() -> None:
    sensory_memory = LocalMemoryObject(MemoryType.SENSORY, {})
    ctx = MockRunnerContext(sensory_memory)
    event_id = uuid.uuid4()
    payload = {"value": "original"}
    reference = sensory_memory.set(build_attachment_path(event_id, "payload"), payload)
    event = Event.model_construct(
        id=event_id,
        type="AttachmentStep",
        attributes={},
        attachments={"payload": reference},
    )

    load_event_attachments(event, ctx)

    assert event.get_attachment("payload") == payload


def test_build_attachment_path() -> None:
    event_id = uuid.UUID("00000000-0000-0000-0000-000000000001")

    path = build_attachment_path(event_id, "payload")

    assert (
        path
        == "__event_attachments__."
        + str(event_id)
        + ".239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5"
    )
