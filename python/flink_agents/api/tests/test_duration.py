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
"""Shared text cases live in api/src/test/resources/duration-cases.json."""

import json
import re
from datetime import timedelta
from pathlib import Path
from typing import Any

import pytest

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.duration import (
    accepted_retry_wait,
    format_duration,
    is_positive,
    parse_duration,
    positive_seconds,
    truncated_millis,
    zero_if_negative,
)


def _duration_cases_path() -> Path:
    for parent in Path(__file__).resolve().parents:
        candidate = (
            parent / "api" / "src" / "test" / "resources" / "duration-cases.json"
        )
        if candidate.is_file():
            return candidate
    msg = "duration-cases.json is not reachable from the duration tests"
    raise FileNotFoundError(msg)


def _load_duration_cases() -> list[dict[str, Any]]:
    return json.loads(_duration_cases_path().read_text(encoding="utf-8"))


_DURATION_CASES = _load_duration_cases()


@pytest.mark.parametrize("case", _DURATION_CASES, ids=lambda case: repr(case["input"]))
def test_shared_duration_cases(case: dict[str, Any]) -> None:
    text = case["input"]
    if "rejection" in case:
        with pytest.raises(ValueError, match=re.escape(case["rejection"])):
            parse_duration(text)
        return
    parsed = parse_duration(text)
    assert parsed == timedelta(microseconds=case["microseconds"])
    assert format_duration(parsed) == case["canonical"]


def test_rejects_bare_numbers() -> None:
    with pytest.raises(ValueError, match="bare number"):
        parse_duration(30)
    with pytest.raises(ValueError, match="bare number"):
        parse_duration(True)


def test_option_defaults_match_the_disable_sentinels() -> None:
    assert AgentExecutionOptions.RETRY_WAIT_INTERVAL.get_key() == "retry-wait-interval"
    assert AgentExecutionOptions.RETRY_WAIT_INTERVAL.get_default_value() == timedelta(
        seconds=1
    )
    assert AgentExecutionOptions.ASYNC_BATCH_TIMEOUT.get_key() == "async.batch.timeout"
    assert AgentExecutionOptions.ASYNC_BATCH_TIMEOUT.get_default_value() == timedelta(
        milliseconds=-1
    )
    assert is_positive(
        AgentExecutionOptions.ASYNC_BATCH_TIMEOUT.get_default_value()
    ) is (False)
    assert (
        AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL.get_key()
        == "short-term-memory.state-ttl"
    )
    assert AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL.get_default_value() == (
        timedelta(0)
    )
    assert zero_if_negative(timedelta(milliseconds=-1)) == timedelta(0)
    assert zero_if_negative(timedelta(seconds=2)) == timedelta(seconds=2)


# Deadline budget shared with Java DurationUtils.positiveNanos.
# 1_500_000 ns is 0.0015 s.
@pytest.mark.parametrize(
    ("text", "seconds"),
    [
        ("1500 us", 0.0015),
        ("1 ms", 0.001),
        ("1500 ms", 1.5),
        ("1 s", 1.0),
        ("1 min", 60.0),
    ],
)
def test_positive_deadline_keeps_microseconds(text: str, seconds: float) -> None:
    parsed = parse_duration(text)
    assert positive_seconds(parsed) == seconds


def test_non_positive_deadline_contributes_no_seconds() -> None:
    assert positive_seconds(None) is None
    assert positive_seconds(parse_duration("0 ms")) is None
    assert positive_seconds(parse_duration("-1 ms")) is None


def test_retry_wait_truncates_to_milliseconds() -> None:
    assert accepted_retry_wait(parse_duration("1500 us")) == timedelta(milliseconds=1)
    assert truncated_millis(parse_duration("1500 us")) == 1
    assert accepted_retry_wait(parse_duration("500 us")) == timedelta(0)
    assert truncated_millis(parse_duration("500 us")) == 0
    wait = accepted_retry_wait(parse_duration("1500 ms"))
    assert wait == timedelta(milliseconds=1500)
    assert truncated_millis(wait) == 1500
    assert accepted_retry_wait(parse_duration("-1 ms")) == timedelta(0)
    assert accepted_retry_wait(parse_duration("0 ms")) == timedelta(0)
