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
import functools
import hashlib
import inspect
from typing import Any, Callable

import cloudpickle

_DURABLE_ID_ATTR = "__flink_agents_durable_id__"


def with_durable_id(func: Callable, durable_id: str) -> Callable:
    """Wrap ``func`` so durable execution keys it by ``durable_id``.

    Callers that own a stable identity for a durable call attach it here
    instead of relying on the module/qualname of the callable, which is
    shared by every call issued from the same implementation.
    """

    @functools.wraps(func)
    def wrapped(*args: Any, **kwargs: Any) -> Any:
        return func(*args, **kwargs)

    setattr(wrapped, _DURABLE_ID_ATTR, durable_id)
    return wrapped


def get_durable_id(func: Callable) -> str | None:
    """Return the explicit durable id attached by :func:`with_durable_id`.

    Returns ``None`` when the callable carries no explicit id, in which case
    callers fall back to deriving the identity from the callable itself.
    """
    return getattr(func, _DURABLE_ID_ATTR, None)


def durable_identity_for_call(
    func: Callable,
    args: tuple,
    kwargs: dict | None,
) -> tuple[str, str]:
    """Return the durable journal identity for a single callable invocation."""
    call_kwargs = kwargs or {}
    return _compute_function_id(func), _compute_args_digest(args, call_kwargs)


def _compute_function_id(func: Callable) -> str:
    """Compute a stable function identifier from a callable.

    An explicit id attached by :func:`with_durable_id` wins over the derived
    module/qualname.
    """
    explicit_id = get_durable_id(func)
    if explicit_id is not None:
        return explicit_id
    module_obj = inspect.getmodule(func)
    module = (
        module_obj.__name__
        if module_obj is not None
        else getattr(func, "__module__", "<unknown>")
    )
    qualname = getattr(func, "__qualname__", getattr(func, "__name__", "<unknown>"))
    return f"{module}.{qualname}"


def _compute_args_digest(args: tuple, kwargs: dict) -> str:
    """Compute a stable digest of the serialized arguments."""
    try:
        serialized = cloudpickle.dumps((args, kwargs))
        return hashlib.sha256(serialized).hexdigest()[:16]
    except Exception:
        return hashlib.sha256(str((args, kwargs)).encode()).hexdigest()[:16]


def _can_bind_call(
    func: Callable,
    *args: Any,
    **kwargs: Any,
) -> bool:
    """Return whether the callable signature can bind the provided arguments."""
    try:
        inspect.signature(func).bind(*args, **kwargs)
    except (TypeError, ValueError):
        return False
    else:
        return True


def _validate_reconciler_callable(
    reconciler: Callable[[], Any] | None,
) -> Callable[[], Any] | None:
    """Validate that the reconciler callable is either absent or zero-argument."""
    if reconciler is None:
        return None

    if not callable(reconciler):
        err_msg = "reconciler must be callable"
        raise TypeError(err_msg)

    if not _can_bind_call(reconciler):
        err_msg = "reconciler must be a callable that takes no arguments"
        raise TypeError(err_msg)

    return reconciler
