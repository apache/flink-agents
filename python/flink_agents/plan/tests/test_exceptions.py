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
"""Framework faults retain their classification without classifying user data."""

import asyncio
from concurrent.futures import CancelledError

import pytest

from flink_agents.plan.exceptions import (
    UncapturableException,
    is_cancellation,
    is_uncapturable,
    raise_if_framework_fault_or_cancellation,
    raise_if_uncapturable,
)


def test_explicit_wrappers_keep_the_fault_but_messages_do_not():
    fault = UncapturableException("state write failed")
    wrapped = RuntimeError("outer")
    wrapped.__cause__ = fault
    assert is_uncapturable(wrapped)
    with pytest.raises(RuntimeError) as raised:
        raise_if_uncapturable(wrapped)
    assert raised.value is wrapped
    assert not is_uncapturable(
        RuntimeError("UncapturableException: state write failed")
    )
    implicit = ValueError("separate failure")
    implicit.__context__ = fault
    assert not is_uncapturable(implicit)


@pytest.mark.parametrize(
    "error_type", [InterruptedError, CancelledError, asyncio.CancelledError]
)
def test_cancellation_preserves_explicit_wrappers(error_type):
    cancellation = error_type("cancelled")
    wrapped = RuntimeError("call failed")
    wrapped.__cause__ = cancellation

    for error in (cancellation, wrapped):
        assert is_cancellation(error)
        with pytest.raises(type(error)) as raised:
            raise_if_framework_fault_or_cancellation(error)
        assert raised.value is error

    implicit = ValueError("fallback failed")
    implicit.__context__ = cancellation
    assert not is_cancellation(implicit)


@pytest.mark.parametrize(
    "error", [TimeoutError("timed out"), RuntimeError("InterruptedError"), None]
)
def test_ordinary_failures_are_not_cancellation(error):
    assert not is_cancellation(error)
    raise_if_framework_fault_or_cancellation(error)
