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
#  limitations under the License.
################################################################################
"""A shared mailbox-driven future for Chat and internal subagents."""

import json
from typing import Any, Callable

from typing_extensions import override

from flink_agents.api.events.chat_event import ChatResponseError, ChatResponseEvent
from flink_agents.api.events.event import Event
from flink_agents.api.runner_context import DurableFuture, Outcome
from flink_agents.api.subagent import SubagentResult
from flink_agents.plan.exceptions import UncapturableException
from flink_agents.runtime.async_future import SchedulableFuture, rethrow_fatal


class SubgraphFuture(SchedulableFuture, DurableFuture[Any]):
    """Java owns graph execution and durable slots; Python only drives its lifecycle."""

    def __init__(self, ctx: Any, preparation: Callable, decoder: Callable) -> None:
        """Bind opaque bridge operations and the raw-result decoder."""
        super().__init__(ctx)
        self._java = ctx._j_runner_context
        self._preparation = preparation
        self._decoder = decoder
        self._call_id = None

    @classmethod
    def chat(cls, ctx: Any, request: Any) -> "SubgraphFuture":
        """Create a lazy call for a snapshot of the chat request."""
        request_json = request.model_dump_json()
        return cls(
            ctx,
            lambda: ctx._j_runner_context.prepareChatCall(request_json),
            lambda raw: ChatResponseEvent.from_event(Event.from_json(raw)).response,
        )

    @classmethod
    def subagent(
        cls, ctx: Any, scope: str, session_id: str, call_id: str, prompt: Any
    ) -> "SubgraphFuture":
        """Create a lazy internal-subagent call with framework-assigned identities."""

        def decode(raw: str) -> SubagentResult:
            payload = json.loads(raw)
            return SubagentResult(
                success=payload["success"],
                result=payload["result"],
                error_message=payload["errorMessage"],
            )

        return cls(
            ctx,
            lambda: ctx._j_runner_context.prepareInternalSubagentCall(
                scope, session_id, call_id, prompt
            ),
            decode,
        )

    def _bridge(self, func: Callable, *args: Any) -> Any:
        try:
            return func(*args)
        except BaseException as error:
            rethrow_fatal(error)
            message = "Subgraph call failed"
            raise UncapturableException(message) from error

    @override
    def prepare(self) -> Outcome | None:
        if self._is_done():
            return self._get_completed_outcome()
        if self._call_id is None:
            self._call_id = self._bridge(self._preparation)
        if self.ready():
            return self.finish()
        return None

    @override
    def start(self) -> None:
        self._bridge(self._java.startSubgraphCall, self._call_id)

    @override
    def ready(self) -> bool:
        return self._is_done() or (
            self._call_id is not None
            and self._bridge(self._java.isSubgraphCallReady, self._call_id)
        )

    @override
    def finish(self, timeout: TimeoutError | None = None) -> Outcome:
        if not self._is_done():
            if timeout is not None:
                outcome = Outcome.failure(timeout)
            else:
                raw = self._bridge(self._java.finishSubgraphCall, self._call_id)
                try:
                    outcome = Outcome.success(self._decoder(raw))
                except ChatResponseError as error:
                    outcome = Outcome.failure(error)
                except Exception as error:
                    message = "Cannot decode subgraph result"
                    raise UncapturableException(message) from error
            self._complete(outcome)
        return self._get_completed_outcome()

    @override
    def cancel(self) -> None:
        if self._call_id is not None and not self._is_done():
            self._bridge(self._java.cancelSubgraphCall, self._call_id)

    @override
    def close(self) -> None:
        if self._call_id is not None:
            self._bridge(self._java.closeSubgraphCall, self._call_id)
