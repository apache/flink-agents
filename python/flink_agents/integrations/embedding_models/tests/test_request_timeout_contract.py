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
"""Embedding model connections take a numeric ``request_timeout`` or none at all.

An omitted ``request_timeout`` uses the connection's default. ``None`` is
rejected instead of being mapped to the default, because ``None`` means "no
timeout" to HTTP clients such as HTTPX. Building a connection contacts no
service.
"""

from typing import Any, Dict, Tuple, Type

import pytest
from pydantic import ValidationError

from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
)
from flink_agents.integrations.embedding_models import (
    dashscope_embedding_model,
    openai_embedding_model,
)
from flink_agents.integrations.embedding_models.local import ollama_embedding_model

CONNECTIONS: Dict[
    str, Tuple[Type[BaseEmbeddingModelConnection], Dict[str, Any], float]
] = {
    "Ollama": (
        ollama_embedding_model.OllamaEmbeddingModelConnection,
        {},
        ollama_embedding_model.DEFAULT_REQUEST_TIMEOUT,
    ),
    "DashScope": (
        dashscope_embedding_model.DashScopeEmbeddingModelConnection,
        {"api_key": "fake-key"},
        dashscope_embedding_model.DEFAULT_REQUEST_TIMEOUT,
    ),
    "OpenAI": (
        openai_embedding_model.OpenAIEmbeddingModelConnection,
        {"api_key": "fake-key"},
        openai_embedding_model.DEFAULT_REQUEST_TIMEOUT,
    ),
}


@pytest.mark.parametrize("provider", list(CONNECTIONS))
def test_omitted_request_timeout_uses_the_default(provider: str) -> None:
    """Omitting request_timeout keeps the connection's default."""
    cls, kwargs, default = CONNECTIONS[provider]

    assert cls(**kwargs).request_timeout == default


@pytest.mark.parametrize("provider", list(CONNECTIONS))
def test_numeric_request_timeout_is_used(provider: str) -> None:
    """A numeric request_timeout is used as given."""
    cls, kwargs, _ = CONNECTIONS[provider]

    assert cls(request_timeout=2.5, **kwargs).request_timeout == 2.5


@pytest.mark.parametrize("provider", list(CONNECTIONS))
def test_none_request_timeout_rejected(provider: str) -> None:
    """None is a validation error, not a second way to select the default."""
    cls, kwargs, _ = CONNECTIONS[provider]

    with pytest.raises(ValidationError, match="request_timeout"):
        cls(request_timeout=None, **kwargs)
