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

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
    ResourceType,
)
from flink_agents.examples.rag.agents.rag_agent import MyRAGAgent
from flink_agents.examples.rag.knowledge_base_setup import populate_knowledge_base

if __name__ == "__main__":
    print("Starting RAG Example Agent...")

    # Populate vector store with sample documents
    populate_knowledge_base()

    agent = MyRAGAgent()

    # Set up the Flink streaming environment and the Agents execution environment.
    env = StreamExecutionEnvironment.get_execution_environment()
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

    # Setup Ollama embedding and chat model connections
    agents_env.add_resource(
        "ollama_embedding_connection",
        ResourceType.EMBEDDING_MODEL_CONNECTION,
        ResourceDescriptor(clazz=ResourceName.EmbeddingModel.OLLAMA_CONNECTION),
    )
    agents_env.add_resource(
        "ollama_chat_connection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_CONNECTION,
            request_timeout=240.0,
        ),
    )

    # A small stream of example queries, keyed by the query text.
    query_stream = env.from_collection(
        [
            "What is Apache Flink?",
            "What is Apache Flink Agents?",
            "What is Python?",
        ],
    )

    # Use the RAG agent to answer each query and print the responses to stdout.
    response_stream = (
        agents_env.from_datastream(input=query_stream, key_selector=lambda x: x)
        .apply(agent)
        .to_datastream()
    )
    response_stream.print()

    # Execute the Flink pipeline.
    agents_env.execute("RAG Agent Example Job")
