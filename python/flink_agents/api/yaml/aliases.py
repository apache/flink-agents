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
"""Static alias tables for the YAML loader.

Two tables:
- ``EVENT_ALIASES`` maps short event names to ``EVENT_TYPE`` constants.
- ``CLAZZ_ALIASES`` maps short provider names to fully-qualified class
  paths. The bucket is keyed on the resource type *and* the
  implementation language so the same alias (``ollama``) can refer to
  different classes across sections and languages.

For Java resources, the loader resolves the alias to the Java FQN and
wraps it in a Python-side wrapper class (see ``JAVA_WRAPPER_CLAZZ``).
"""

from typing import Dict

from flink_agents.api.events.chat_event import (
    ChatRequestEvent,
    ChatResponseEvent,
)
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
)
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.events.tool_event import (
    ToolRequestEvent,
    ToolResponseEvent,
)
from flink_agents.api.resource import ResourceName, ResourceType
from flink_agents.api.yaml.specs import Language

EVENT_ALIASES: Dict[str, str] = {
    "input": InputEvent.EVENT_TYPE,
    "output": OutputEvent.EVENT_TYPE,
    "chat_request": ChatRequestEvent.EVENT_TYPE,
    "chat_response": ChatResponseEvent.EVENT_TYPE,
    "tool_request": ToolRequestEvent.EVENT_TYPE,
    "tool_response": ToolResponseEvent.EVENT_TYPE,
    "context_retrieval_request": ContextRetrievalRequestEvent.EVENT_TYPE,
    "context_retrieval_response": ContextRetrievalResponseEvent.EVENT_TYPE,
}

# resource_type -> language -> alias -> fully-qualified class path
CLAZZ_ALIASES: Dict[ResourceType, Dict[str, Dict[str, str]]] = {
    ResourceType.CHAT_MODEL_CONNECTION: {
        "python": {
            "ollama": ResourceName.ChatModel.OLLAMA_CONNECTION,
            "openai": ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION,
            "anthropic": ResourceName.ChatModel.ANTHROPIC_CONNECTION,
            "tongyi": ResourceName.ChatModel.TONGYI_CONNECTION,
            "azure_openai": ResourceName.ChatModel.AZURE_OPENAI_CONNECTION,
        },
        "java": {
            "ollama": ResourceName.ChatModel.Java.OLLAMA_CONNECTION,
            "openai_completions": ResourceName.ChatModel.Java.OPENAI_COMPLETIONS_CONNECTION,
            "openai_responses": ResourceName.ChatModel.Java.OPENAI_RESPONSES_CONNECTION,
            "anthropic": ResourceName.ChatModel.Java.ANTHROPIC_CONNECTION,
            "azure": ResourceName.ChatModel.Java.AZURE_CONNECTION,
        },
    },
    ResourceType.CHAT_MODEL: {
        "python": {
            "ollama": ResourceName.ChatModel.OLLAMA_SETUP,
            "openai": ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP,
            "anthropic": ResourceName.ChatModel.ANTHROPIC_SETUP,
            "tongyi": ResourceName.ChatModel.TONGYI_SETUP,
            "azure_openai": ResourceName.ChatModel.AZURE_OPENAI_SETUP,
        },
        "java": {
            "ollama": ResourceName.ChatModel.Java.OLLAMA_SETUP,
            "openai_completions": ResourceName.ChatModel.Java.OPENAI_COMPLETIONS_SETUP,
            "openai_responses": ResourceName.ChatModel.Java.OPENAI_RESPONSES_SETUP,
            "anthropic": ResourceName.ChatModel.Java.ANTHROPIC_SETUP,
            "azure": ResourceName.ChatModel.Java.AZURE_SETUP,
        },
    },
    ResourceType.EMBEDDING_MODEL_CONNECTION: {
        "python": {
            "ollama": ResourceName.EmbeddingModel.OLLAMA_CONNECTION,
            "openai": ResourceName.EmbeddingModel.OPENAI_CONNECTION,
            "tongyi": ResourceName.EmbeddingModel.TONGYI_CONNECTION,
        },
        "java": {
            "ollama": ResourceName.EmbeddingModel.Java.OLLAMA_CONNECTION,
        },
    },
    ResourceType.EMBEDDING_MODEL: {
        "python": {
            "ollama": ResourceName.EmbeddingModel.OLLAMA_SETUP,
            "openai": ResourceName.EmbeddingModel.OPENAI_SETUP,
            "tongyi": ResourceName.EmbeddingModel.TONGYI_SETUP,
        },
        "java": {
            "ollama": ResourceName.EmbeddingModel.Java.OLLAMA_SETUP,
        },
    },
    ResourceType.VECTOR_STORE: {
        "python": {
            "chroma": ResourceName.VectorStore.CHROMA_VECTOR_STORE,
        },
        "java": {
            "elasticsearch": ResourceName.VectorStore.Java.ELASTICSEARCH_VECTOR_STORE,
        },
    },
}

# Python wrapper class for each cross-language-supported resource type.
# When the user writes ``type: java``, the loader resolves the alias in
# the java bucket to a Java FQN and constructs a ResourceDescriptor whose
# ``clazz`` is the wrapper below and whose ``java_clazz`` kwarg is the
# resolved Java FQN.
JAVA_WRAPPER_CLAZZ: Dict[ResourceType, str] = {
    ResourceType.CHAT_MODEL_CONNECTION: ResourceName.ChatModel.JAVA_WRAPPER_CONNECTION,
    ResourceType.CHAT_MODEL: ResourceName.ChatModel.JAVA_WRAPPER_SETUP,
    ResourceType.EMBEDDING_MODEL_CONNECTION: ResourceName.EmbeddingModel.JAVA_WRAPPER_CONNECTION,
    ResourceType.EMBEDDING_MODEL: ResourceName.EmbeddingModel.JAVA_WRAPPER_SETUP,
    ResourceType.VECTOR_STORE: ResourceName.VectorStore.JAVA_WRAPPER_VECTOR_STORE,
}


def resolve_event_type(name: str) -> str:
    """Replace an event alias with its fully-qualified event type string,
    or pass through if no alias matches.
    """
    return EVENT_ALIASES.get(name, name)


def resolve_clazz(
    name: str, resource_type: ResourceType, language: Language = "python"
) -> str:
    """Look up ``name`` in the alias bucket for ``(resource_type, language)``.

    Returns the fully-qualified class path on hit, or ``name`` unchanged
    on miss (so users can supply a fully-qualified class path directly).
    """
    bucket = CLAZZ_ALIASES.get(resource_type, {}).get(language, {})
    return bucket.get(name, name)
