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
import os
import subprocess
import sys
from pathlib import Path

import pytest
from ollama import Client

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.integrations.embedding_models.local.ollama_embedding_model import (
    OllamaEmbeddingModelConnection,
    OllamaEmbeddingModelSetup,
)

test_model = os.environ.get("OLLAMA_EMBEDDING_MODEL", "all-minilm:22m")
current_dir = Path(__file__).parent

try:
    # only auto setup ollama in ci with python3.9 to reduce ci cost.
    if "3.9" in sys.version:
        subprocess.run(
            ["bash", f"{current_dir}/start_ollama_server.sh"], timeout=300, check=True
        )
    client = Client()
    models = client.list()

    model_found = False
    for model in models["models"]:
        if model.model == test_model:
            model_found = True
            break

    if not model_found:
        client = None  # type: ignore
except Exception:
    client = None  # type: ignore


@pytest.mark.skipif(
    client is None, reason="Ollama client is not available or test embedding model is missing"
)
def test_ollama_embedding_setup() -> None:
    """Test embedding functionality with OllamaEmbeddingModelSetup."""
    connection = OllamaEmbeddingModelConnection(
        name="ollama_embed",
        base_url="http://localhost:11434"
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        return connection

    setup = OllamaEmbeddingModelSetup(
        name="embeddings",
        connection="ollama_embed",
        model=test_model,
        truncate=True,
        get_resource=get_resource
    )

    # Test embedding through setup
    embedding = setup.embed("This is a test sentence for embedding.")
    assert embedding is not None
    assert isinstance(embedding, list)
    assert len(embedding) > 0
    assert all(isinstance(x, float) for x in embedding)
