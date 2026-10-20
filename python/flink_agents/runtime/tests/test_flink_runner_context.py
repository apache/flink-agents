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
from unittest.mock import Mock

from flink_agents.api.events.event import Event
from flink_agents.api.memory_reference import MemoryRef
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


def test_action_config_follows_resource_scope_across_task_switches() -> None:
    """A chat inside a child graph uses the child config, then restores root config."""
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    java_context = Mock()
    java_context.getActionName.return_value = "chat_model_action"
    ctx._j_runner_context = java_context
    root_plan, child_plan = Mock(), Mock()
    root_plan.get_action_config.return_value = {"model": "root"}
    root_plan.get_action_config_value.return_value = "root"
    child_plan.get_action_config.return_value = {"model": "child"}
    child_plan.get_action_config_value.return_value = "child"
    ctx._FlinkRunnerContext__agent_plan = root_plan
    ctx._FlinkRunnerContext__scoped_resource_caches = {
        "child-json": (child_plan, Mock())
    }

    for scope, expected in [(None, "root"), ("child-json", "child"), (None, "root")]:
        java_context.getActiveScopePlanJson.return_value = scope
        assert ctx.action_config == {"model": expected}
        assert ctx.get_action_config_value("model") == expected

    child_plan.get_action_config.assert_called_once_with("chat_model_action")
    child_plan.get_action_config_value.assert_called_once_with(
        action_name="chat_model_action", key="model"
    )


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
        self.subagent_result_json: str | None = None

    def getSensoryMemory(self) -> _FakeJavaSensoryMemory:
        return self.sensory_memory

    def sendEventJson(self, event_json: str) -> None:
        self.sent_event_json = event_json

    def tryCompleteInternalSubagentCall(
        self, session_id: str, call_id: str
    ) -> str | None:
        return self.subagent_result_json


def _bridge_context() -> tuple[FlinkRunnerContext, _FakeJavaRunnerContext]:
    java_context = _FakeJavaRunnerContext()
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._j_runner_context = java_context
    return ctx, java_context


def test_send_event_offloads_attachments_before_forwarding() -> None:
    ctx, java_context = _bridge_context()
    payload = {"value": "original"}
    event = Event(type="AttachmentStep", attachments={"payload": payload})

    ctx.send_event(event)

    attachment = event.get_attachment("payload")
    assert isinstance(attachment, MemoryRef)
    assert java_context.sensory_memory.values[attachment.path] == payload
    assert java_context.sent_event_json is not None
    forwarded_event = Event.from_json(java_context.sent_event_json)
    assert forwarded_event.get_attachment("payload") == attachment


def test_send_event_preserves_identity_and_payload() -> None:
    ctx, java_context = _bridge_context()

    event = Event(type="result", attributes={"source": "user"})
    ctx.send_event(event)

    assert java_context.sent_event_json is not None
    forwarded = Event.from_json(java_context.sent_event_json)
    assert forwarded.id == event.id
    assert forwarded.get_type() == event.get_type()
    assert forwarded.get_attr("source") == "user"


# What the Java bridge hands over: byte for byte the payload
# ``InternalSubagentBridgeTest`` pins, so both halves of the bridge are asserted
# against one representation.
_SUBAGENT_RESULT_JSON = (
    '{"success":true,"result":[{"1":"answer","nested":{"7":[1,null]},'
    '"absent":null}],"errorMessage":null}'
)


def test_internal_subagent_poll_reports_a_running_call_as_none() -> None:
    ctx, _ = _bridge_context()

    assert ctx.try_complete_internal_subagent_call("session", "call") is None


def test_internal_subagent_poll_decodes_the_bridge_json() -> None:
    ctx, java_context = _bridge_context()
    java_context.subagent_result_json = _SUBAGENT_RESULT_JSON

    result = ctx.try_complete_internal_subagent_call("session", "call")

    assert result is not None
    assert result.success
    assert result.error_message is None
    assert result.result == [
        {"1": "answer", "nested": {"7": [1, None]}, "absent": None}
    ]


def test_internal_subagent_poll_decodes_a_child_failure() -> None:
    ctx, java_context = _bridge_context()
    java_context.subagent_result_json = (
        '{"success":false,"result":null,"errorMessage":"child refused"}'
    )

    result = ctx.try_complete_internal_subagent_call("session", "call")

    assert result is not None
    assert not result.success
    assert result.result is None
    assert result.error_message == "child refused"
