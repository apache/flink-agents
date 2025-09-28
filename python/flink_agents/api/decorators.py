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

from flink_agents.api.events.event import Event


def action(*listen_events: Type[Event]) -> Callable:
    """Decorator for marking a function as an agent action.

    Parameters
    ----------
    listen_events : list[Type[Event]]
        List of event types that this action should respond to.

    Returns:
    -------
    Callable
        Decorator function that marks the target function with event listeners.

    Raises:
    ------
    AssertionError
        If no events are provided to listen to.
    """
    assert len(listen_events) > 0, (
        "action must have at least one event type to listen to"
    )

    for event in listen_events:
        assert issubclass(event, Event), "action must only listen to event types."

    def decorator(func: Callable) -> Callable:
        func._listen_events = listen_events
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


def tool(func: Callable) -> Callable:
    """Decorator for marking a function declaring a tool.

    Parameters
    ----------
    func : Callable
        Function to be decorated.

    Returns:
    -------
    Callable
        Decorator function that marks the target function declare a tool.
    """
    func._is_tool = True
    return func


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
