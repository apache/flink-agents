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
 * Hierarchical resource class names for pointing a resource implementation in {@link
 * ResourceDescriptor}.
 *
 * <p>Structure:
 *
 * <ul>
 *   <li>Java implementation: ResourceType.PROVIDER_RESOURCEKIND
 *   <li>Python implementation: ResourceType.Python.PROVIDER_RESOURCEKIND
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Java implementation
 * ResourceName.ChatModel.OLLAMA_CONNECTION
 * ResourceName.ChatModel.OPENAI_SETUP
 *
 * // Python implementation
 * ResourceName.ChatModel.Python.OLLAMA_CONNECTION
 * }</pre>
 */
public final class ResourceName {

    private ResourceName() {}

    // ==================== ChatModel ====================
    public static final class ChatModel {

        // Anthropic
        public static final String ANTHROPIC_CONNECTION =
                "org.apache.flink.agents.integrations.chatmodels.anthropic.AnthropicChatModelConnection";
        public static final String ANTHROPIC_SETUP =
                "org.apache.flink.agents.integrations.chatmodels.anthropic.AnthropicChatModelSetup";

        // Azure
        public static final String AZURE_CONNECTION =
                "org.apache.flink.agents.integrations.chatmodels.anthropic.AzureAIChatModelConnection";
        public static final String AZURE_SETUP =
                "org.apache.flink.agents.integrations.chatmodels.anthropic.AzureAIChatModelSetup";

        // Ollama
        public static final String OLLAMA_CONNECTION =
                "org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelConnection";
        public static final String OLLAMA_SETUP =
                "org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup";

        // OpenAI
        public static final String OPENAI_CONNECTION =
                "org.apache.flink.agents.integrations.chatmodels.openai.OpenAIChatModelConnection";
        public static final String OPENAI_SETUP =
                "org.apache.flink.agents.integrations.chatmodels.openai.OpenAIChatModelSetup";

        // Python Wrapper
        public static final String PYTHON_WRAPPER_CONNECTION =
                "org.apache.flink.agents.api.chat.model.python.PythonChatModelConnection";
        public static final String PYTHON_WRAPPER_SETUP =
                "org.apache.flink.agents.api.chat.model.python.PythonChatModelSetup";

        /** Python implementations of ChatModel. */
        public static final class Python {

            // Anthropic
            public static final String ANTHROPIC_CONNECTION =
                    "flink_agents.integrations.chat_models.anthropic.anthropic_chat_model.AnthropicChatModelConnection";
            public static final String ANTHROPIC_SETUP =
                    "flink_agents.integrations.chat_models.anthropic.anthropic_chat_model.AnthropicChatModelSetup";

            // Ollama
            public static final String OLLAMA_CONNECTION =
                    "flink_agents.integrations.chat_models.ollama_chat_model.OllamaChatModelConnection";
            public static final String OLLAMA_SETUP =
                    "flink_agents.integrations.chat_models.ollama_chat_model.OllamaChatModelSetup";

            // OpenAI
            public static final String OPENAI_CONNECTION =
                    "flink_agents.integrations.chat_models.openai.openai_chat_model.OpenAIChatModelConnection";
            public static final String OPENAI_SETUP =
                    "flink_agents.integrations.chat_models.openai.openai_chat_model.OpenAIChatModelSetup";

            // Tongyi
            public static final String TONGYI_CONNECTION =
                    "flink_agents.integrations.chat_models.tongyi_chat_model.TongyiChatModelConnection";
            public static final String TONGYI_SETUP =
                    "flink_agents.integrations.chat_models.tongyi_chat_model.TongyiChatModelSetup";

            private Python() {}
        }

        private ChatModel() {}
    }

    // ==================== EmbeddingModel ====================
    public static final class EmbeddingModel {

        // Ollama
        public static final String OLLAMA_CONNECTION =
                "org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelConnection";
        public static final String OLLAMA_SETUP =
                "org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelSetup";

        // Python Wrapper
        public static final String PYTHON_WRAPPER_CONNECTION =
                "org.apache.flink.agents.api.embedding.model.python.PythonEmbeddingModelConnection";
        public static final String PYTHON_WRAPPER_SETUP =
                "org.apache.flink.agents.api.embedding.model.python.PythonEmbeddingModelSetup";

        /** Python implementations of EmbeddingModel. */
        public static final class Python {

            // Ollama
            public static final String OLLAMA_CONNECTION =
                    "flink_agents.integrations.embedding_models.local.ollama_embedding_model.OllamaEmbeddingModelConnection";
            public static final String OLLAMA_SETUP =
                    "flink_agents.integrations.embedding_models.local.ollama_embedding_model.OllamaEmbeddingModelSetup";

            // OpenAI
            public static final String OPENAI_CONNECTION =
                    "flink_agents.integrations.embedding_models.openai_embedding_model.OpenAIEmbeddingModelConnection";
            public static final String OPENAI_SETUP =
                    "flink_agents.integrations.embedding_models.openai_embedding_model.OpenAIEmbeddingModelSetup";

            private Python() {}
        }

        private EmbeddingModel() {}
    }

    // ==================== VectorStore ====================
    public static final class VectorStore {

        // Elasticsearch
        public static final String ELASTICSEARCH_VECTOR_STORE =
                "org.apache.flink.agents.integrations.vectorstores.elasticsearch.ElasticsearchVectorStore";

        // Python Wrapper
        public static final String PYTHON_WRAPPER_VECTOR_STORE =
                "org.apache.flink.agents.api.vectorstores.python.PythonVectorStore";

        public static final String PYTHON_WRAPPER_COLLECTION_MANAGEABLE_VECTOR_STORE =
                "org.apache.flink.agents.api.vectorstores.python.PythonCollectionManageableVectorStore";

        /** Python implementations of VectorStore. */
        public static final class Python {

            // Chroma
            public static final String CHROMA_VECTOR_STORE =
                    "flink_agents.integrations.vector_stores.chroma.chroma_vector_store.ChromaVectorStore";

            private Python() {}
        }

        private VectorStore() {}
    }

    // ==================== MCP ====================
    public static final String MCP_SERVER = "DECIDE_IN_RUNTIME_MCPServer";
}
