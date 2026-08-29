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
from typing import Any

from flink_agents.api.events.event import Event
from flink_agents.api.memory_reference import MemoryRef
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


class _FakeJavaMemoryRef:
    def __init__(self, path: str) -> None:
        self._path = path

    def getPath(self) -> str:
        return self._path


class _FakeJavaSensoryMemory:
    def __init__(self) -> None:
        self.values: dict[str, Any] = {}

    def set(self, path: str, value: Any) -> _FakeJavaMemoryRef:
        self.values[path] = value
        return _FakeJavaMemoryRef(path)


class _FakeJavaRunnerContext:
    def __init__(self) -> None:
        self.sensory_memory = _FakeJavaSensoryMemory()
        self.sent_event_json: str | None = None

    def getSensoryMemory(self) -> _FakeJavaSensoryMemory:
        return self.sensory_memory

    def sendEventJson(self, event_json: str) -> None:
        self.sent_event_json = event_json


def test_send_event_offloads_attachments_before_forwarding() -> None:
    java_context = _FakeJavaRunnerContext()
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._j_runner_context = java_context
    payload = {"value": "original"}
    event = Event(type="AttachmentStep", attachments={"payload": payload})

    ctx.send_event(event)

    attachment = event.get_attachment("payload")
    assert isinstance(attachment, MemoryRef)
    assert java_context.sensory_memory.values[attachment.path] == payload
    assert java_context.sent_event_json is not None
    forwarded_event = Event.from_json(java_context.sent_event_json)
    assert forwarded_event.get_attachment("payload") == attachment
