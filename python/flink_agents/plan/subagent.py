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
"""Plan-layer support for internal sub-agents.

An ``Agent`` registered directly as an AGENT resource
(``add_resource(name, AGENT, ChildAgent())``) is compiled into an internal
sub-agent: the child's compiled :class:`~flink_agents.plan.agent_plan.AgentPlan`
plus a scope id (the resource name) are carried by a
``PythonSerializableResourceProvider`` referencing the runtime setup class
``flink_agents.runtime.internal_subagent.InternalSubagentSetup`` by name, so
the plan layer keeps its dependency direction (the setup itself lives in the
runtime layer, mirroring Java's ``InternalSubagentProvider``).

The ``begin`` / ``end`` / ``get_or_compile`` helpers provide thread-local
cycle detection and child-plan reuse during compilation, mirroring Java's
``InternalSubagentCompilationHelper``.
"""

import threading
from typing import TYPE_CHECKING, Any, Callable

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
