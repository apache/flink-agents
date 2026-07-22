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


def _subclasses_recursive(cls: Type[object]) -> Iterator[Type[object]]:
    for subclass in cls.__subclasses__():
        yield subclass
        yield from _subclasses_recursive(subclass)


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
