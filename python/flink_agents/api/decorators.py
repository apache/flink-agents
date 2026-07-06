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
from typing import Callable, Type

from flink_agents.api.function import Function, JavaFunction, PythonFunction
from flink_agents.api.tools.tool_parameter_injection import (
    InjectedArg,
    normalize_injected_args,
    validate_injected_arg_names,
)


def _validate_target(target: Function, owner: str) -> None:
    """Reject targets with empty required identifiers, attributed to ``owner``."""
    if isinstance(target, PythonFunction):
        if not target.module or not target.qualname:
            msg = f"PythonFunction target on '{owner}' must set both module and qualname"
            raise ValueError(msg)
    elif isinstance(target, JavaFunction):
        if not target.qualname or not target.method_name:
            msg = f"JavaFunction target on '{owner}' must set both qualname and method_name"
            raise ValueError(msg)


def action(
    *trigger_conditions: str,
    target: Function | None = None,
) -> Callable:
    """Decorator for marking a function as an agent action.

    Each argument is an event-type name string that this action responds to.
    Multiple entries combine with OR semantics — the action triggers if any
    one matches.

    Parameters
    ----------
    trigger_conditions : str
        Event-type name strings that this action responds to.
    target : Function, optional
        Cross-language function descriptor dispatched instead of the
        decorated body. The body becomes a stub — raise
        ``NotImplementedError`` so direct calls fail loud.

    Returns:
    -------
    Callable
        Decorator function that marks the target function with trigger conditions.

    Raises:
    ------
    AssertionError
        If no conditions are given or any entry is not a non-empty string.
    TypeError
        If ``target`` is provided but is not a :class:`Function` descriptor.
    """
    assert len(trigger_conditions) > 0, (
        "action must have at least one trigger condition (event-type name)"
    )

    for entry in trigger_conditions:
        assert isinstance(entry, str), (
            f"action trigger condition must be a string, got {entry!r}"
        )
        assert entry != "", (
            f"action trigger condition must be non-empty, got {entry!r}"
        )

    if target is not None and not isinstance(target, Function):
        msg = (
            f"action(target=...) must be an api-layer Function descriptor, "
            f"got {type(target).__name__}"
        )
        raise TypeError(msg)

    def decorator(func: Callable) -> Callable:
        if target is not None:
            _validate_target(target, func.__qualname__)
            func._target = target
        func._trigger_conditions = trigger_conditions
        return func

    return decorator


def chat_model_connection(func: Callable) -> Callable:
    """Decorator for marking a function declaring a chat model connection.

    Parameters
    ----------
    func : Callable
        Function to be decorated.

    Returns:
    -------
    Callable
        Decorator function that marks the target function declare a chat model
        connection.
    """
    func._is_chat_model_connection = True
    return func


def chat_model_setup(func: Callable) -> Callable:
    """Decorator for marking a function declaring a chat model setup.

    Parameters
    ----------
    func : Callable
        Function to be decorated.

    Returns:
    -------
    Callable
        Decorator function that marks the target function declare a chat model.
    """
    func._is_chat_model_setup = True
    return func


def embedding_model_connection(func: Callable) -> Callable:
    """Decorator for marking a function declaring an embedding model connection.

    Parameters
    ----------
    func : Callable
        Function to be decorated.

    Returns:
    -------
    Callable
        Decorator function that marks the target function declare an embedding model
        connection.
    """
    func._is_embedding_model_connection = True
    return func


def embedding_model_setup(func: Callable) -> Callable:
    """Decorator for marking a function declaring an embedding model setup.

    Parameters
    ----------
    func : Callable
        Function to be decorated.

    Returns:
    -------
    Callable
        Decorator function that marks the target function declare an embedding model.
    """
    func._is_embedding_model_setup = True
    return func


def tool(
    func: Callable | None = None,
    *,
    injected_args: dict[str, InjectedArg | dict] | None = None,
) -> Callable:
    """Decorator for marking a function declaring a tool.

    Parameters
    ----------
    func : Callable
        Function to be decorated.
    injected_args : dict, optional
        Mapping from parameter name to its framework-owned value source.
        These arguments are hidden from the model-facing tool schema.

    Returns:
    -------
    Callable
        Decorator function that marks the target function declare a tool.
    """
    injected = normalize_injected_args(injected_args)

    def decorator(target: Callable) -> Callable:
        validate_injected_arg_names(target, injected)
        target._is_tool = True
        target._injected_args = injected
        return target

    if func is not None:
        return decorator(func)
    return decorator


def prompt(func: Callable) -> Callable:
    """Decorator for marking a function declaring a prompt.

    Parameters
    ----------
    func : Callable
        Function to be decorated.

    Returns:
    -------
    Callable
        Decorator function that marks the target function declare a prompt.
    """
    func._is_prompt = True
    return func


def mcp_server(func: Callable) -> Callable:
    """Decorator for marking a function declaring a MCP server.

    Parameters
    ----------
    func : Callable
        Function to be decorated.

    Returns:
    -------
    Callable
        Decorator function that marks the target function declare a MCP server.
    """
    func._is_mcp_server = True
    return func


def vector_store(func: Callable) -> Callable:
    """Decorator for marking a function declaring a vector store.

    Parameters
    ----------
    func : Callable
        Function to be decorated.

    Returns:
    -------
    Callable
        Decorator function that marks the target function declare a vector store.
    """
    func._is_vector_store = True
    return func


def skills(func: Callable) -> Callable:
    """Decorator for marking a function declaring skills.

    Parameters
    ----------
    func : Callable
        Function to be decorated.

    Returns:
    -------
    Callable
        Decorator function that marks the target function declare skills.
    """
    func._is_skills = True
    return func


def java_resource(cls: Type) -> Type:
    """Decorator to mark a class as Java resource."""
    cls._is_java_resource = True
    return cls
