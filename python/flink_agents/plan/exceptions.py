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
"""Internal framework faults shared by plan and runtime execution."""

import traceback
from collections.abc import Callable, Mapping
from typing import Any

_MAX_CAUSE_DEPTH = 64
_JAVA_FRAMEWORK_FAULT = "org.apache.flink.agents.plan.exceptions.UncapturableException"
_JAVA_THROWABLE = frozenset({"java.lang.Throwable"})


class UncapturableException(RuntimeError):
    """A framework fault that must not become a response or a durable outcome."""


def _is_pemja_object(value: Any) -> bool:
    cls = type(value)
    return cls.__name__ == "PyJObject" and cls.__module__.split(".")[0] == "pemja"


def _java_class_matches(java_class: Any, names: frozenset[str]) -> bool:
    for _ in range(_MAX_CAUSE_DEPTH):
        if java_class is None:
            return False
        if str(java_class.getName()) in names:
            return True
        java_class = java_class.getSuperclass()
    return False


def _java_throwable_matches(value: Any, names: frozenset[str]) -> bool:
    try:
        for _ in range(_MAX_CAUSE_DEPTH):
            if value is None or not _is_pemja_object(value):
                return False
            if _java_class_matches(value.getClass(), names):
                return True
            value = value.getCause()
    except BaseException:
        return False
    return False


def find_java_exception(
    exception: BaseException | None, class_names: frozenset[str]
) -> Any | None:
    """Find a Java throwable carried by Pemja, preserving its outer cause chain.

    Inspect actual classes and explicit causes. Messages and implicit Python
    contexts do not classify a failure. Inspection must not replace the failure.
    """
    try:
        current = exception
        for _ in range(_MAX_CAUSE_DEPTH):
            if current is None:
                break
            for arg in current.args:
                if _java_throwable_matches(arg, class_names):
                    return arg
            current = current.__cause__
    except BaseException:
        return None
    return None


def is_uncapturable(exception: BaseException | None) -> bool:
    """Recognize a native Python marker or a Java marker through explicit causes."""
    try:
        current = exception
        for _ in range(_MAX_CAUSE_DEPTH):
            if current is None:
                break
            if isinstance(current, UncapturableException):
                return True
            current = current.__cause__
    except BaseException:
        return False
    return (
        find_java_exception(exception, frozenset({_JAVA_FRAMEWORK_FAULT})) is not None
    )


def raise_if_uncapturable(exception: BaseException | None) -> None:
    """Keep a marked fault out of an application failure conversion or recording."""
    if is_uncapturable(exception):
        raise exception


def is_retryable_http_status(status: int) -> bool:
    """Return whether an HTTP status should consume the retry budget."""
    if status in (408, 409, 429):
        return True
    return status < 400 or status >= 500


def linked_failure(error: BaseException) -> BaseException | None:
    """Return the next failure in the Python exception chain.

    An explicit cause wins. Otherwise use the implicit context.
    """
    if error.__cause__ is not None:
        return error.__cause__
    if error.__suppress_context__:
        return None
    context = error.__context__
    return None if context is error else context


def _python_http_status(error: BaseException) -> int | None:
    for name in ("status_code", "status"):
        value = getattr(error, name, None)
        if isinstance(value, int) and not isinstance(value, bool):
            return value
    response = getattr(error, "response", None)
    if response is not None:
        value = getattr(response, "status_code", None)
        if isinstance(value, int) and not isinstance(value, bool):
            return value
    return None


def _java_single_http_status(throwable: Any) -> int | None:
    for name in ("statusCode", "getStatusCode", "code"):
        try:
            accessor = getattr(throwable, name)
            value = accessor()
        except BaseException:
            continue
        if isinstance(value, int) and not isinstance(value, bool):
            return value
    return None


def _java_chain_http_status(value: Any) -> int | None:
    first_retryable: int | None = None
    try:
        current = value
        for _ in range(_MAX_CAUSE_DEPTH):
            if current is None or not _is_pemja_object(current):
                break
            if not _java_class_matches(current.getClass(), _JAVA_THROWABLE):
                break
            status = _java_single_http_status(current)
            if status is not None:
                if not is_retryable_http_status(status):
                    return status
                if first_retryable is None:
                    first_retryable = status
            current = current.getCause()
    except BaseException:
        return first_retryable
    return first_retryable


def effective_http_status(error: BaseException) -> int | None:
    """Extract the governing HTTP status from Python causes or bridged Java throwables.

    Any non-retryable status in the chain wins over a retryable status.
    """
    first_retryable: int | None = None
    current: BaseException | None = error
    seen: set[int] = set()
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        status = _python_http_status(current)
        if status is not None:
            if not is_retryable_http_status(status):
                return status
            if first_retryable is None:
                first_retryable = status
        for arg in getattr(current, "args", ()):
            java_status = _java_chain_http_status(arg)
            if java_status is not None:
                if not is_retryable_http_status(java_status):
                    return java_status
                if first_retryable is None:
                    first_retryable = java_status
        current = linked_failure(current)
    return first_retryable


def _message(exception: BaseException) -> str:
    try:
        return str(exception)
    except Exception:
        return "<message unavailable>"


def _trace(exception: BaseException) -> str:
    try:
        return "".join(traceback.format_exception(exception))
    except Exception:
        return "<traceback unavailable>"


def capture_framework_fault(function: Callable, args: tuple) -> tuple:
    """Encode marked faults before Pemja loses their class and cause.

    Successful values have an outer (True, value) tuple, so user data cannot be
    mistaken for a fault. Ordinary application exceptions keep Pemja's existing
    exception handling. Java faults travel as their original throwable objects.
    """
    try:
        value = function(*args)
    except Exception as error:
        if not is_uncapturable(error):
            raise
        java_error = find_java_exception(error, frozenset({_JAVA_FRAMEWORK_FAULT}))
        direct_java = (
            java_error is not None
            and len(error.args) == 1
            and error.args[0] is java_error
            and error.__cause__ is None
        )
        cls = type(error)
        return (
            False,
            java_error,
            f"{cls.__module__}.{cls.__qualname__}",
            _message(error),
            None if direct_java else _trace(error),
        )
    return True, value


def invoke_with_framework_faults(
    namespace: Mapping[str, Any], name: str, *args: Any
) -> tuple:
    """Invoke an entry point in its owning interpreter's namespace."""
    parts = name.split(".")
    function = namespace[parts[0]]
    for part in parts[1:]:
        function = getattr(function, part)
    return capture_framework_fault(function, args)
