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
################################################################################
"""Compile and traverse child plans carried by ordinary resource descriptors."""

import threading
from copy import deepcopy
from typing import TYPE_CHECKING, Any, Callable

from flink_agents.api.agents.agent import Agent
from flink_agents.api.resource import JavaResourceDescriptor, ResourceDescriptor
from flink_agents.api.subagent import INTERNAL_SUBAGENT_SETUP_CLASS

if TYPE_CHECKING:
    from flink_agents.plan.agent_plan import AgentPlan

# Thread-local cycle detection and plan reuse for the recursive walk in
# ``AgentPlan.from_agent``: completed plans are memoized per agent instance
# (an agent registered under multiple names shares one compiled plan) and
# cycles are rejected consistently — whether or not they run through the root
# agent — by tracking the agents currently being compiled. The root caller
# owns the state: :func:`begin` returns ``True`` for it and it must call
# :func:`end` in a finally block.

_STATE = threading.local()


def begin(root: Any) -> bool:
    """Mark the start of a compilation walk.

    The root is registered as being compiled, so a cycle running through it
    is detected like any other.

    Returns:
    -------
    bool
        ``True`` if this call created the state (i.e. this is the root); the
        caller must invoke :func:`end` in its ``finally`` block.
    """
    owner = getattr(_STATE, "compiled", None) is None
    if owner:
        _STATE.compiled = {}
        _STATE.compiling = {}
        _STATE.path = []
        # Nested plans call begin too; only the owner registers the root.
        _STATE.compiling[id(root)] = "<root>"
        _STATE.path.append("<root>")
    return owner


def end() -> None:
    """Clean up the thread-local state.

    Only call when :func:`begin` returned ``True``.
    """
    _STATE.compiled = None
    _STATE.compiling = None
    _STATE.path = None


def get_or_compile(
    agent: Any, name: str, compiler: Callable[[Any], "AgentPlan"]
) -> "AgentPlan":
    """Return the previously compiled plan for ``agent``, compiling it once.

    Raises:
    ------
    ValueError
        When ``agent`` is already being compiled higher up the call stack,
        reporting the resource-name path of the cycle.
    """
    compiled = _STATE.compiled
    compiling = _STATE.compiling
    path = _STATE.path
    key = id(agent)
    plan = compiled.get(key)
    if plan is not None:
        return plan
    if key in compiling:
        names = list(path)
        start = 0
        for index, entry in enumerate(names):
            if entry == compiling[key]:
                start = index
                break
        cycle = " -> ".join(names[start:] + [name])
        msg = f"Cyclic sub-agent definition detected: {cycle}"
        raise ValueError(msg)
    compiling[key] = name
    path.append(name)
    try:
        plan = compiler(agent)
        compiled[key] = plan
        return plan
    finally:
        del compiling[key]
        path.pop()


def is_internal(descriptor: ResourceDescriptor) -> bool:
    """Whether the descriptor constructs the internal sub-agent runtime."""
    return (
        descriptor.language == "java"
        and descriptor.target_clazz == INTERNAL_SUBAGENT_SETUP_CLASS
    )


def compile_descriptor(
    name: str,
    descriptor: ResourceDescriptor,
    compiler: Callable[[Agent], "AgentPlan"],
) -> ResourceDescriptor:
    """Compile a child Agent without modifying its declaration."""
    if not is_internal(descriptor):
        return descriptor
    arguments = dict(descriptor.arguments)
    agent = arguments.pop("agent", None)
    if not isinstance(agent, Agent) or "plan" in arguments:
        msg = (
            f"Internal sub-agent '{name}' requires an Agent declaration, "
            "not a compiled plan"
        )
        raise ValueError(msg)
    plan = get_or_compile(agent, name, compiler)
    arguments["plan"] = plan.model_dump(mode="json", serialize_as_any=True)
    arguments["scope"] = name
    return JavaResourceDescriptor(
        clazz=INTERNAL_SUBAGENT_SETUP_CLASS, arguments=arguments
    )


def decode_plan(descriptor: ResourceDescriptor) -> "AgentPlan":
    """Restore the typed plan from a descriptor's JSON data."""
    from flink_agents.plan.agent_plan import AgentPlan

    data = descriptor.arguments.get("plan")
    if not isinstance(data, dict):
        msg = "Internal sub-agent requires a compiled plan object"
        raise TypeError(msg)
    # AgentPlan's validator reconstructs providers in place; preserve the descriptor.
    return AgentPlan.model_validate(deepcopy(data))
