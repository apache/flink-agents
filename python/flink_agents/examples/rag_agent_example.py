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

from flink_agents.api.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import (
    action,
    chat_model_connection,
    chat_model_setup,
    embedding_model_connection,
    embedding_model_setup,
    prompt,
    vector_store_connection,
    vector_store_setup,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
)
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
    OllamaChatModelSetup,
)
from flink_agents.integrations.embedding_models.local.ollama_embedding_model import (
    OllamaEmbeddingModelConnection,
    OllamaEmbeddingModelSetup,
)
from flink_agents.integrations.vector_stores.chroma.chroma_vector_store import (
    ChromaVectorStoreConnection,
    ChromaVectorStoreSetup,
)

OLLAMA_CHAT_MODEL = os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:8b")
OLLAMA_EMBEDDING_MODEL = os.environ.get("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text")


class MyRAGAgent(Agent):
    """Example RAG agent demonstrating context retrieval.

    This RAG agent shows how to:
    1. Receive a user query
    2. Retrieve relevant context from a vector store using semantic search
    3. Augment the user query with retrieved context
    4. Generate enhanced responses using the chat model

    This is a basic example demonstrating RAG workflow with Ollama and ChromaDB.
    """

    @prompt
    @staticmethod
    def context_enhanced_prompt() -> Prompt:
        """Prompt template for enhancing user queries with retrieved context."""
        template = """Based on the following context, please answer the user's question.

Context:
{context}

User Question:
{user_query}

Please provide a helpful answer based on the context provided."""
        return Prompt.from_text(template)

    @embedding_model_connection
    @staticmethod
    def ollama_embedding_connection() -> ResourceDescriptor:
        """Embedding model connection for Ollama."""
        return ResourceDescriptor(clazz=OllamaEmbeddingModelConnection)

    @embedding_model_setup
    @staticmethod
    def text_embedder() -> ResourceDescriptor:
        """Embedding model setup for generating text embeddings."""
        return ResourceDescriptor(
            clazz=OllamaEmbeddingModelSetup,
            connection="ollama_embedding_connection",
            model=OLLAMA_EMBEDDING_MODEL,
        )

    @vector_store_connection
    @staticmethod
    def chroma_connection() -> ResourceDescriptor:
        """Vector store connection for ChromaDB (in-memory for demo)."""
        return ResourceDescriptor(clazz=ChromaVectorStoreConnection)

    @vector_store_setup
    @staticmethod
    def knowledge_base() -> ResourceDescriptor:
        """Vector store setup for knowledge base."""
        return ResourceDescriptor(
            clazz=ChromaVectorStoreSetup,
            connection="chroma_connection",
            embedding_model="text_embedder",
            collection="example_knowledge_base",
        )

    @chat_model_connection
    @staticmethod
    def ollama_chat_connection() -> ResourceDescriptor:
        """Chat model connection for Ollama."""
        return ResourceDescriptor(clazz=OllamaChatModelConnection, model=OLLAMA_CHAT_MODEL)

    @chat_model_setup
    @staticmethod
    def chat_model() -> ResourceDescriptor:
        """Chat model setup for generating responses."""
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            connection="ollama_chat_connection",
        )

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """Process user input and retrieve relevant context."""
        user_query = str(event.input)
        ctx.send_event(
            ContextRetrievalRequestEvent(
                query=user_query,
                vector_store="knowledge_base",
                max_results=3,
            )
        )

    @action(ContextRetrievalResponseEvent)
    @staticmethod
    def process_retrieved_context(
        event: ContextRetrievalResponseEvent, ctx: RunnerContext
    ) -> None:
        """Process retrieved context and create enhanced chat request."""
        user_query = event.query
        retrieved_docs = event.documents

        # Create context from retrieved documents
        context_text = "\n\n".join(
            [f"{i+1}. {doc.content}" for i, doc in enumerate(retrieved_docs)]
        )

        # Get prompt resource and format it
        prompt_resource = ctx.get_resource("context_enhanced_prompt", ResourceType.PROMPT)
        enhanced_prompt = prompt_resource.format_string(
            context=context_text,
            user_query=user_query
        )

        # Send chat request with enhanced prompt
        ctx.send_event(
            ChatRequestEvent(
                model="chat_model",
                messages=[ChatMessage(role=MessageRole.USER, content=enhanced_prompt)],
            )
        )

    @action(ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """Process chat model response and generate output."""
        if event.response and event.response.content:
            ctx.send_event(OutputEvent(output=event.response.content))


def populate_knowledge_base() -> None:
    """Populate ChromaDB with sample knowledge documents using Ollama embeddings."""
    print("Populating ChromaDB with sample knowledge documents...")

    # Create connections directly
    embedding_connection = OllamaEmbeddingModelConnection()
    vector_store_connection = ChromaVectorStoreConnection()

    # Get collection (create if doesn't exist)
    collection_name = "example_knowledge_base"
    collection = vector_store_connection.client.get_or_create_collection(
        name=collection_name,
        metadata=None,
    )

    # Sample documents to embed and store
    documents = [
        "Apache Flink is a stream processing framework for distributed, high-performing, always-available, and accurate data streaming applications.",
        "Flink provides exactly-once state consistency guarantees and low-latency processing with high throughput.",
        "Apache Flink Agents is an innovative Agentic AI framework built on Apache Flink that enables distributed, stateful execution of AI agents.",
        "Vector stores are databases optimized for storing and searching high-dimensional vectors, commonly used in context retrieval applications.",
        "Context retrieval combines information retrieval with language generation to provide more accurate and contextual responses by finding relevant information.",
    ]

    metadatas = [
        {"topic": "flink", "source": "documentation"},
        {"topic": "flink", "source": "documentation"},
        {"topic": "flink_agents", "source": "documentation"},
        {"topic": "vector_stores", "source": "ai_concepts"},
        {"topic": "context_retrieval", "source": "ai_concepts"},
    ]

    # Generate real embeddings using Ollama
    embeddings = []
    for _i, doc in enumerate(documents):
        embedding = embedding_connection.embed(doc, model=OLLAMA_EMBEDDING_MODEL)
        embeddings.append(embedding)

    # Prepare data for ChromaDB
    test_data = {
        "documents": documents,
        "embeddings": embeddings,
        "metadatas": metadatas,
        "ids": [f"doc{i+1}" for i in range(len(documents))]
    }

    # Add documents to ChromaDB
    collection.add(**test_data)

    print(f"Knowledge base setup complete! Added {len(documents)} documents to ChromaDB.")


if __name__ == "__main__":
    print("Starting RAG Example Agent...")

    # Initialize knowledge base with real data
    populate_knowledge_base()

    agent = MyRAGAgent()

    env = AgentsExecutionEnvironment.get_execution_environment()
    input_list = []

    output_list = env.from_list(input_list).apply(agent).to_list()

    test_queries = [
        {"key": "001", "value": "What is Apache Flink?"},
        {"key": "002", "value": "What is Apache Flink Agents?"},
        {"key": "003", "value": "What is Python?"},
    ]

    input_list.extend(test_queries)

    env.execute()

    print("\n" + "="*50)
    print("RAG Example Results:")
    print("="*50)

    for output in output_list:
        for key, value in output.items():
            print(f"\n[{key}] Response: {value}")
            print("-" * 40)

