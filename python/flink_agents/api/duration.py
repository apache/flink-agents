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
"""Parse and format framework durations the same way on the Java side.

A value is an integer and one unit, optionally separated by whitespace, such as
``30 s`` or ``5 min``. A leading ``-`` is allowed. A unit is required; a bare
number is rejected. Recognized units match Flink's duration labels: ``ns``,
``us``/``µs``, ``ms``, ``s``, ``min``/``m``, ``h``, and ``d``, plus the longer
singular and plural forms. ``m`` means minutes.

The value must be a whole number of microseconds, matching ``datetime.timedelta``.
``1000 ns`` is one microsecond; ``1 ns`` is rejected.
"""

from datetime import timedelta
from typing import Any, Iterable, NoReturn

_NANOS_PER_MICRO = 1_000
_NANOS_PER_SECOND = 1_000_000_000

_UNIT_NANOS: dict[str, int] = {}


def _add(nanos: int, *labels: str) -> None:
    for label in labels:
        _UNIT_NANOS[label] = nanos


_add(1, "ns", "nano", "nanos", "nanosecond", "nanoseconds")
_add(1_000, "µs", "μs", "us", "micro", "micros", "microsecond", "microseconds")
_add(1_000_000, "ms", "milli", "millis", "millisecond", "milliseconds")
_add(_NANOS_PER_SECOND, "s", "sec", "secs", "second", "seconds")
_add(60 * _NANOS_PER_SECOND, "min", "m", "mins", "minute", "minutes")
_add(3600 * _NANOS_PER_SECOND, "h", "hour", "hours")
_add(86400 * _NANOS_PER_SECOND, "d", "day", "days")

# Largest unit first. Names match Java DurationFormat's canonical labels.
_FORMAT_UNITS: tuple[tuple[int, str], ...] = (
    (86400 * _NANOS_PER_SECOND, "d"),
    (3600 * _NANOS_PER_SECOND, "h"),
    (60 * _NANOS_PER_SECOND, "min"),
    (_NANOS_PER_SECOND, "s"),
    (1_000_000, "ms"),
    (1_000, "us"),
    (1, "ns"),
)

# Keys removed when duration options dropped their unit suffix.
REMOVED_DURATION_KEYS: dict[str, str] = {
    "async.batch.timeout.ms": "async.batch.timeout",
    "short-term-memory.state-ttl.ms": "short-term-memory.state-ttl",
}


def parse_duration(value: Any) -> timedelta:
    """Parse a duration string or return a timedelta unchanged.

    Raises:
        ValueError: The value is not a duration string or timedelta, has no unit,
            uses an unknown unit, is not a whole number of microseconds, or
            overflows timedelta.
    """
    if isinstance(value, timedelta):
        return value
    if not isinstance(value, str):
        rendered = "null" if value is None else str(value)
        _invalid(
            rendered,
            "a duration needs a number and a unit, for example '30 s' or '5 min',"
            " not a bare number",
        )

    text = value.strip()
    if not text:
        _invalid(value, "a duration needs a number and a unit, for example '30 s'")

    negative = False
    index = 0
    if text[0] == "-":
        negative = True
        index = 1
        while index < len(text) and text[index] == " ":
            index += 1
    number_end = index
    while number_end < len(text) and text[number_end].isdigit():
        number_end += 1
    if number_end == index:
        _invalid(
            value,
            "a duration needs an integer and a unit, for example '30 s' or '5 min'",
        )
    number = text[index:number_end]
    unit_label = text[number_end:].strip().lower()
    if unit_label.startswith("."):
        _invalid(
            value,
            "a duration needs an integer and a unit, for example '1500 ms'",
        )
    if not unit_label:
        _invalid(
            value,
            "a duration needs a unit, for example '30 s' or '30 ms', not a bare number",
        )
    unit_nanos = _UNIT_NANOS.get(unit_label)
    if unit_nanos is None:
        _invalid(
            value,
            f"time unit '{unit_label}' is not recognized."
            " Use ns, us, ms, s, min, h, or d",
        )

    try:
        magnitude = int(number)
    except ValueError:
        _invalid(value, "the number cannot be represented as an integer")
    nanos = magnitude * unit_nanos
    if nanos % _NANOS_PER_MICRO != 0:
        _invalid(value, "durations finer than 1 us are not supported")
    if negative:
        nanos = -nanos
    try:
        return _timedelta_from_nanos(nanos)
    except OverflowError:
        _invalid(value, "the value overflows a duration")


def format_duration(value: timedelta) -> str:
    """Format a timedelta with the largest unit that does not lose precision.

    Zero is ``0 ms``. ``60 s`` is ``1 min``.
    """
    if value < timedelta(0):
        return "-" + format_duration(-value)
    if value == timedelta(0):
        return "0 ms"
    nanos = _nanos_from_timedelta(value)
    for unit_nanos, label in _FORMAT_UNITS:
        if nanos % unit_nanos == 0:
            return f"{nanos // unit_nanos} {label}"
    msg = f"Cannot format duration {value}"
    raise ValueError(msg)


def is_positive(value: timedelta) -> bool:
    """Return whether a duration is strictly greater than zero."""
    return value > timedelta(0)


def zero_if_negative(value: timedelta) -> timedelta:
    """Return zero when ``value`` is negative."""
    if value < timedelta(0):
        return timedelta(0)
    return value


def removed_duration_keys_message(keys: Iterable[str]) -> str | None:
    """Return an error naming removed duration keys, or None when none are present."""
    present = set(keys)
    found = [key for key in sorted(REMOVED_DURATION_KEYS) if key in present]
    if not found:
        return None
    details = ", ".join(f"'{key}' (use '{REMOVED_DURATION_KEYS[key]}')" for key in found)
    return (
        "Unsupported duration configuration keys: "
        f"{details}. Values use a duration such as '30 s' or '5 min'."
    )


def _timedelta_from_nanos(nanos: int) -> timedelta:
    seconds, remainder = divmod(nanos, _NANOS_PER_SECOND)
    # remainder is a multiple of 1000 for every accepted value.
    return timedelta(seconds=seconds, microseconds=remainder // _NANOS_PER_MICRO)


def _nanos_from_timedelta(value: timedelta) -> int:
    return (
        value.days * 86400 * _NANOS_PER_SECOND
        + value.seconds * _NANOS_PER_SECOND
        + value.microseconds * _NANOS_PER_MICRO
    )


def _invalid(value: object, reason: str) -> NoReturn:
    msg = f"Invalid duration '{value}': {reason}."
    raise ValueError(msg)
