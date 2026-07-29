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
import hashlib
import inspect
from typing import Any, Callable

import cloudpickle


def _compute_function_id(func: Callable) -> str:
    """Compute a stable function identifier from a callable."""
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
