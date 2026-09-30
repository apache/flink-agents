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
"""Persisted form of an exception raised by a durable call.

An exception is stored as a marker followed by a pickled dict that keeps the class
name and message next to the pickled exception. Pickle alone is not enough: some
exceptions cannot be pickled, and others pickle but cannot be rebuilt. With the
class name and message stored separately, replay can always raise something that
names the recorded failure.
"""

import logging
from typing import Any

import cloudpickle

logger = logging.getLogger(__name__)

# A pickle stream never starts with these bytes, so a payload without them is one
# written before this format existed: the pickled exception itself.
_MARKER = b"FLINK-AGENTS-EXC\x00"
_VERSION = 1
# The message is stored next to the pickle, which usually holds it too. The copy is
# bounded so a long message does not double the record; the fallback only needs
# enough of it to name the failure.
_MAX_MESSAGE_LENGTH = 16 * 1024
_TRUNCATION_MARK = "... [truncated]"

# java.io.InterruptedIOException is left out on purpose. HTTP stacks raise it for an
# ordinary network timeout, which must stay a recorded failure. Same choice as
# ModelRoutingResolver.isCancellation on the Java side.
_JAVA_INTERRUPTION_CLASS_NAMES = frozenset(
    {
        "java.lang.InterruptedException",
        "java.nio.channels.ClosedByInterruptException",
    }
)
_MAX_DEPTH = 64


def _class_name(exception: BaseException) -> str:
    cls = type(exception)
    return f"{cls.__module__}.{cls.__qualname__}"


def durable_exception_message(exception: BaseException) -> str:
    """Return the message of ``exception``, tolerating a formatter that raises."""
    try:
        return str(exception)
    except Exception:
        return "<message unavailable>"


def _recorded_message(exception: BaseException) -> str:
    message = durable_exception_message(exception)
    if len(message) > _MAX_MESSAGE_LENGTH:
        return message[:_MAX_MESSAGE_LENGTH] + _TRUNCATION_MARK
    return message


def serialize_durable_exception(exception: BaseException) -> bytes:
    """Serialize ``exception`` for the durable call record. Never raises for an
    exception that cannot be pickled; the class name and message are kept instead.
    """
    try:
        pickled = cloudpickle.dumps(exception)
    except Exception:
        logger.debug(
            "Durable call exception %s cannot be pickled; "
            "recording its class name and message only.",
            _class_name(exception),
            exc_info=True,
        )
        pickled = None
    return _MARKER + cloudpickle.dumps(
        {
            "version": _VERSION,
            "class": _class_name(exception),
            "message": _recorded_message(exception),
            "pickle": pickled,
        }
    )


def deserialize_durable_exception(payload: bytes) -> BaseException:
    """Rebuild the exception recorded for a durable call.

    Returns the original exception when it can be rebuilt, and otherwise a
    ``RuntimeError`` carrying the recorded class name and message.
    """
    payload = bytes(payload)
    if not payload.startswith(_MARKER):
        return cloudpickle.loads(payload)

    try:
        record = cloudpickle.loads(payload[len(_MARKER) :])
        version = record["version"]
    except Exception as e:
        msg = "The recorded durable call exception cannot be read."
        raise RuntimeError(msg) from e
    if type(version) is not int or version != _VERSION:
        msg = f"Unsupported durable call exception record version: {version}"
        raise RuntimeError(msg)
    try:
        class_name = record["class"]
        message = record["message"]
        pickled = record["pickle"]
    except Exception as e:
        msg = "The recorded durable call exception is incomplete."
        raise RuntimeError(msg) from e
    if not (
        isinstance(class_name, str)
        and isinstance(message, str)
        and (pickled is None or isinstance(pickled, bytes))
    ):
        msg = "The recorded durable call exception is malformed."
        raise RuntimeError(msg)

    if pickled is not None:
        try:
            exception = cloudpickle.loads(pickled)
        except Exception:
            logger.debug(
                "Recorded durable call exception %s cannot be rebuilt; "
                "raising its class name and message instead.",
                class_name,
                exc_info=True,
            )
        else:
            if isinstance(exception, BaseException):
                return exception
    return RuntimeError(f"{class_name}: {message}")


def _is_pemja_object(obj: Any) -> bool:
    cls = type(obj)
    return cls.__name__ == "PyJObject" and cls.__module__.split(".")[0] == "pemja"


def _java_class_is_interruption(j_class: Any) -> bool:
    depth = 0
    while j_class is not None and depth < _MAX_DEPTH:
        if str(j_class.getName()) in _JAVA_INTERRUPTION_CLASS_NAMES:
            return True
        j_class = j_class.getSuperclass()
        depth += 1
    return False


def _java_throwable_is_interruption(obj: Any) -> bool:
    try:
        depth = 0
        while obj is not None and depth < _MAX_DEPTH and _is_pemja_object(obj):
            if _java_class_is_interruption(obj.getClass()):
                return True
            obj = obj.getCause()
            depth += 1
    except BaseException:
        # Not a throwable, or the Java call failed: nothing to conclude from it.
        # BaseException is caught on purpose, here and in is_java_interruption: the
        # caller is about to record or re-raise a failure, and anything raised while
        # looking at it would replace that failure.
        logger.debug("Failed to inspect a Java object.", exc_info=True)
    return False


def is_java_interruption(exception: BaseException | None) -> bool:
    """Whether ``exception`` carries a Java throwable that is, or was caused by, a
    thread interruption.

    Pemja raises a Java exception in Python as ``RuntimeError`` whose only argument
    is the Java throwable, so the throwable's class and cause chain are checked,
    never the message. Such a failure belongs to a cancelled attempt and must not
    be recorded as the result of the call.

    Only the explicit cause chain (``raise ... from error``) is followed. The
    implicit context is not: a failure raised while an interruption was being
    handled is a failure of its own. An exception that cannot be inspected is not
    an interruption, and the inspection never replaces it with a failure of its
    own.
    """
    try:
        current = exception
        depth = 0
        while current is not None and depth < _MAX_DEPTH:
            if any(_java_throwable_is_interruption(arg) for arg in current.args):
                return True
            current = current.__cause__
            depth += 1
    except BaseException:
        logger.debug("Failed to inspect a durable call exception.", exc_info=True)
    return False
