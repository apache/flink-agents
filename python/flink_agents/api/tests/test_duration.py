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
"""Keep this table aligned with Java DurationFormatTest."""

from datetime import timedelta

import pytest

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.duration import (
    format_duration,
    is_positive,
    parse_duration,
    zero_if_negative,
)


@pytest.mark.parametrize(
    ("text", "expected", "canonical"),
    [
        ("30 s", timedelta(seconds=30), "30 s"),
        ("30s", timedelta(seconds=30), "30 s"),
        ("5 min", timedelta(minutes=5), "5 min"),
        ("5m", timedelta(minutes=5), "5 min"),
        ("1 h", timedelta(hours=1), "1 h"),
        ("1 d", timedelta(days=1), "1 d"),
        ("1500 ms", timedelta(milliseconds=1500), "1500 ms"),
        ("60 s", timedelta(seconds=60), "1 min"),
        ("1000 ns", timedelta(microseconds=1), "1 us"),
        ("1 us", timedelta(microseconds=1), "1 us"),
        ("1 µs", timedelta(microseconds=1), "1 us"),
        ("0 ms", timedelta(0), "0 ms"),
        ("0 s", timedelta(0), "0 ms"),
        ("-1 ms", timedelta(milliseconds=-1), "-1 ms"),
        ("-1ms", timedelta(milliseconds=-1), "-1 ms"),
        ("2 seconds", timedelta(seconds=2), "2 s"),
        ("1 millisecond", timedelta(milliseconds=1), "1 ms"),
    ],
)
def test_parse_and_format(text: str, expected: timedelta, canonical: str) -> None:
    parsed = parse_duration(text)
    assert parsed == expected
    assert format_duration(parsed) == canonical
    assert parse_duration(parsed) == parsed


@pytest.mark.parametrize(
    ("text", "reason"),
    [
        ("30", "bare number"),
        ("1.5 s", "an integer"),
        ("1 fortnight", "not recognized"),
        ("1 ns", "finer than 1 us"),
        ("", "a number and a unit"),
        ("-", "an integer"),
    ],
)
def test_rejects_unusable_text(text: str, reason: str) -> None:
    with pytest.raises(ValueError, match=reason):
        parse_duration(text)


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
    assert is_positive(AgentExecutionOptions.ASYNC_BATCH_TIMEOUT.get_default_value()) is (
        False
    )
    assert (
        AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL.get_key()
        == "short-term-memory.state-ttl"
    )
    assert AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL.get_default_value() == (
        timedelta(0)
    )
    assert zero_if_negative(timedelta(milliseconds=-1)) == timedelta(0)
    assert zero_if_negative(timedelta(seconds=2)) == timedelta(seconds=2)
