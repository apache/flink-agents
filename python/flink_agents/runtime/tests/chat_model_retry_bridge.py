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
"""Fixtures for the real Java/Python chat model retry classification test."""

from typing import Any, Dict, List, Mapping, Sequence

from typing_extensions import override

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.tools.tool import Tool


class StatusError(Exception):
    """Provider failure carrying an HTTP status_code."""

    def __init__(self, status_code: int) -> None:
        super().__init__(f"status {status_code}")
        self.status_code = status_code


class BridgeStatusChatConnection(BaseChatModelConnection):
    """Python chat connection that raises a configured failure and counts calls."""

    status_code: int | None = None
    wrapped: bool = False
    calls: int = 0

    @override
    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        self.calls += 1
        if self.status_code is None:
            msg = "transient provider failure"
            raise RuntimeError(msg)
        if self.wrapped:
            self._raise_wrapped(self.status_code)
        raise StatusError(self.status_code)

    @staticmethod
    def _raise_wrapped(code: int) -> None:
        def raise_inner() -> None:
            raise StatusError(code)

        try:
            raise_inner()
        except StatusError as err:
            msg = "wrapped provider failure"
            raise RuntimeError(msg) from err


class BridgeStatusChatSetup(BaseChatModelSetup):
    """Python chat setup that raises a configured failure and counts calls."""

    status_code: int | None = None
    wrapped: bool = False
    calls: int = 0

    @property
    @override
    def model_kwargs(self) -> Dict[str, Any]:
        return {}

    @override
    def open(self) -> None:
        pass

    @override
    def chat(
        self,
        messages: Sequence[ChatMessage],
        prompt_args: Mapping[str, Any] | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        self.calls += 1
        if self.status_code is None:
            msg = "transient provider failure"
            raise RuntimeError(msg)
        if self.wrapped:
            BridgeStatusChatConnection._raise_wrapped(self.status_code)
        raise StatusError(self.status_code)


def get_calls(resource: Any) -> int:
    return int(resource.calls)


def run_python_chat_against_java_setup(j_setup: Any, max_retries: int) -> int:
    """Run Python chat() against a JavaChatModelSetup across Pemja and return retry_count."""
    import asyncio
    from uuid import uuid4

    from flink_agents.api.chat_message import MessageRole
    from flink_agents.plan.actions.chat_model_action import chat
    from flink_agents.plan.resource.java.java_chat_model import JavaChatModelSetup
    from flink_agents.plan.tests.actions.test_chat_model_action_retry import (
        _create_mock_runner_context,
    )
    from flink_agents.runtime.java_resource_adapter import JavaResourceAdapterImpl

    class _AdapterStub:
        def fromPythonChatMessage(self, payload: dict[str, Any]) -> Any:
            from pemja import findClass

            return findClass(
                "org.apache.flink.agents.api.chat.messages.ChatMessage"
            ).fromMap(payload)

    setup = JavaChatModelSetup(
        j_resource=j_setup,
        j_resource_adapter=JavaResourceAdapterImpl(_AdapterStub()),
        connection="java-conn",
        model="java-model",
    )
    ctx, sent_events, _, _ = _create_mock_runner_context(
        setup, max_retries=max_retries, retry_wait_interval_sec=0
    )
    asyncio.run(
        chat(
            uuid4(),
            "test-model",
            [ChatMessage.of(role=MessageRole.USER, content="hi")],
            {},
            None,
            ctx,
        )
    )
    assert sent_events[0].is_failed
    return int(sent_events[0].retry_count)
