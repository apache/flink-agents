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
import os

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import (
    action,
    embedding_model_connection,
    embedding_model_setup,
)
from flink_agents.api.embedding_models.java_embedding_model import (
    JavaEmbeddingModelConnection,
    JavaEmbeddingModelSetup,
)
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext


class EmbeddingModelCrossLanguageAgent(Agent):
    """Example agent demonstrating cross-language embedding model testing."""

    @embedding_model_connection
    @staticmethod
    def embedding_model_connection() -> ResourceDescriptor:
        """EmbeddingModelConnection responsible for ollama model service connection."""
        return ResourceDescriptor(
            clazz=JavaEmbeddingModelConnection,
            java_clazz="org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelConnection",
            host="http://localhost:11434",
        )

    @embedding_model_setup
    @staticmethod
    def embedding_model() -> ResourceDescriptor:
        """EmbeddingModel which focus on math, and reuse ChatModelConnection."""
        return ResourceDescriptor(
            clazz=JavaEmbeddingModelSetup,
            java_clazz="org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelSetup",
            connection="embedding_model_connection",
            model=os.environ.get("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text:latest"),
        )

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """User defined action for processing input.

        In this action, we will test embedding model functionality.
        """
        input_text = event.input

        short_doc = f"{input_text[:5]}..."

        print(f"[TEST] Starting embedding generation test for: '{input_text[:10]}...'")

        try:
            # Get embedding model
            embeddingModel = ctx.get_resource("embedding_model", ResourceType.EMBEDDING_MODEL)

            # Test single text embedding
            embedding = embeddingModel.embed(input_text)
            print(f"[TEST] Generated embedding with dimension: {len(embedding)}")

            # Validate single embedding result
            if embedding is None or not isinstance(embedding, list) or len(embedding) == 0:
                err_msg = "Embedding cannot be null or empty"
                raise AssertionError(err_msg) # noqa: TRY301

            if not all(isinstance(x, float) for x in embedding):
                err_msg = "All embedding values must be floats"
                raise AssertionError(err_msg) # noqa: TRY301

            print(f"[TEST] Validated single embedding: Text={short_doc}, Dimension={len(embedding)}, Text='{input_text[:30]}...'")

            # Test batch embedding
            embeddings = embeddingModel.embed([input_text])
            print(f"[TEST] Generated batch embeddings: count={len(embeddings)}")

            # Validate batch embedding results
            if embeddings is None or not isinstance(embeddings, list) or len(embeddings) == 0:
                err_msg = "Batch embeddings cannot be null or empty"
                raise AssertionError(err_msg) # noqa: TRY301

            if len(embeddings) != 1:
                err_msg = f"Expected 1 embedding but got {len(embeddings)}"
                raise AssertionError(err_msg) # noqa: TRY301

            for i, emb in enumerate(embeddings):
                if not isinstance(emb, list) or len(emb) == 0:
                    err_msg = f"Embedding at index {i} is invalid"
                    raise AssertionError(err_msg) # noqa: TRY301
                print(f"[TEST] Validated batch embedding {i}: Dimension={len(emb)}")

            # Create test result as a single string
            test_result = f"[PASS] Text={short_doc}, Dimension={len(embedding)}, BatchCount={len(embeddings)}"

            ctx.send_event(OutputEvent(output=test_result))

            print(f"[TEST] Embedding generation test PASSED for: '{input_text[:50]}...'")

        except Exception as e:
            # Create error result as a single string
            test_result = f"[FAIL] Text={short_doc}, Error={e!s}"

            ctx.send_event(OutputEvent(output=test_result))

            print(f"[TEST] Embedding generation test FAILED: {e!s}")
