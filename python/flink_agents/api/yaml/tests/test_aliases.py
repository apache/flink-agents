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
from flink_agents.api.yaml.aliases import (
    CLAZZ_ALIASES,
    EVENT_ALIASES,
    JAVA_WRAPPER_CLAZZ,
    resolve_clazz,
    resolve_event_type,
)


def test_event_aliases_map_to_real_event_types() -> None:
    assert EVENT_ALIASES["input"] == InputEvent.EVENT_TYPE
    assert EVENT_ALIASES["output"] == OutputEvent.EVENT_TYPE
    assert EVENT_ALIASES["chat_request"] == ChatRequestEvent.EVENT_TYPE
    assert EVENT_ALIASES["chat_response"] == ChatResponseEvent.EVENT_TYPE
    assert EVENT_ALIASES["tool_request"] == ToolRequestEvent.EVENT_TYPE
    assert EVENT_ALIASES["tool_response"] == ToolResponseEvent.EVENT_TYPE
    assert (
        EVENT_ALIASES["context_retrieval_request"]
        == ContextRetrievalRequestEvent.EVENT_TYPE
    )
    assert (
        EVENT_ALIASES["context_retrieval_response"]
        == ContextRetrievalResponseEvent.EVENT_TYPE
    )


def test_resolve_event_type_replaces_alias() -> None:
    assert resolve_event_type("input") == InputEvent.EVENT_TYPE


def test_resolve_event_type_passes_through_custom() -> None:
    assert resolve_event_type("my_custom_event") == "my_custom_event"


def test_clazz_aliases_are_strings_with_dots() -> None:
    assert CLAZZ_ALIASES
    for resource_type, lang_map in CLAZZ_ALIASES.items():
        assert isinstance(resource_type, ResourceType)
        assert lang_map, f"empty lang map for {resource_type}"
        for lang, bucket in lang_map.items():
            assert bucket, f"empty alias bucket for ({resource_type}, {lang})"
            for alias, fqn in bucket.items():
                assert isinstance(alias, str)
                assert isinstance(fqn, str)
                assert "." in fqn, (
                    f"alias {alias!r} -> {fqn!r} in ({resource_type}, {lang}) is "
                    "not a qualified name"
                )


def test_resolve_clazz_replaces_alias_per_resource_type() -> None:
    # Same short name resolves differently per resource type
    conn = resolve_clazz("ollama", ResourceType.CHAT_MODEL_CONNECTION)
    setup = resolve_clazz("ollama", ResourceType.CHAT_MODEL)
    embed_conn = resolve_clazz("ollama", ResourceType.EMBEDDING_MODEL_CONNECTION)
    assert conn.endswith("OllamaChatModelConnection")
    assert setup.endswith("OllamaChatModelSetup")
    assert embed_conn.endswith("OllamaEmbeddingModelConnection")


def test_resolve_clazz_passes_through_fqn() -> None:
    assert (
        resolve_clazz("my.custom.Klass", ResourceType.CHAT_MODEL) == "my.custom.Klass"
    )


def test_resolve_clazz_unknown_alias_passes_through() -> None:
    assert resolve_clazz("nonexistent", ResourceType.CHAT_MODEL) == "nonexistent"


def test_clazz_aliases_have_per_language_buckets() -> None:
    for resource_type, lang_map in CLAZZ_ALIASES.items():
        assert "python" in lang_map, f"missing python bucket for {resource_type}"
        # Java bucket optional; some resource types are Python-only
        for lang, bucket in lang_map.items():
            assert bucket, f"empty bucket for ({resource_type}, {lang})"
            for alias, fqn in bucket.items():
                assert isinstance(alias, str)
                assert isinstance(fqn, str)
                assert "." in fqn


def test_resolve_clazz_dispatches_on_language() -> None:
    py = resolve_clazz("ollama", ResourceType.CHAT_MODEL_CONNECTION, "python")
    java = resolve_clazz("ollama", ResourceType.CHAT_MODEL_CONNECTION, "java")
    assert "OllamaChatModelConnection" in py
    assert "OllamaChatModelConnection" in java
    # Java FQN starts with `org.apache.flink.agents`
    assert java.startswith("org.apache.flink.agents")
    assert py.startswith("flink_agents")


def test_watsonx_aliases_resolve_for_java_and_python() -> None:
    assert (
        resolve_clazz("watsonx", ResourceType.CHAT_MODEL_CONNECTION, "python")
        == ResourceName.ChatModel.WATSONX_CONNECTION
    )
    assert (
        resolve_clazz("watsonx", ResourceType.CHAT_MODEL, "python")
        == ResourceName.ChatModel.WATSONX_SETUP
    )
    assert (
        resolve_clazz("watsonx", ResourceType.CHAT_MODEL_CONNECTION, "java")
        == ResourceName.ChatModel.Java.WATSONX_CONNECTION
    )
    assert (
        resolve_clazz("watsonx", ResourceType.CHAT_MODEL, "java")
        == ResourceName.ChatModel.Java.WATSONX_SETUP
    )


def test_resolve_clazz_default_language_is_python() -> None:
    default = resolve_clazz("ollama", ResourceType.CHAT_MODEL_CONNECTION)
    explicit = resolve_clazz("ollama", ResourceType.CHAT_MODEL_CONNECTION, "python")
    assert default == explicit


def test_java_wrapper_clazz_table_covers_supported_types() -> None:
    # The Python-side wrappers must exist for every cross-language type
    expected = {
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceType.CHAT_MODEL,
        ResourceType.EMBEDDING_MODEL_CONNECTION,
        ResourceType.EMBEDDING_MODEL,
        ResourceType.VECTOR_STORE,
    }
    assert set(JAVA_WRAPPER_CLAZZ.keys()) == expected
    for fqn in JAVA_WRAPPER_CLAZZ.values():
        assert "." in fqn
