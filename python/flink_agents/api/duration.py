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
"""Parse, format, and check framework durations the same way on the Java side.

Text follows Flink ``TimeUtils``: an integer and one unit, such as ``30 s`` or
``5 min``; a unitless integer, which means milliseconds, such as ``1000``; or an
ISO-8601 duration, such as ``PT30S``, ``PT1H30M``, or ``P1DT2H``. A leading ``-``
is allowed on every form. Recognized units match Flink's duration labels and the
extra labels this project already accepted: ``ns``, ``us``/``µs``, ``ms``, ``s``,
``min``/``m``, ``h``, and ``d``, plus the longer singular and plural forms.
``m`` means minutes.

The value must be a whole number of microseconds, matching ``datetime.timedelta``.
``1000 ns`` is one microsecond; ``1 ns`` is rejected. An ISO-8601 value that is
not microsecond-aligned is rejected the same way. This parser does not truncate
to a coarser unit. Retry wait and short-term memory TTL apply millisecond
truncation later, when the job is submitted.
"""

import re
from datetime import timedelta
from typing import Any, NoReturn

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

# Java Duration.parse, including a case-insensitive designator. Group 3 is the
# whole "T..." section; a section of length 1 is a "T" with no time fields.
_ISO_DURATION = re.compile(
    r"([-+]?)P(?:([-+]?\d+)D)?"
    r"(T(?:([-+]?\d+)H)?(?:([-+]?\d+)M)?"
    r"(?:([-+]?\d+)(?:[.,](\d{0,9}))?S)?)?",
    re.IGNORECASE,
)

# Largest unit first. Names match Java DurationUtils's canonical labels.
_FORMAT_UNITS: tuple[tuple[int, str], ...] = (
    (86400 * _NANOS_PER_SECOND, "d"),
    (3600 * _NANOS_PER_SECOND, "h"),
    (60 * _NANOS_PER_SECOND, "min"),
    (_NANOS_PER_SECOND, "s"),
    (1_000_000, "ms"),
    (1_000, "us"),
    (1, "ns"),
)


def parse_duration(value: Any) -> timedelta:
    """Parse a duration string or return a timedelta unchanged.

    Raises:
        ValueError: The value is not a duration string or timedelta, uses an
            unknown unit, is not a valid ISO-8601 duration, is not a whole number
            of microseconds, or overflows timedelta.
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
    if _starts_with_magnitude(text):
        return _parse_labeled(text, value)
    return _parse_iso8601(text, value)


def _starts_with_magnitude(text: str) -> bool:
    """True for a labeled duration or a unitless millisecond value."""
    index = 0
    if text[0] == "-":
        index = 1
        while index < len(text) and text[index] == " ":
            index += 1
    return index < len(text) and text[index].isdigit()


def _parse_labeled(text: str, original: str) -> timedelta:
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
    number = text[index:number_end]
    unit_label = text[number_end:].strip().lower()
    if unit_label.startswith("."):
        _invalid(
            original,
            "a duration needs an integer and a unit, for example '1500 ms'",
        )
    if not unit_label:
        unit_nanos = 1_000_000
    else:
        unit_nanos = _UNIT_NANOS.get(unit_label)
        if unit_nanos is None:
            _invalid(
                original,
                f"time unit '{unit_label}' is not recognized."
                " Use ns, us, ms, s, min, h, or d",
            )

    try:
        magnitude = int(number)
    except ValueError:
        _invalid(original, "the number cannot be represented as an integer")
    nanos = magnitude * unit_nanos
    if nanos % _NANOS_PER_MICRO != 0:
        _invalid(original, "durations finer than 1 us are not supported")
    if negative:
        nanos = -nanos
    try:
        return _timedelta_from_nanos(nanos)
    except OverflowError:
        _invalid(original, "the value overflows a duration")


def _parse_iso8601(text: str, original: str) -> timedelta:
    """ISO-8601 fallback. Negative durations are kept; Flink TimeUtils rejects them."""
    if _is_bare_sign(text):
        _invalid(
            original,
            "a duration needs an integer and a unit, for example '30 s' or '5 min'",
        )
    match = _ISO_DURATION.fullmatch(text)
    if (
        match is None
        or _iso_t_without_time(match)
        or (
            match.group(2) is None
            and match.group(4) is None
            and match.group(5) is None
            and match.group(6) is None
        )
    ):
        _invalid(
            original,
            "text does not start with a number, and is not a valid ISO-8601"
            " duration format",
        )
    sign, days, _tsection, hours, minutes, seconds, fraction = match.groups()
    try:
        total = 0
        if days is not None:
            total += int(days) * 86400
        if hours is not None:
            total += int(hours) * 3600
        if minutes is not None:
            total += int(minutes) * 60
        if seconds is not None:
            total += int(seconds)
        nanos = 0
        if fraction:
            frac = int(fraction) * 10 ** (9 - len(fraction))
            if seconds is not None and seconds.startswith("-"):
                frac = -frac
            nanos = frac
        if sign == "-":
            total = -total
            nanos = -nanos
        # Match Duration.ofSeconds: floor-divide the nanosecond adjustment.
        total += nanos // _NANOS_PER_SECOND
        nanos %= _NANOS_PER_SECOND
        if nanos % _NANOS_PER_MICRO != 0:
            _invalid(original, "durations finer than 1 us are not supported")
        return timedelta(seconds=total, microseconds=nanos // _NANOS_PER_MICRO)
    except OverflowError:
        _invalid(original, "the value overflows a duration")


def _iso_t_without_time(match: re.Match[str]) -> bool:
    tsection = match.group(3)
    return tsection is not None and len(tsection) == 1


def _is_bare_sign(text: str) -> bool:
    index = 0
    if text[0] == "-":
        index = 1
        while index < len(text) and text[index] == " ":
            index += 1
    if index >= len(text):
        return True
    return text[0] == "-" and "p" not in text.lower()


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


def positive_seconds(value: timedelta | None) -> float | None:
    """Seconds in a positive duration, or None when it does not set a deadline.

    The full microsecond duration is kept. Java's matching budget is
    ``DurationUtils.positiveNanos``.
    """
    if value is None or not is_positive(value):
        return None
    return value.total_seconds()


def truncated_millis(value: timedelta) -> int:
    """Whole milliseconds, truncated toward zero, matching ``Duration.toMillis``."""
    if value < timedelta(0):
        return -((-value) // timedelta(milliseconds=1))
    return value // timedelta(milliseconds=1)


def accepted_retry_wait(value: timedelta) -> timedelta:
    """Retry backoff in whole milliseconds.

    A negative duration waits zero. A positive duration is truncated toward
    zero, and a truncated result of zero does not wait.
    """
    non_negative = zero_if_negative(value)
    if not is_positive(non_negative):
        return timedelta(0)
    return timedelta(milliseconds=truncated_millis(non_negative))


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
