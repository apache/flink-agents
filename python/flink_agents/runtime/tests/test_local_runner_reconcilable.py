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
import asyncio

from flink_agents.runtime.local_runner import LocalRunnerContext


def reconciled_add(x: int, y: int) -> int:
    return x + y


def _create_local_runner_context() -> LocalRunnerContext:
    return LocalRunnerContext.__new__(LocalRunnerContext)


def test_local_runner_context_reconcile_durable_execute_degrades() -> None:
    ctx = _create_local_runner_context()
    reconcile_called = False

    def reconcile() -> int:
        nonlocal reconcile_called
        reconcile_called = True
        return 999

    result = ctx.durable_execute(reconciled_add, 5, 10, reconcile=reconcile)

    assert result == 15
    assert reconcile_called is False


def test_local_runner_context_reconcile_durable_execute_async_degrades() -> None:
    ctx = _create_local_runner_context()
    reconcile_called = False

    def reconcile() -> int:
        nonlocal reconcile_called
        reconcile_called = True
        return 999

    async_result = ctx.durable_execute_async(reconciled_add, 5, 10, reconcile=reconcile)

    async def _await_result():
        return await async_result

    assert asyncio.run(_await_result()) == 15
    assert reconcile_called is False


def test_local_runner_context_reconcile_kwarg_is_not_forwarded() -> None:
    ctx = _create_local_runner_context()

    def collect_kwargs(**kwargs):
        return kwargs

    result = ctx.durable_execute(collect_kwargs, reconcile=lambda: "unused")

    assert result == {}
