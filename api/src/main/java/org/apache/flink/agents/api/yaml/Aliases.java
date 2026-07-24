/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.agents.api.yaml;

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Static alias tables for the YAML loader.
 *
 * <p>Two tables:
 *
 * <ul>
 *   <li>{@link #EVENT_ALIASES} maps short event names to {@code EVENT_TYPE} constants.
 *   <li>{@link #CLAZZ_ALIASES} maps short provider names to fully-qualified class paths, keyed on
 *       resource type <em>and</em> implementation language so the same alias (e.g. {@code ollama})
 *       can refer to different classes across sections and languages.
 * </ul>
 *
 * <p>For Python resources, the loader resolves the alias to the Python FQN and wraps it in a
 * Java-side wrapper class (see {@link #PYTHON_WRAPPER_CLAZZ}).
 */
public final class Aliases {

    /** Short event alias to fully-qualified {@code EVENT_TYPE} string. */
    public static final Map<String, String> EVENT_ALIASES;

    /** ResourceType to Language to alias to fully-qualified class path. */
    public static final Map<ResourceType, Map<Language, Map<String, String>>> CLAZZ_ALIASES;

    /**
     * ResourceType to Java-side wrapper FQN that embeds a Python implementation. Used when a YAML
     * resource declares {@code type: python} so the Java host wraps the Python class through an
     * existing PythonResourceWrapper implementation.
     */
    public static final Map<ResourceType, String> PYTHON_WRAPPER_CLAZZ;

    static {
        Map<String, String> ev = new HashMap<>();
        ev.put("input", InputEvent.EVENT_TYPE);
        ev.put("output", OutputEvent.EVENT_TYPE);
        ev.put("chat_request", ChatRequestEvent.EVENT_TYPE);
        ev.put("chat_response", ChatResponseEvent.EVENT_TYPE);
        ev.put("tool_request", ToolRequestEvent.EVENT_TYPE);
        ev.put("tool_response", ToolResponseEvent.EVENT_TYPE);
        ev.put("context_retrieval_request", ContextRetrievalRequestEvent.EVENT_TYPE);
        ev.put("context_retrieval_response", ContextRetrievalResponseEvent.EVENT_TYPE);
        EVENT_ALIASES = Collections.unmodifiableMap(ev);

        Map<ResourceType, Map<Language, Map<String, String>>> ca =
                new EnumMap<>(ResourceType.class);

        // CHAT_MODEL_CONNECTION
        Map<String, String> chatConnJava = new HashMap<>();
        chatConnJava.put("ollama", ResourceName.ChatModel.OLLAMA_CONNECTION);
        chatConnJava.put(
                "openai_completions", ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION);
        chatConnJava.put("openai_responses", ResourceName.ChatModel.OPENAI_RESPONSES_CONNECTION);
        chatConnJava.put("anthropic", ResourceName.ChatModel.ANTHROPIC_CONNECTION);
        chatConnJava.put("azure", ResourceName.ChatModel.AZURE_CONNECTION);
        chatConnJava.put("bedrock", ResourceName.ChatModel.BEDROCK_CONNECTION);
        chatConnJava.put("watsonx", ResourceName.ChatModel.WATSONX_CONNECTION);
        Map<String, String> chatConnPython = new HashMap<>();
        chatConnPython.put("ollama", ResourceName.ChatModel.Python.OLLAMA_CONNECTION);
        chatConnPython.put("openai", ResourceName.ChatModel.Python.OPENAI_COMPLETIONS_CONNECTION);
        chatConnPython.put("anthropic", ResourceName.ChatModel.Python.ANTHROPIC_CONNECTION);
        chatConnPython.put("tongyi", ResourceName.ChatModel.Python.TONGYI_CONNECTION);
        chatConnPython.put("azure_openai", ResourceName.ChatModel.Python.AZURE_OPENAI_CONNECTION);
        chatConnPython.put("watsonx", ResourceName.ChatModel.Python.WATSONX_CONNECTION);
        ca.put(ResourceType.CHAT_MODEL_CONNECTION, buildLangBuckets(chatConnJava, chatConnPython));

        // CHAT_MODEL
        Map<String, String> chatJava = new HashMap<>();
        chatJava.put("ollama", ResourceName.ChatModel.OLLAMA_SETUP);
        chatJava.put("openai_completions", ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP);
        chatJava.put("openai_responses", ResourceName.ChatModel.OPENAI_RESPONSES_SETUP);
        chatJava.put("anthropic", ResourceName.ChatModel.ANTHROPIC_SETUP);
        chatJava.put("azure", ResourceName.ChatModel.AZURE_SETUP);
        chatJava.put("bedrock", ResourceName.ChatModel.BEDROCK_SETUP);
        chatJava.put("watsonx", ResourceName.ChatModel.WATSONX_SETUP);
        Map<String, String> chatPython = new HashMap<>();
        chatPython.put("ollama", ResourceName.ChatModel.Python.OLLAMA_SETUP);
        chatPython.put("openai", ResourceName.ChatModel.Python.OPENAI_COMPLETIONS_SETUP);
        chatPython.put("anthropic", ResourceName.ChatModel.Python.ANTHROPIC_SETUP);
        chatPython.put("tongyi", ResourceName.ChatModel.Python.TONGYI_SETUP);
        chatPython.put("azure_openai", ResourceName.ChatModel.Python.AZURE_OPENAI_SETUP);
        chatPython.put("watsonx", ResourceName.ChatModel.Python.WATSONX_SETUP);
        ca.put(ResourceType.CHAT_MODEL, buildLangBuckets(chatJava, chatPython));

        // EMBEDDING_MODEL_CONNECTION
        Map<String, String> embConnJava = new HashMap<>();
        embConnJava.put("ollama", ResourceName.EmbeddingModel.OLLAMA_CONNECTION);
        embConnJava.put("bedrock", ResourceName.EmbeddingModel.BEDROCK_CONNECTION);
        Map<String, String> embConnPython = new HashMap<>();
        embConnPython.put("ollama", ResourceName.EmbeddingModel.Python.OLLAMA_CONNECTION);
        embConnPython.put("openai", ResourceName.EmbeddingModel.Python.OPENAI_CONNECTION);
        embConnPython.put("tongyi", ResourceName.EmbeddingModel.Python.TONGYI_CONNECTION);
        ca.put(
                ResourceType.EMBEDDING_MODEL_CONNECTION,
                buildLangBuckets(embConnJava, embConnPython));

        // EMBEDDING_MODEL
        Map<String, String> embJava = new HashMap<>();
        embJava.put("ollama", ResourceName.EmbeddingModel.OLLAMA_SETUP);
        embJava.put("bedrock", ResourceName.EmbeddingModel.BEDROCK_SETUP);
        Map<String, String> embPython = new HashMap<>();
        embPython.put("ollama", ResourceName.EmbeddingModel.Python.OLLAMA_SETUP);
        embPython.put("openai", ResourceName.EmbeddingModel.Python.OPENAI_SETUP);
        embPython.put("tongyi", ResourceName.EmbeddingModel.Python.TONGYI_SETUP);
        ca.put(ResourceType.EMBEDDING_MODEL, buildLangBuckets(embJava, embPython));

        // VECTOR_STORE
        Map<String, String> vsJava = new HashMap<>();
        vsJava.put("elasticsearch", ResourceName.VectorStore.ELASTICSEARCH_VECTOR_STORE);
        Map<String, String> vsPython = new HashMap<>();
        vsPython.put("chroma", ResourceName.VectorStore.Python.CHROMA_VECTOR_STORE);
        ca.put(ResourceType.VECTOR_STORE, buildLangBuckets(vsJava, vsPython));

        CLAZZ_ALIASES = Collections.unmodifiableMap(ca);

        Map<ResourceType, String> wrap = new EnumMap<>(ResourceType.class);
        wrap.put(
                ResourceType.CHAT_MODEL_CONNECTION,
                ResourceName.ChatModel.PYTHON_WRAPPER_CONNECTION);
        wrap.put(ResourceType.CHAT_MODEL, ResourceName.ChatModel.PYTHON_WRAPPER_SETUP);
        wrap.put(
                ResourceType.EMBEDDING_MODEL_CONNECTION,
                ResourceName.EmbeddingModel.PYTHON_WRAPPER_CONNECTION);
        wrap.put(ResourceType.EMBEDDING_MODEL, ResourceName.EmbeddingModel.PYTHON_WRAPPER_SETUP);
        wrap.put(ResourceType.VECTOR_STORE, ResourceName.VectorStore.PYTHON_WRAPPER_VECTOR_STORE);
        PYTHON_WRAPPER_CLAZZ = Collections.unmodifiableMap(wrap);
    }

    private Aliases() {}

    private static Map<Language, Map<String, String>> buildLangBuckets(
            Map<String, String> javaBucket, Map<String, String> pythonBucket) {
        Map<Language, Map<String, String>> out = new EnumMap<>(Language.class);
        out.put(Language.JAVA, Collections.unmodifiableMap(new HashMap<>(javaBucket)));
        out.put(Language.PYTHON, Collections.unmodifiableMap(new HashMap<>(pythonBucket)));
        return Collections.unmodifiableMap(out);
    }

    /** Look up an event alias; return {@code name} unchanged on miss. */
    public static String resolveEventType(String name) {
        return EVENT_ALIASES.getOrDefault(name, name);
    }

    /**
     * Look up a class alias for {@code (resourceType, language)}; return {@code name} unchanged on
     * miss.
     */
    public static String resolveClazz(String name, ResourceType resourceType, Language language) {
        Map<Language, Map<String, String>> byLang = CLAZZ_ALIASES.get(resourceType);
        if (byLang == null) {
            return name;
        }
        Map<String, String> bucket = byLang.get(language);
        if (bucket == null) {
            return name;
        }
        return bucket.getOrDefault(name, name);
    }
}
