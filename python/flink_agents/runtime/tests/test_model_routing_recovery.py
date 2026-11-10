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
from uuid import uuid4

import pytest

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_models.routing import Strategies
from flink_agents.plan.actions.chat_model_action import chat
from flink_agents.plan.tests.actions.test_model_routing import Select, setup
from flink_agents.runtime.tests.test_flink_runner_context_reconcilable import (
    _close_runner_context,
    _create_runner_context,
    _FakeJavaRunnerContext,
    _run_async,
)


@pytest.mark.parametrize("asynchronous", [False, True])
@pytest.mark.parametrize("custom", [False, True])
def test_route_and_fallback_replay_through_runtime_journal(asynchronous, custom):
    Select.calls = 0
    strategy = (
        Strategies.custom(Select, {"model": "big"})
        if custom
        else Strategies.rules({"big": "code"})
    )
    ctx, events, models, _ = setup(strategy, fallback=True, chat_async=asynchronous)
    models["big"].chat.side_effect = ValueError("selected candidate unavailable")
    journal = _FakeJavaRunnerContext()
    for _ in range(2):
        journal.current_call_index = 0
        runner = _create_runner_context(journal)
        ctx.durable_execute = runner.durable_execute
        ctx.durable_execute_async = runner.durable_execute_async
        try:
            _run_async(
                chat(uuid4(), "router", [ChatMessage.user("code")], None, None, ctx)
            )
        finally:
            _close_runner_context(runner)
    assert len(journal.call_results) == 3
    assert journal.current_call_index == 3
    assert all(record.status == "SUCCEEDED" for record in journal.call_results)
    models["big"].chat.assert_called_once()
    models["small"].chat.assert_called_once()
    assert Select.calls == (1 if custom else 0)
    assert events[-1].attributes["model_routing"]["final_model"] == "small"
