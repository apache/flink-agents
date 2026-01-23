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

package org.apache.flink.agents.api.resource;

/**
 * Constant strings for pointing a resource implementation in {@link ResourceDescriptor}.
 *
 * <p>TODO: Could use service provider interface to simplify the definition of constant in the
 * future.
 */
public class Constant {
    // Built-in ChatModel
    // python wrapper
    public static String PYTHON_CHAT_MODEL_CONNECTION =
            "org.apache.flink.agents.api.chat.model.python.PythonChatModelConnection";
    public static String PYTHON_CHAT_MODEL_SETUP =
            "org.apache.flink.agents.api.chat.model.python.PythonChatModelSetup";

    // ollama
    public static String OLLAMA_CHAT_MODEL_CONNECTION =
            "org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelConnection";
    public static String OLLAMA_CHAT_MODEL_SETUP =
            "org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup";

    // anthropic
    public static String ANTHROPIC_CHAT_MODEL_CONNECTION =
            "org.apache.flink.agents.integrations.chatmodels.anthropic.AnthropicChatModelConnection";
    public static String ANTHROPIC_CHAT_MODEL_SETUP =
            "org.apache.flink.agents.integrations.chatmodels.anthropic.AnthropicChatModelSetup";

    // Azure
    public static String AZURE_CHAT_MODEL_CONNECTION =
            "org.apache.flink.agents.integrations.chatmodels.anthropic.AzureAIChatModelConnection";
    public static String AZURE_CHAT_MODEL_SETUP =
            "org.apache.flink.agents.integrations.chatmodels.anthropic.AzureAIChatModelSetup";

    // OpenAI
    public static String OPENAI_CHAT_MODEL_CONNECTION =
            "org.apache.flink.agents.integrations.chatmodels.openai.OpenAIChatModelConnection";
    public static String OPENAI_CHAT_MODEL_SETUP =
            "org.apache.flink.agents.integrations.chatmodels.openai.OpenAIChatModelSetup";

    // Built-in EmbeddingModel
    // python wrapper
    public static String PYTHON_EMBEDDING_MODEL_CONNECTION =
            "org.apache.flink.agents.api.embedding.model.python.PythonEmbeddingModelConnection";
    public static String PYTHON_EMBEDDING_MODEL_SETUP =
            "org.apache.flink.agents.api.embedding.model.python.PythonEmbeddingModelSetup";

    // ollama
    public static String OLLAMA_EMBEDDING_MODEL_CONNECTION =
            "org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelConnection";
    public static String OLLAMA_EMBEDDING_MODEL_SETUP =
            "org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelSetup";

    // Built-in VectorStore
    // python wrapper
    public static String PYTHON_VECTOR_STORE =
            "org.apache.flink.agents.api.vectorstores.python.PythonVectorStore";
    public static String PYTHON_COLLECTION_MANAGEABLE_VECTOR_STORE =
            "org.apache.flink.agents.api.vectorstores.python.PythonCollectionManageableVectorStore";

    // elasticsearch
    public static String ELASTICSEARCH_VECTOR_STORE =
            "org.apache.flink.agents.integrations.vectorstores.elasticsearch.ElasticsearchVectorStore";

    // MCP
    public static String MCP_SERVER = "DECIDE_IN_RUNTIME_MCPServer";
}
