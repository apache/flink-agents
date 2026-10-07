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
"""Turns a sub-agent result into something a chat model can be told.

A sub-agent result reaches the caller as an opaque object, but from here on it is
carried in a tool message and re-bound after a failover, so it must hold nothing
that JSON cannot express. Such a payload is rejected with the path where it was
found instead of being dropped or silently stringified.

A sub-agent that declares a result type is read through it: the type says how to
interpret a result the checks below would refuse, and what comes out is only what
the type declares.
"""

import json
import math
from functools import cache
from typing import Any

from pydantic import TypeAdapter


def normalize_agent_result(raw: Any, result_type: type = object) -> Any:
    """Reject a payload JSON cannot express, and reduce the rest to plain containers.

    Parameters
    ----------
    raw : Any
        The result a sub-agent produced.
    result_type : type = object
        The type the sub-agent declares for its result, or :class:`object` when
        it declares none, in which case ``raw`` is taken as it arrived.

    Returns:
    -------
    Any
        The result in generic form: dicts, lists and scalars only.
    """
    if result_type is object:
        _require_json_compatible(raw, "result")
        return json.loads(json.dumps(raw, allow_nan=False))
    # Two conversions: the first reads the result as the declared type, which is
    # what admits a payload the compatibility check below would refuse; the
    # second reduces that back to plain containers, so what is reported and
    # re-bound after a failover is the same generic form the undeclared path
    # produces.
    adapter = _adapter_for(result_type)
    generic = adapter.dump_python(adapter.validate_python(raw), mode="json")
    # Still required: a declared type can render a field JSON cannot express, and
    # the result outlives this call in a tool message and in state.
    _require_json_compatible(generic, "result")
    return generic


@cache
def _adapter_for(result_type: type) -> TypeAdapter[Any]:
    """The adapter of a declared result type, built once per type.

    Building one validates the type against pydantic, which is far more than a
    single result report should cost, and the set of declared types is bounded by
    the sub-agents in the plan.
    """
    return TypeAdapter(result_type)


def to_chat_message_content(value: Any) -> str:
    """Render a value as the content of the tool message handed back to the model."""
    if value is None:
        return "null"
    if isinstance(value, bool | dict | list | tuple):
        return json.dumps(value, separators=(",", ":"), allow_nan=False)
    return str(value)


def _require_json_compatible(value: Any, path: str) -> None:
    if value is None or isinstance(value, str | bool):
        return
    if isinstance(value, int | float):
        _require_finite_number(value, path)
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                msg = f"Map keys in sub-agent result must be strings at {path}"
                raise TypeError(msg)
            _require_json_compatible(item, f"{path}.{key}")
        return
    if isinstance(value, list | tuple):
        for index, item in enumerate(value):
            _require_json_compatible(item, f"{path}[{index}]")
        return
    msg = (
        f"Sub-agent result must be JSON-compatible at {path}, "
        f"found {type(value).__name__}"
    )
    raise TypeError(msg)


def _require_finite_number(number: float, path: str) -> None:
    if isinstance(number, float) and not math.isfinite(number):
        msg = f"Non-finite number in sub-agent result at {path}"
        raise ValueError(msg)
