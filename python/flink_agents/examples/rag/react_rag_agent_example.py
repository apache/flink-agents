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
from pyflink.datastream import StreamExecutionEnvironment

from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
    ResourceType,
)
from flink_agents.api.tools.vector_store_search_tool import VectorStoreSearchTool
from flink_agents.examples.rag.agents.rag_agent import (
    CHROMA_PERSIST_DIRECTORY,
    OLLAMA_CHAT_MODEL,
    OLLAMA_EMBEDDING_MODEL,
)
from flink_agents.examples.rag.knowledge_base_setup import populate_knowledge_base


def main() -> None:
    """Let a ReAct agent decide when to search a knowledge base.

    Unlike the RAG example, which always retrieves context before calling the
    chat model, this agent exposes the vector store as a tool. The model decides
    whether to search, what to search for, and whether to search again.
    """
    populate_knowledge_base()

    env = StreamExecutionEnvironment.get_execution_environment()
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

    agents_env.add_resource(
        "ollama_embedding_connection",
        ResourceType.EMBEDDING_MODEL_CONNECTION,
        ResourceDescriptor(clazz=ResourceName.EmbeddingModel.OLLAMA_CONNECTION),
    ).add_resource(
        "text_embedder",
        ResourceType.EMBEDDING_MODEL,
        ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OLLAMA_SETUP,
            connection="ollama_embedding_connection",
            model=OLLAMA_EMBEDDING_MODEL,
        ),
    ).add_resource(
        "knowledge_base",
        ResourceType.VECTOR_STORE,
        ResourceDescriptor(
            clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
            embedding_model="text_embedder",
            collection="example_knowledge_base",
            persist_directory=CHROMA_PERSIST_DIRECTORY,
        ),
    ).add_resource(
        # Register the tool under the same name as its ``name`` argument.
        "search_knowledge_base",
        ResourceType.TOOL,
        VectorStoreSearchTool(
            name="search_knowledge_base",
            description=(
                "Search a knowledge base about Apache Flink, Apache Flink Agents, "
                "vector stores and context retrieval."
            ),
            vector_store="knowledge_base",
            max_results=2,
        ),
    ).add_resource(
        "ollama_chat_connection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, request_timeout=240.0
        ),
    )

    agent = ReActAgent(
        chat_model=ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_chat_connection",
            model=OLLAMA_CHAT_MODEL,
            tools=["search_knowledge_base"],
            think=False,
        ),
    )

    query_stream = env.from_collection(
        [
            "What is Apache Flink Agents?",
            "What is 2 + 3?",
        ],
    )

    response_stream = (
        agents_env.from_datastream(input=query_stream, key_selector=lambda x: x)
        .apply(agent)
        .to_datastream()
    )
    response_stream.print()

    agents_env.execute("ReAct RAG Agent Example Job")


if __name__ == "__main__":
    main()
