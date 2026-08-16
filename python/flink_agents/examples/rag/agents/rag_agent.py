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
import tempfile
from pathlib import Path

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import (
    action,
    chat_model_setup,
    embedding_model_setup,
    prompt,
    vector_store,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
    ResourceType,
)
from flink_agents.api.runner_context import RunnerContext

OLLAMA_CHAT_MODEL = os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:8b")
OLLAMA_EMBEDDING_MODEL = os.environ.get("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text")
CHROMA_PERSIST_DIRECTORY = os.environ.get(
    "CHROMA_PERSIST_DIRECTORY",
    str(Path(tempfile.gettempdir()) / "flink-agents-rag-chroma"),
)


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

    @embedding_model_setup
    @staticmethod
    def text_embedder() -> ResourceDescriptor:
        """Embedding model setup for generating text embeddings."""
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OLLAMA_SETUP,
            connection="ollama_embedding_connection",
            model=OLLAMA_EMBEDDING_MODEL,
        )

    @vector_store
    @staticmethod
    def knowledge_base() -> ResourceDescriptor:
        """Vector store setup for knowledge base."""
        return ResourceDescriptor(
            clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
            embedding_model="text_embedder",
            collection="example_knowledge_base",
            persist_directory=CHROMA_PERSIST_DIRECTORY,
        )

    @chat_model_setup
    @staticmethod
    def chat_model() -> ResourceDescriptor:
        """Chat model setup for generating responses."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_chat_connection",
            model=OLLAMA_CHAT_MODEL,
            think=False,
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Process user input and retrieve relevant context."""
        user_query = str(InputEvent.from_event(event).input)
        ctx.send_event(
            ContextRetrievalRequestEvent(
                query=user_query,
                vector_store="knowledge_base",
                max_results=3,
            )
        )

    @action(EventType.ContextRetrievalResponseEvent)
    @staticmethod
    def process_retrieved_context(event: Event, ctx: RunnerContext) -> None:
        """Process retrieved context and create enhanced chat request."""
        retrieval_event = ContextRetrievalResponseEvent.from_event(event)
        user_query = retrieval_event.query
        retrieved_docs = retrieval_event.documents

        context_text = "\n\n".join(
            [f"{i + 1}. {doc.content}" for i, doc in enumerate(retrieved_docs)]
        )

        prompt_resource = ctx.get_resource(
            "context_enhanced_prompt", ResourceType.PROMPT
        )
        enhanced_prompt = prompt_resource.format_string(
            context=context_text, user_query=user_query
        )

        ctx.send_event(
            ChatRequestEvent(
                model="chat_model",
                messages=[ChatMessage(role=MessageRole.USER, content=enhanced_prompt)],
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: Event, ctx: RunnerContext) -> None:
        """Process chat model response and generate output."""
        chat_response = ChatResponseEvent.from_event(event)
        if chat_response.response and chat_response.response.content:
            ctx.send_event(OutputEvent(output=chat_response.response.content))
