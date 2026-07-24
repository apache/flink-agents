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
"""Guards the ``output_schema`` parameter contract across every chat connection."""

import inspect
from typing import Iterator, List, Type

from pydantic import BaseModel

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_models import java_chat_model as api_java_chat_model
from flink_agents.api.chat_models.chat_model import BaseChatModelConnection
from flink_agents.e2e_tests.e2e_tests_integration import (
    mock_chat_model_agent,
    tool_parameter_injection_agent,
)
from flink_agents.integrations.chat_models import ollama_chat_model, tongyi_chat_model
from flink_agents.integrations.chat_models.anthropic import anthropic_chat_model
from flink_agents.integrations.chat_models.azure import azure_openai_chat_model
from flink_agents.integrations.chat_models.openai import openai_chat_model
from flink_agents.runtime.java import java_chat_model as runtime_java_chat_model

# A class is only discoverable through __subclasses__() once it has been imported.
# Importing every module that defines a connection is what gives the walk below its
# reach — including the cross-language bridge and the e2e test doubles.
_MODULES_DEFINING_CONNECTIONS = (
    anthropic_chat_model,
    api_java_chat_model,
    azure_openai_chat_model,
    mock_chat_model_agent,
    ollama_chat_model,
    openai_chat_model,
    runtime_java_chat_model,
    tongyi_chat_model,
    tool_parameter_injection_agent,
)


class _Answer(BaseModel):
    """A representative output schema to hand to a connection."""

    text: str


def _subclasses_recursive(cls: Type[object]) -> Iterator[Type[object]]:
    for subclass in cls.__subclasses__():
        yield subclass
        yield from _subclasses_recursive(subclass)


def _is_test_local(cls: Type[object]) -> bool:
    """Whether ``cls`` is defined inside a ``tests`` package.

    A whole-tree pytest run imports every test module into one process, so doubles
    declared inside a test file also turn up in the walk. Such a double may
    deliberately accept a schema in order to assert on what a caller routed to it,
    which is the opposite of the contract asserted here.
    """
    return "tests" in cls.__module__.split(".")


def _connections_implementing_chat() -> List[Type[BaseChatModelConnection]]:
    """Connections outside a ``tests`` package that supply their own ``chat`` body.

    A class that never overrides ``chat`` inherits only the abstract declaration, so
    there is no body to assert on.
    """
    return [
        cls
        for cls in _subclasses_recursive(BaseChatModelConnection)
        if not _is_test_local(cls) and cls.chat is not BaseChatModelConnection.chat
    ]


def test_every_connection_declares_output_schema_param() -> None:
    """Every connection in the tree must declare ``output_schema`` defaulting to None.

    A connection that omits the parameter absorbs a caller's ``output_schema`` into
    ``**kwargs``. Connections forward ``**kwargs`` to their provider SDK — and the
    cross-language bridge forwards it to Java as ``modelParams`` — so the schema would
    reach the request body as an unknown field. Declaring the parameter is what makes
    the leak impossible rather than merely unlikely.

    The tree is walked rather than hand-listed: a hand-kept list silently stops
    guarding whatever it was never updated to mention, including the abstract base
    itself.
    """
    offenders: List[str] = []
    connections = [
        BaseChatModelConnection,
        *_subclasses_recursive(BaseChatModelConnection),
    ]
    for cls in connections:
        param = inspect.signature(cls.chat).parameters.get("output_schema")
        if param is None:
            offenders.append(f"{cls.__module__}.{cls.__qualname__}: parameter missing")
        elif param.default is not None:
            offenders.append(
                f"{cls.__module__}.{cls.__qualname__}: default is "
                f"{param.default!r}, expected None"
            )

    assert not offenders, (
        "connections violating the output_schema contract:\n" + "\n".join(offenders)
    )


def _schema_rejection_failure(
    cls: Type[BaseChatModelConnection], schema: OutputSchema
) -> str | None:
    """Describe how ``cls.chat`` mishandles ``schema``, or ``None`` if it rejects it.

    ``__new__`` skips ``__init__``, so no credentials, no client and no network are
    involved: the rejection has to happen before the first attribute access for the
    call to get this far, which is what pins the guard to the top of ``chat``.
    """
    name = f"{cls.__module__}.{cls.__qualname__}"
    connection = cls.__new__(cls)
    try:
        cls.chat(connection, messages=[], tools=None, output_schema=schema)
    except NotImplementedError:
        return None
    except Exception as exc:
        return f"{name}: raised {type(exc).__name__} before rejecting the schema"
    return f"{name}: accepted the schema instead of rejecting it"


def test_every_connection_rejects_an_output_schema_it_cannot_translate() -> None:
    """A connection with no native translation must reject a schema, not drop it.

    Declaring the parameter only keeps the schema out of the provider request; on its
    own it lets a connection silently return an unconstrained response that the caller
    would treat as schema-conforming. Rejecting turns that into an error at the call.

    The tree is walked rather than hand-listed, so a connection added to a module that
    is already imported is held to the contract for free. Reach still stops at those
    imports: ``__subclasses__()`` only sees classes that have been imported, so a
    connection in a brand-new module needs that module added at the top of this file.
    """
    schema = OutputSchema(output_schema=_Answer)
    connections = _connections_implementing_chat()

    assert connections, "the connection walk found nothing to check"
    offenders = [
        failure
        for cls in connections
        if (failure := _schema_rejection_failure(cls, schema)) is not None
    ]

    assert not offenders, (
        "connections that do not reject an untranslatable output_schema:\n"
        + "\n".join(offenders)
    )
