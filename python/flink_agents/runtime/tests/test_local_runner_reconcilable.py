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
from typing import Any

from flink_agents.runtime.local_runner import LocalRunnerContext


def reconciled_add(x: int, y: int) -> int:
    """Return a simple deterministic value for local-runner tests."""
    return x + y


def _create_local_runner_context() -> LocalRunnerContext:
    return LocalRunnerContext.__new__(LocalRunnerContext)


def test_local_runner_context_reconciler_durable_execute_degrades() -> None:
    """Keep sync local execution on the existing non-durable path."""
    ctx = _create_local_runner_context()
    reconciler_called = False

    def reconciler() -> int:
        nonlocal reconciler_called
        reconciler_called = True
        return 999

    result = ctx.durable_execute(reconciled_add, 5, 10, reconciler=reconciler)

    assert result == 15
    assert reconciler_called is False


def test_local_runner_context_reconciler_durable_execute_async_degrades() -> None:
    """Keep async local execution on the existing non-durable path."""
    ctx = _create_local_runner_context()
    reconciler_called = False

    def reconciler() -> int:
        nonlocal reconciler_called
        reconciler_called = True
        return 999

    async_result = ctx.durable_execute_async(
        reconciled_add, 5, 10, reconciler=reconciler
    )

    async def _await_result() -> Any:
        return await async_result

    assert asyncio.run(_await_result()) == 15
    assert reconciler_called is False


def test_local_runner_context_reconciler_kwarg_is_not_forwarded() -> None:
    """Do not forward the reserved reconciler kwarg to the user function."""
    ctx = _create_local_runner_context()

    def collect_kwargs(**kwargs: Any) -> dict[str, Any]:
        return kwargs

    result = ctx.durable_execute(collect_kwargs, reconciler=lambda: "unused")

    assert result == {}
