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

from flink_agents.api.function import PythonFunction
from flink_agents.examples.rag.agents.rag_agent import (
    CHROMA_PERSIST_DIRECTORY as AGENT_CHROMA_DIRECTORY,
)
from flink_agents.examples.rag.agents.rag_agent import MyRAGAgent
from flink_agents.examples.rag.knowledge_base_setup import (
    CHROMA_PERSIST_DIRECTORY as SETUP_CHROMA_DIRECTORY,
)


def test_rag_agent_actions_resolve_from_an_importable_module() -> None:
    actions = [
        MyRAGAgent.process_input,
        MyRAGAgent.process_retrieved_context,
        MyRAGAgent.process_chat_response,
    ]

    for action in actions:
        descriptor = PythonFunction.from_callable(action)
        assert descriptor.module == "flink_agents.examples.rag.agents.rag_agent"
        assert descriptor.as_callable() is action


def test_rag_vector_store_uses_cross_process_persistence() -> None:
    vector_store = MyRAGAgent.knowledge_base()

    assert AGENT_CHROMA_DIRECTORY == SETUP_CHROMA_DIRECTORY
    assert vector_store.arguments["persist_directory"] == SETUP_CHROMA_DIRECTORY
