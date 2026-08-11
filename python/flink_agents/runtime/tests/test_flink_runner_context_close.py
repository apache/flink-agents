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
from unittest.mock import MagicMock

import pytest

from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


def _create_context() -> tuple[FlinkRunnerContext, MagicMock, MagicMock]:
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ltm = MagicMock()
    resource_cache = MagicMock()
    ctx._FlinkRunnerContext__ltm = ltm
    ctx._FlinkRunnerContext__resource_cache = resource_cache
    return ctx, ltm, resource_cache


def test_close_releases_long_term_memory_and_resource_cache_once() -> None:
    ctx, ltm, resource_cache = _create_context()

    ctx.close()
    ctx.close()

    ltm.close.assert_called_once_with()
    resource_cache.close.assert_called_once_with()
    assert ctx.long_term_memory is None


def test_close_clears_long_term_memory_before_logical_cleanup() -> None:
    ctx, ltm, resource_cache = _create_context()
    ltm.close.side_effect = RuntimeError("logical close failed")

    with pytest.raises(RuntimeError, match="logical close failed"):
        ctx.close()

    assert ctx.long_term_memory is None
    resource_cache.close.assert_called_once_with()
    ctx.close()
    ltm.close.assert_called_once_with()
    resource_cache.close.assert_called_once_with()


def test_close_preserves_first_failure_when_both_cleanups_fail() -> None:
    ctx, ltm, resource_cache = _create_context()
    ltm_failure = RuntimeError("logical close failed")
    resource_cache_failure = RuntimeError("resource cache close failed")
    ltm.close.side_effect = ltm_failure
    resource_cache.close.side_effect = resource_cache_failure

    with pytest.raises(RuntimeError, match="logical close failed") as exc_info:
        ctx.close()

    assert exc_info.value is ltm_failure
    assert exc_info.value.__context__ is resource_cache_failure

    ctx.close()
    ltm.close.assert_called_once_with()
    resource_cache.close.assert_called_once_with()
