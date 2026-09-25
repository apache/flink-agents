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
from dataclasses import dataclass
from typing import Callable, Type

from flink_agents.api.function import Function, JavaFunction, PythonFunction
from flink_agents.api.tools.tool_parameter_injection import (
    InjectedArg,
    normalize_injected_args,
    validate_injected_arg_names,
)


@dataclass(frozen=True)
class ActionDeclaration:
    """Immutable action declaration produced by applying ``action`` to a descriptor.

    Applying ``action`` to a cross-language :class:`Function` descriptor returns
    this wrapper instead of mutating the descriptor, so a descriptor shared by
    multiple declarations keeps each declaration's trigger conditions and name
    separate.

    Attributes:
    ----------
    trigger_conditions : tuple[str, ...]
        Raw event-type names or Boolean condition expressions.
    func : Function
        The api-layer executable descriptor dispatched for this action.
    name : str | None
        Optional action name override; ``None`` means use the attribute name.
    """

    trigger_conditions: tuple
    func: Function
    name: str | None = None


def _validate_descriptor(descriptor: Function, owner: str) -> None:
    """Reject descriptors with empty required identifiers, attributed to ``owner``."""
    if isinstance(descriptor, PythonFunction):
        if not descriptor.module or not descriptor.qualname:
            msg = f"PythonFunction target on '{owner}' must set both module and qualname"
            raise ValueError(msg)
    elif isinstance(descriptor, JavaFunction):
        if not descriptor.qualname or not descriptor.method_name:
            msg = (
                f"JavaFunction target on '{owner}' must set both qualname and method_name"
            )
            raise ValueError(msg)


def action(
    *trigger_conditions: str,
    name: str | None = None,
) -> Callable:
    """Mark a function or a cross-language descriptor as an agent action.

    Two declaration forms, mirroring Java's ``@Action`` on a method or a field:

    * Native — decorate a callable; the decorated function is the action body::

          @action(EventType.InputEvent)
          @staticmethod
          def handle(event: Event, ctx: RunnerContext) -> None: ...

    * Cross-language — apply to a :class:`Function` descriptor; the decorated
      attribute is the executable target, so no placeholder body is needed::

          handle = action(EventType.InputEvent)(
              JavaFunction.for_action("com.example.Handlers", "handle"))

    Each trigger condition is an event-type name or Boolean condition
    expression. Multiple conditions combine with OR semantics. To combine an
    event type and an attribute predicate with AND, place both in one
    expression, such as ``type == EventType.InputEvent && score > 5``.
    Expression validation occurs when the plan is applied.

    Parameters
    ----------
    trigger_conditions : str
        Raw event-type names or Boolean condition expressions.
    name : str, optional
        Action name override. An empty string or ``None`` falls back to the
        decorated member's attribute name (mirroring Java's ``@Action.name``).

    Returns:
    -------
    Callable
        Decorator that, for a callable target, returns the tagged function, and
        for a :class:`Function` descriptor target, returns an immutable
        :class:`ActionDeclaration`.

    Raises:
    ------
    TypeError
        If a trigger condition is not a string, or the decorated object is
        neither a callable nor an api-layer :class:`Function` descriptor.
    ValueError
        If a cross-language descriptor is missing required identifiers.
    """
    for entry in trigger_conditions:
        if not isinstance(entry, str):
            msg = f"action trigger condition must be a string, got {entry!r}"
            raise TypeError(msg)

    # Mirror Java's resolveActionName: an empty-string override means "no
    # override" (a Java annotation cannot distinguish its "" default from an
    # explicit ""), so the action falls back to its attribute name.
    if name == "":
        name = None

    def decorator(target: Callable | Function) -> Callable | ActionDeclaration:
        if isinstance(target, Function):
            _validate_descriptor(target, name or type(target).__name__)
            return ActionDeclaration(
                trigger_conditions=trigger_conditions,
                func=target,
                name=name,
            )
        if not callable(target):
            msg = (
                f"action() must decorate a callable or an api-layer Function "
                f"descriptor, got {type(target).__name__}"
            )
            raise TypeError(msg)
        target._trigger_conditions = trigger_conditions
        if name is not None:
            target._action_name = name
        return target

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
