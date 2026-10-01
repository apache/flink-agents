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
import threading
from typing import Any

import cloudpickle
import pytest

from flink_agents.runtime.durable_exception import (
    deserialize_durable_exception,
    is_java_interruption,
    serialize_durable_exception,
)
from flink_agents.runtime.tests.test_flink_runner_context_reconcilable import (
    _close_runner_context,
    _create_runner_context,
    _FakeJavaRunnerContext,
    _preload_pending,
    _run_async,
)


class _KeywordOnlyError(Exception):
    """Pickles, but cannot be rebuilt: unpickling calls ``cls(*args)``."""

    def __init__(self, message: str, *, status: int) -> None:
        super().__init__(message)
        self.status = status


class _UnpicklableError(Exception):
    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.lock = threading.Lock()


_KEYWORD_ONLY_NAME = f"{__name__}._KeywordOnlyError"
_UNPICKLABLE_NAME = f"{__name__}._UnpicklableError"


def _refuse_pickling(self: Any) -> None:
    msg = "cannot pickle 'pemja.PyJObject' object"
    raise TypeError(msg)


def _fake_java_throwable(class_name: str, cause: Any = None) -> Any:
    """Build an object shaped like the ``pemja.PyJObject`` of a Java throwable: it
    prints as the Java throwable does and cannot be pickled.
    """

    def j_class(name: str | None) -> Any:
        if name is None:
            return None
        parent = "java.lang.Exception" if name != "java.lang.Exception" else None
        return type(
            "PyJObject",
            (),
            {
                "__module__": "pemja",
                "getName": lambda self: name,
                "getSuperclass": lambda self: j_class(parent),
            },
        )()

    return type(
        "PyJObject",
        (),
        {
            "__module__": "pemja",
            "__reduce_ex__": lambda self, protocol: _refuse_pickling(self),
            "__str__": lambda self: f"{class_name}: call failed",
            "getClass": lambda self: j_class(class_name),
            "getCause": lambda self: cause,
        },
    )()


_INTERRUPTED = "java.lang.InterruptedException"
_CLOSED_BY_INTERRUPT = "java.nio.channels.ClosedByInterruptException"
_INTERRUPTED_IO = "java.io.InterruptedIOException"
_ILLEGAL_STATE = "java.lang.IllegalStateException"


def _java_error(class_name: str, cause: Any = None) -> RuntimeError:
    return RuntimeError(_fake_java_throwable(class_name, cause))


def _execute(ctx: Any, asynchronous: bool, func: Any, **kwargs: Any) -> Any:
    if asynchronous:
        return _run_async(ctx.durable_execute_async(func, **kwargs))
    return ctx.durable_execute(func, **kwargs)


def _run_twice(
    store: _FakeJavaRunnerContext, asynchronous: bool, func: Any, **kwargs: Any
) -> list[BaseException]:
    """Execute the call, then replay it in a new context over the same store."""
    raised = []
    for _ in range(2):
        store.current_call_index = 0
        ctx = _create_runner_context(store)
        try:
            with pytest.raises(BaseException) as error:
                _execute(ctx, asynchronous, func, **kwargs)
            raised.append(error.value)
        finally:
            _close_runner_context(ctx)
    return raised


def test_picklable_exception_round_trips_with_its_type() -> None:
    restored = deserialize_durable_exception(
        serialize_durable_exception(ValueError("boom"))
    )

    assert type(restored) is ValueError
    assert restored.args == ("boom",)


def test_exception_that_cannot_be_rebuilt_falls_back_to_class_and_message() -> None:
    restored = deserialize_durable_exception(
        serialize_durable_exception(_KeywordOnlyError("rate limited", status=429))
    )

    assert type(restored) is RuntimeError
    assert str(restored) == f"{_KEYWORD_ONLY_NAME}: rate limited"


def test_exception_that_cannot_be_pickled_falls_back_to_class_and_message() -> None:
    restored = deserialize_durable_exception(
        serialize_durable_exception(_UnpicklableError("payment declined"))
    )

    assert type(restored) is RuntimeError
    assert str(restored) == f"{_UNPICKLABLE_NAME}: payment declined"


def test_payload_written_before_the_envelope_still_loads() -> None:
    restored = deserialize_durable_exception(cloudpickle.dumps(ValueError("old")))

    assert type(restored) is ValueError
    assert restored.args == ("old",)


@pytest.mark.parametrize("asynchronous", [False, True])
def test_replay_of_exception_that_cannot_be_rebuilt(asynchronous: bool) -> None:
    calls = []

    def call() -> None:
        calls.append(1)
        msg = "rate limited"
        raise _KeywordOnlyError(msg, status=429)

    store = _FakeJavaRunnerContext()
    first, replayed = _run_twice(store, asynchronous, call)

    assert type(first) is _KeywordOnlyError
    assert type(replayed) is RuntimeError
    assert str(replayed) == f"{_KEYWORD_ONLY_NAME}: rate limited"
    assert len(calls) == 1
    assert [slot.status for slot in store.call_results] == ["FAILED"]


def test_gather_replays_exception_that_cannot_be_rebuilt() -> None:
    calls = []

    def call() -> None:
        calls.append(1)
        msg = "rate limited"
        raise _KeywordOnlyError(msg, status=429)

    store = _FakeJavaRunnerContext()
    errors = []
    for _ in range(2):
        store.current_call_index = 0
        ctx = _create_runner_context(store)
        try:
            outcomes = _run_async(ctx.gather(ctx.durable_execute_async(call)))
            errors.append(outcomes[0].error)
        finally:
            _close_runner_context(ctx)

    assert type(errors[0]) is _KeywordOnlyError
    assert type(errors[1]) is RuntimeError
    assert str(errors[1]) == f"{_KEYWORD_ONLY_NAME}: rate limited"
    assert len(calls) == 1


@pytest.mark.parametrize("asynchronous", [False, True])
def test_exception_that_cannot_be_pickled_is_recorded(asynchronous: bool) -> None:
    calls = []

    def call() -> None:
        calls.append(1)
        msg = "payment declined"
        raise _UnpicklableError(msg)

    store = _FakeJavaRunnerContext()
    first, replayed = _run_twice(store, asynchronous, call)

    assert type(first) is _UnpicklableError
    assert type(replayed) is RuntimeError
    assert str(replayed) == f"{_UNPICKLABLE_NAME}: payment declined"
    assert len(calls) == 1
    assert [slot.status for slot in store.call_results] == ["FAILED"]


@pytest.mark.parametrize("asynchronous", [False, True])
@pytest.mark.parametrize("use_reconciler", [False, True])
def test_pending_slot_keeps_exception_that_cannot_be_pickled(
    use_reconciler: bool, asynchronous: bool
) -> None:
    def call() -> None:
        msg = "payment declined"
        raise _UnpicklableError(msg)

    def reconciler() -> None:
        msg = "payment declined"
        raise _UnpicklableError(msg)

    store = _FakeJavaRunnerContext()
    _preload_pending(store, call)
    ctx = _create_runner_context(store)
    kwargs = {"reconciler": reconciler} if use_reconciler else {}
    try:
        with pytest.raises(_UnpicklableError, match="payment declined"):
            _execute(ctx, asynchronous, call, **kwargs)
    finally:
        _close_runner_context(ctx)

    assert [slot.status for slot in store.call_results] == ["FAILED"]
    restored = deserialize_durable_exception(store.call_results[0].exception_payload)
    assert str(restored) == f"{_UNPICKLABLE_NAME}: payment declined"


@pytest.mark.parametrize(
    "error",
    [
        _java_error(_INTERRUPTED),
        _java_error(_CLOSED_BY_INTERRUPT),
        _java_error("java.lang.RuntimeException", _fake_java_throwable(_INTERRUPTED)),
    ],
    ids=["direct", "closed-by-interrupt", "wrapped"],
)
def test_java_interruption_is_recognized(error: RuntimeError) -> None:
    wrapped = ConnectionError("call failed")
    wrapped.__cause__ = error

    assert is_java_interruption(error)
    assert is_java_interruption(wrapped)


@pytest.mark.parametrize(
    "error",
    [
        _java_error(_ILLEGAL_STATE),
        # An ordinary network timeout is an InterruptedIOException too.
        _java_error(_INTERRUPTED_IO),
        RuntimeError("java.lang.InterruptedException: sleep interrupted"),
        ValueError("boom"),
        None,
    ],
    ids=["other-java", "interrupted-io", "message-only", "python", "none"],
)
def test_other_failures_are_not_java_interruptions(error: Any) -> None:
    assert not is_java_interruption(error)


def test_java_interruption_is_not_recorded() -> None:
    calls = []

    def call() -> str:
        calls.append(1)
        if len(calls) == 1:
            raise _java_error(_INTERRUPTED)
        return "done"

    store = _FakeJavaRunnerContext()
    ctx = _create_runner_context(store)
    try:
        with pytest.raises(RuntimeError):
            ctx.durable_execute(call)
    finally:
        _close_runner_context(ctx)
    assert store.call_results == []

    store.current_call_index = 0
    ctx = _create_runner_context(store)
    try:
        assert ctx.durable_execute(call) == "done"
    finally:
        _close_runner_context(ctx)
    assert len(calls) == 2
    assert [slot.status for slot in store.call_results] == ["SUCCEEDED"]


@pytest.mark.parametrize("use_reconciler", [False, True])
def test_java_interruption_leaves_pending_slot(use_reconciler: bool) -> None:
    def call() -> None:
        raise _java_error(_INTERRUPTED)

    store = _FakeJavaRunnerContext()
    _preload_pending(store, call)
    ctx = _create_runner_context(store)
    kwargs = {"reconciler": call} if use_reconciler else {}
    try:
        with pytest.raises(RuntimeError):
            ctx.durable_execute(call, **kwargs)
    finally:
        _close_runner_context(ctx)

    assert [slot.status for slot in store.call_results] == ["PENDING"]
    assert store.current_call_index == 0


def test_other_java_failure_is_recorded() -> None:
    calls = []

    def call() -> None:
        calls.append(1)
        raise _java_error(_ILLEGAL_STATE)

    store = _FakeJavaRunnerContext()
    first, replayed = _run_twice(store, False, call)

    assert type(first) is RuntimeError
    assert type(replayed) is RuntimeError
    assert str(replayed) == f"builtins.RuntimeError: {_ILLEGAL_STATE}: call failed"
    assert len(calls) == 1
    assert [slot.status for slot in store.call_results] == ["FAILED"]


class _UnprintableError(Exception):
    def __str__(self) -> str:
        msg = "formatter failed"
        raise ValueError(msg)


def test_exception_whose_message_cannot_be_formatted_round_trips() -> None:
    restored = deserialize_durable_exception(
        serialize_durable_exception(_UnprintableError())
    )

    assert type(restored) is _UnprintableError


@pytest.mark.parametrize("pending", [False, True])
def test_exception_whose_message_cannot_be_formatted_is_recorded(
    pending: bool,
) -> None:
    def call() -> None:
        raise _UnprintableError

    store = _FakeJavaRunnerContext()
    if pending:
        _preload_pending(store, call)
    ctx = _create_runner_context(store)
    try:
        with pytest.raises(_UnprintableError):
            ctx.durable_execute(call)
    finally:
        _close_runner_context(ctx)

    assert [slot.status for slot in store.call_results] == ["FAILED"]


def test_non_ascii_message_survives_the_fallback() -> None:
    restored = deserialize_durable_exception(
        serialize_durable_exception(_UnpicklableError("결제 거절: café"))
    )

    assert str(restored) == f"{_UNPICKLABLE_NAME}: 결제 거절: café"


@pytest.mark.parametrize("protocol", range(cloudpickle.pickle.HIGHEST_PROTOCOL + 1))
def test_payload_written_with_any_pickle_protocol_is_read_as_legacy(
    protocol: int,
) -> None:
    payload = cloudpickle.dumps(ValueError("old"), protocol=protocol)

    restored = deserialize_durable_exception(payload)

    assert type(restored) is ValueError


@pytest.mark.parametrize("wrapper", [bytes, bytearray, memoryview])
def test_payload_is_read_from_any_bytes_like_object(wrapper: Any) -> None:
    payload = wrapper(serialize_durable_exception(ValueError("boom")))

    assert type(deserialize_durable_exception(payload)) is ValueError


@pytest.mark.parametrize(
    "payload",
    [
        b"FLINK-AGENTS-EXC\x00not a pickle",
        b"FLINK-AGENTS-EXC\x00" + cloudpickle.dumps(["not", "a", "record"]),
        b"FLINK-AGENTS-EXC\x00" + cloudpickle.dumps({"version": 99}),
        b"FLINK-AGENTS-EXC\x00" + cloudpickle.dumps({"version": 1}),
        b"FLINK-AGENTS-EXC\x00"
        + cloudpickle.dumps(
            {"version": 1, "class": None, "message": None, "pickle": None}
        ),
        b"FLINK-AGENTS-EXC\x00"
        + cloudpickle.dumps(
            {"version": True, "class": "E", "message": "m", "pickle": None}
        ),
    ],
    ids=[
        "corrupt",
        "not-a-record",
        "unknown-version",
        "incomplete",
        "malformed",
        "boolean-version",
    ],
)
def test_unreadable_record_is_reported(payload: bytes) -> None:
    with pytest.raises(RuntimeError, match="durable call exception"):
        deserialize_durable_exception(payload)


def test_failure_raised_while_handling_an_interruption_is_not_one() -> None:
    try:
        try:
            raise _java_error(_INTERRUPTED)
        except RuntimeError:
            msg = "declined"
            raise ValueError(msg)  # noqa: B904
    except ValueError as error:
        failure = error

    assert failure.__context__ is not None
    assert not is_java_interruption(failure)


def test_interruption_is_found_past_an_argument_that_cannot_be_inspected() -> None:
    not_a_throwable = type("PyJObject", (), {"__module__": "pemja"})()
    error = ConnectionError(not_a_throwable)
    error.__cause__ = _java_error(_INTERRUPTED)

    assert is_java_interruption(error)


@pytest.mark.parametrize("use_reconciler", [False, True])
def test_java_interruption_is_not_recorded_by_async_call(
    use_reconciler: bool,
) -> None:
    def call() -> None:
        raise _java_error(_INTERRUPTED)

    store = _FakeJavaRunnerContext()
    ctx = _create_runner_context(store)
    kwargs = {"reconciler": call} if use_reconciler else {}
    try:
        with pytest.raises(RuntimeError):
            _run_async(ctx.durable_execute_async(call, **kwargs))
    finally:
        _close_runner_context(ctx)

    expected = ["PENDING"] if use_reconciler else []
    assert [slot.status for slot in store.call_results] == expected
    assert store.current_call_index == 0


@pytest.mark.parametrize("asynchronous", [False, True])
@pytest.mark.parametrize("use_reconciler", [False, True])
def test_java_interruption_leaves_preloaded_pending_slot(
    use_reconciler: bool, asynchronous: bool
) -> None:
    interruption = _java_error(_INTERRUPTED)

    def call() -> None:
        raise interruption

    store = _FakeJavaRunnerContext()
    _preload_pending(store, call)
    ctx = _create_runner_context(store)
    kwargs = {"reconciler": call} if use_reconciler else {}
    try:
        with pytest.raises(RuntimeError) as raised:
            _execute(ctx, asynchronous, call, **kwargs)
    finally:
        _close_runner_context(ctx)

    assert raised.value is interruption
    assert [slot.status for slot in store.call_results] == ["PENDING"]
    assert store.current_call_index == 0


def test_interrupted_future_is_executed_again_when_awaited_again() -> None:
    calls = []

    def call() -> str:
        calls.append(1)
        if len(calls) == 1:
            raise _java_error(_INTERRUPTED)
        return "done"

    store = _FakeJavaRunnerContext()
    ctx = _create_runner_context(store)
    try:
        future = ctx.durable_execute_async(call)
        with pytest.raises(RuntimeError):
            _run_async(future)
        outcomes = _run_async(ctx.gather(future))
    finally:
        _close_runner_context(ctx)

    assert outcomes[0].value == "done"
    assert len(calls) == 2
    assert [slot.status for slot in store.call_results] == ["SUCCEEDED"]


def test_exception_whose_message_cannot_be_formatted_is_recorded_by_async_call() -> (
    None
):
    def call() -> None:
        raise _UnprintableError

    store = _FakeJavaRunnerContext()
    ctx = _create_runner_context(store)
    try:
        with pytest.raises(_UnprintableError):
            _run_async(ctx.durable_execute_async(call))
    finally:
        _close_runner_context(ctx)

    assert [slot.status for slot in store.call_results] == ["FAILED"]


def test_java_interruption_in_gather_leaves_the_slot_pending() -> None:
    interruption = _java_error(_INTERRUPTED)

    def interrupted() -> None:
        raise interruption

    def declined() -> None:
        msg = "payment declined"
        raise _UnpicklableError(msg)

    def completed() -> str:
        return "done"

    store = _FakeJavaRunnerContext()
    ctx = _create_runner_context(store)
    try:
        with pytest.raises(RuntimeError) as raised:
            _run_async(
                ctx.gather(
                    ctx.durable_execute_async(declined),
                    ctx.durable_execute_async(interrupted),
                    ctx.durable_execute_async(completed),
                )
            )
    finally:
        _close_runner_context(ctx)

    assert raised.value is interruption
    assert [slot.status for slot in store.call_results] == [
        "FAILED",
        "PENDING",
        "PENDING",
    ]
    assert store.current_call_index == 0


def test_gather_records_exception_that_cannot_be_pickled() -> None:
    calls = []

    def declined() -> None:
        calls.append(1)
        msg = "payment declined"
        raise _UnpicklableError(msg)

    store = _FakeJavaRunnerContext()
    errors = []
    for _ in range(2):
        store.current_call_index = 0
        ctx = _create_runner_context(store)
        try:
            outcomes = _run_async(ctx.gather(ctx.durable_execute_async(declined)))
            errors.append(outcomes[0].error)
        finally:
            _close_runner_context(ctx)

    assert type(errors[0]) is _UnpicklableError
    assert str(errors[1]) == f"{_UNPICKLABLE_NAME}: payment declined"
    assert len(calls) == 1


def test_interrupted_gather_is_executed_again_when_awaited_again() -> None:
    calls = []

    def call() -> str:
        calls.append(1)
        if len(calls) == 1:
            raise _java_error(_INTERRUPTED)
        return "done"

    store = _FakeJavaRunnerContext()
    ctx = _create_runner_context(store)
    try:
        gathered = ctx.gather(ctx.durable_execute_async(call))
        with pytest.raises(RuntimeError):
            _run_async(gathered)
        outcomes = _run_async(gathered)
    finally:
        _close_runner_context(ctx)

    assert outcomes[0].value == "done"
    assert len(calls) == 2
    assert [slot.status for slot in store.call_results] == ["SUCCEEDED"]


def test_inspection_never_replaces_the_failure() -> None:
    class _Hostile(Exception):
        @property
        def args(self) -> tuple:
            raise KeyboardInterrupt

    assert not is_java_interruption(_Hostile())


def test_long_message_is_stored_once() -> None:
    message = "x" * 450_000
    error = ValueError(message)

    payload = serialize_durable_exception(error)
    restored = deserialize_durable_exception(payload)

    assert len(payload) < len(cloudpickle.dumps(error)) + 20 * 1024
    assert restored.args == (message,)


def test_long_message_of_unpicklable_exception_is_truncated_in_the_fallback() -> None:
    restored = deserialize_durable_exception(
        serialize_durable_exception(_UnpicklableError("x" * 20_000))
    )

    text = str(restored)
    assert text.startswith(f"{_UNPICKLABLE_NAME}: " + "x" * 16 * 1024)
    assert text.endswith("... [truncated]")
    assert len(text) < 17 * 1024
