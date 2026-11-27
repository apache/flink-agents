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
################################################################################
"""Real Python scheduling with an in-memory Java durable journal."""

from typing import ClassVar

import pytest

from flink_agents.runtime.flink_runner_context import FlinkRunnerContext
from flink_agents.runtime.tests.test_flink_runner_context_reconcilable import (
    _close_runner_context,
    _create_runner_context,
    _FakeJavaRunnerContext,
)


class RuntimeContext(FlinkRunnerContext):
    instances: ClassVar[list] = []

    def __init__(self, java=None, config=None) -> None:
        self.__dict__.update(
            _create_runner_context(java or _FakeJavaRunnerContext(), config).__dict__
        )
        self.instances.append(self)


@pytest.fixture(autouse=True)
def close_runtime_contexts():
    yield
    for ctx in RuntimeContext.instances:
        _close_runner_context(ctx)
    RuntimeContext.instances.clear()


class SubgraphBridge(_FakeJavaRunnerContext):
    """Journal and event notifications controlled by tests, without worker sleeps."""

    def __init__(self) -> None:
        super().__init__()
        self.calls = {}
        self.responses = {}
        self.started = []
        self.cancelled = []
        self.closed = []
        self.request = None
        self.preparations = 0

    def _prepare(self, identity: str) -> str:
        import cloudpickle

        self.preparations += 1
        index = self.current_call_index
        saved = self.call_results[index] if index < len(self.call_results) else None
        if saved is not None and saved.function_id != identity:
            self.clearCallResultsFromAndPersist(index)
            saved = None
        if saved is None:
            self.appendPendingCall(identity)
        self.advanceCallIndexBy(1)
        self.calls[identity] = (
            index,
            cloudpickle.loads(saved.result_payload)
            if saved is not None and saved.status != "PENDING"
            else None,
        )
        return identity

    def prepareChatCall(self, request_json):
        from flink_agents.api.events.event import Event

        self.request = Event.from_json(request_json)
        return self._prepare(f"chat:{self.current_call_index}")

    def prepareInternalSubagentCall(self, scope, session_id, call_id, prompt):
        return self._prepare(f"{session_id}#{call_id}")

    def startSubgraphCall(self, identity):
        self.started.append(identity)

    def isSubgraphCallReady(self, identity):
        return self.calls[identity][1] is not None or (
            identity in self.started
            and identity not in self.cancelled
            and identity in self.responses
        )

    def finishSubgraphCall(self, identity):
        import cloudpickle

        index, saved = self.calls[identity]
        if saved is not None:
            return saved
        result = self.responses[identity]
        self.finalizeCallAt(index, identity, cloudpickle.dumps(result), None)
        self.calls[identity] = (index, result)
        return result

    def cancelSubgraphCall(self, identity):
        self.cancelled.append(identity)

    def closeSubgraphCall(self, identity):
        self.closed.append(identity)
