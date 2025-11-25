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

package org.apache.flink.agents.examples.rag;

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.*;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelConnection;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup;
import org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelConnection;
import org.apache.flink.agents.integrations.vectorstores.elasticsearch.ElasticsearchVectorStore;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Retrieval-Augmented Generation (RAG) example using Ollama for embeddings and chat along with
 * Elasticsearch as the vector store.
 *
 * <p>This example demonstrates an agent that:
 *
 * <ul>
 *   <li>Embeds the incoming user query using an Ollama embedding model,
 *   <li>Retrieves relevant context from Elasticsearch via a {@code VectorStore},
 *   <li>Formats a prompt that includes the retrieved context, and
 *   <li>Generates a response using an Ollama chat model.
 * </ul>
 *
 * <p>Prerequisites:
 *
 * <ul>
 *   <li>Elasticsearch 8.x reachable from this example with an index that contains a {@code
 *       dense_vector} field for KNN search.
 *   <li>Ollama running locally (default {@code http://localhost:11434}) with the configured
 *       embedding and chat models available.
 * </ul>
 *
 * <p>Example Elasticsearch mapping (adjust index name, field, dims, and similarity to your needs):
 *
 * <pre>{@code
 * {
 *   "mappings": {
 *     "properties": {
 *       "content": { "type": "text" },
 *       "metadata": { "type": "object", "enabled": false },
 *       "content_vector": { "type": "dense_vector", "dims": 768, "similarity": "cosine" }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>System properties you can override:
 *
 * <ul>
 *   <li>{@code ES_HOST} (default {@code http://localhost:9200})
 *   <li>{@code ES_INDEX} (default {@code my_documents})
 *   <li>{@code ES_VECTOR_FIELD} (default {@code content_vector})
 *   <li>{@code ES_DIMS} (default {@code 768})
 *   <li>{@code ES_SIMILARITY} (default {@code cosine}) — used by the optional population step
 *   <li>Authentication (optional, used by both vector store and population step):
 *       <ul>
 *         <li>{@code ES_API_KEY_BASE64} — Base64 of {@code apiKeyId:apiKeySecret}
 *         <li>{@code ES_API_KEY_ID} and {@code ES_API_KEY_SECRET} — combined and Base64-encoded
 *         <li>{@code ES_USERNAME} and {@code ES_PASSWORD} — basic authentication
 *       </ul>
 *   <li>{@code OLLAMA_ENDPOINT} (default {@code http://localhost:11434})
 *   <li>{@code OLLAMA_EMBEDDING_MODEL} (default {@code nomic-embed-text})
 *   <li>{@code OLLAMA_CHAT_MODEL} (default {@code qwen3:8b})
 *   <li>{@code ES_POPULATE} (default {@code true}) — whether to populate sample data on startup
 * </ul>
 *
 * <p>Direct CLI flags (optional): <br>
 * Instead of or in addition to system properties, you can pass flags when starting the job. CLI
 * flags take precedence over existing system properties.
 *
 * <ul>
 *   <li>{@code --es.host=http://your-es:9200}
 *   <li>{@code --es.username=elastic} and {@code --es.password=secret}
 *   <li>{@code --es.apiKeyBase64=BASE64_ID_COLON_SECRET} or {@code --es.apiKeyId=ID} with {@code
 *       --es.apiKeySecret=SECRET}
 *   <li>{@code --es.index=my_documents}, {@code --es.vectorField=content_vector}, {@code
 *       --es.dims=768}, {@code --es.similarity=cosine}
 *   <li>{@code --ollama.endpoint=http://localhost:11434}, {@code
 *       --ollama.embeddingModel=nomic-embed-text}, {@code --ollama.chatModel=qwen3:8b}
 *   <li>{@code --es.populate=true|false}
 * </ul>
 *
 * <p>Examples:
 *
 * <pre>{@code
 * # Use API key (base64 of id:secret) and custom host
 * flink run ... -c org.apache.flink.agents.examples.rag.ElasticsearchRagExample \
 *   examples.jar --es.host=http://es:9200 --es.apiKeyBase64=XXXXX=
 *
 * # Use basic authentication
 * flink run ... -c org.apache.flink.agents.examples.rag.ElasticsearchRagExample \
 *   examples.jar --es.host=http://es:9200 --es.username=elastic --es.password=secret
 * }</pre>
 *
 * <p>Notes:
 *
 * <ul>
 *   <li>Authentication can be provided via either CLI flags or System properties; API key takes
 *       precedence over basic auth when both are present.
 *   <li>The optional knowledge base population step uses the same System properties to connect to
 *       Elasticsearch.
 * </ul>
 *
 * <p>Running the example will:
 *
 * <ol>
 *   <li>Optionally populate the Elasticsearch index with sample documents and stored vectors,
 *   <li>Create a simple agent pipeline that retrieves context from Elasticsearch, and
 *   <li>Print the model's answers for a set of example queries.
 * </ol>
 */
public class ElasticsearchRagExample {

    public static class MyRagAgent extends Agent {

        @Prompt
        public static org.apache.flink.agents.api.prompt.Prompt contextEnhancedPrompt() {
            String template =
                    "Based on the following context, please answer the user's question.\n\n"
                            + "Context:\n{context}\n\n"
                            + "User Question:\n{user_query}\n\n"
                            + "Please provide a helpful answer based on the context provided.";
            return new org.apache.flink.agents.api.prompt.Prompt(template);
        }

        @EmbeddingModelSetup
        public static ResourceDescriptor textEmbedder() {
            // Embedding setup referencing the embedding connection name
            return ResourceDescriptor.Builder.newBuilder(
                            OllamaEmbeddingModelConnection.class.getName())
                    .addInitialArgument("connection", "ollamaEmbeddingConnection")
                    .addInitialArgument(
                            "model",
                            System.getProperty("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text"))
                    .build();
        }

        @ChatModelConnection
        public static ResourceDescriptor ollamaChatModelConnection() {
            return ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                    .addInitialArgument(
                            "endpoint",
                            System.getProperty("OLLAMA_ENDPOINT", "http://localhost:11434"))
                    .addInitialArgument("requestTimeout", 120)
                    .build();
        }

        @ChatModelSetup
        public static ResourceDescriptor chatModel() {
            return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                    .addInitialArgument("connection", "ollamaChatModelConnection")
                    .addInitialArgument(
                            "model", System.getProperty("OLLAMA_CHAT_MODEL", "qwen3:8b"))
                    .build();
        }

        @VectorStore
        public static ResourceDescriptor knowledgeBase() {
            ResourceDescriptor.Builder builder =
                    ResourceDescriptor.Builder.newBuilder(ElasticsearchVectorStore.class.getName())
                            .addInitialArgument("embedding_model", "textEmbedder")
                            .addInitialArgument(
                                    "index", System.getProperty("ES_INDEX", "my_documents"))
                            .addInitialArgument(
                                    "vector_field",
                                    System.getProperty("ES_VECTOR_FIELD", "content_vector"))
                            .addInitialArgument("dims", Integer.getInteger("ES_DIMS", 768))
                            .addInitialArgument(
                                    "host", System.getProperty("ES_HOST", "http://localhost:9200"));

            // Optional authentication
            String apiKeyBase64 = System.getProperty("ES_API_KEY_BASE64");
            String apiKeyId = System.getProperty("ES_API_KEY_ID");
            String apiKeySecret = System.getProperty("ES_API_KEY_SECRET");
            String username = System.getProperty("ES_USERNAME");
            String password = System.getProperty("ES_PASSWORD");

            if (apiKeyBase64 != null && !apiKeyBase64.isEmpty()) {
                builder.addInitialArgument("api_key_base64", apiKeyBase64);
            } else if (apiKeyId != null
                    && apiKeySecret != null
                    && !apiKeyId.isEmpty()
                    && !apiKeySecret.isEmpty()) {
                builder.addInitialArgument("api_key_id", apiKeyId)
                        .addInitialArgument("api_key_secret", apiKeySecret);
            } else if (username != null
                    && password != null
                    && !username.isEmpty()
                    && !password.isEmpty()) {
                builder.addInitialArgument("username", username)
                        .addInitialArgument("password", password);
            }

            return builder.build();
        }

        /**
         * Converts an incoming {@link InputEvent} into a {@link ContextRetrievalRequestEvent} that
         * asks the vector store to fetch relevant documents for the input string. The vector store
         * resource is referenced by name ({@code "knowledgeBase"}).
         */
        @Action(listenEvents = {InputEvent.class})
        public static void processInput(InputEvent event, RunnerContext ctx) {
            ctx.sendEvent(
                    new ContextRetrievalRequestEvent((String) event.getInput(), "knowledgeBase"));
        }

        /**
         * Receives retrieved documents from the vector store, constructs a context string, formats
         * the prompt using the {@code contextEnhancedPrompt}, and emits a {@link ChatRequestEvent}
         * targeting the configured chat model.
         *
         * @param event contains the user query and the list of retrieved documents
         * @param context provides access to resources (e.g., the prompt) and lets the agent send
         *     the next event
         */
        @Action(listenEvents = {ContextRetrievalResponseEvent.class})
        public static void processRetrievedContext(
                ContextRetrievalResponseEvent<Map<String, Object>> event, RunnerContext context)
                throws Exception {
            final String userQuery = event.getQuery();
            final List<Document<Map<String, Object>>> docs = event.getDocuments();

            // Build context text from retrieved documents
            List<String> items = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                Object content = docs.get(i).getContent();
                items.add(String.format("%d. %s", i + 1, content));
            }
            String contextText = String.join("\n\n", items);

            // Format enhanced prompt
            org.apache.flink.agents.api.prompt.Prompt prompt =
                    (org.apache.flink.agents.api.prompt.Prompt)
                            context.getResource("contextEnhancedPrompt", ResourceType.PROMPT);

            String enhanced =
                    prompt.formatString(
                            Map.of(
                                    "context", contextText,
                                    "user_query", userQuery));

            // Send chat request
            ChatMessage userMsg = new ChatMessage(MessageRole.USER, enhanced);
            context.sendEvent(new ChatRequestEvent("chatModel", List.of(userMsg)));
        }

        /**
         * Handles the final {@link ChatResponseEvent} from the model and forwards the text back as
         * an {@link OutputEvent} to the outside world.
         */
        @Action(listenEvents = ChatResponseEvent.class)
        public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
            String output = event.getResponse() != null ? event.getResponse().getContent() : "";
            ctx.sendEvent(new OutputEvent(output));
        }
    }

    /**
     * Entry point for the example. Optionally populates the Elasticsearch index with sample data
     * and then builds a simple DataStream pipeline that sends example queries through the RAG
     * agent.
     *
     * <p>System properties used by the optional population step:
     *
     * <ul>
     *   <li>{@code ES_POPULATE} — whether to insert sample docs and vectors (default {@code true})
     *   <li>{@code ES_HOST}, {@code ES_INDEX}, {@code ES_VECTOR_FIELD}, {@code ES_DIMS}, {@code
     *       ES_SIMILARITY}
     *   <li>{@code OLLAMA_ENDPOINT}, {@code OLLAMA_EMBEDDING_MODEL}
     * </ul>
     */
    public static void main(String[] args) throws Exception {
        System.out.println("Starting Elasticsearch RAG Example...");

        // Parse CLI arguments and set System properties so both the knowledgeBase() and
        // the optional knowledge base population step can use them.
        parseArgsAndSetProperties(args);

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Optionally populate ES with sample data for the demo
        if (Boolean.parseBoolean(System.getProperty("ES_POPULATE", "true"))) {
            String esHost = System.getProperty("ES_HOST", "http://localhost:9200");
            String index = System.getProperty("ES_INDEX", "my_documents");
            String vectorField = System.getProperty("ES_VECTOR_FIELD", "content_vector");
            int dims = Integer.getInteger("ES_DIMS", 768);
            String similarity = System.getProperty("ES_SIMILARITY", "cosine");
            String ollamaEndpoint = System.getProperty("OLLAMA_ENDPOINT", "http://localhost:11434");
            String embeddingModel =
                    System.getProperty("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text");

            try {
                ElasticsearchKnowledgeBaseSetup.populate(
                        esHost,
                        index,
                        vectorField,
                        dims,
                        similarity,
                        ollamaEndpoint,
                        embeddingModel);
            } catch (Exception e) {
                System.err.println(
                        "[KB Setup] Failed to populate ES sample data: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Prepare example queries as a DataStream
        DataStream<String> queries =
                env.fromData(
                        "What is Apache Flink?",
                        "What is Apache Flink Agents?",
                        "What is Python?",
                        "What is vector store?",
                        "Does flink supports k8s?",
                        "What is the capability of agentic system");

        MyRagAgent agent = new MyRagAgent();

        // Use DataStream pipeline instead of local list execution
        agentsEnv.fromDataStream(queries).apply(agent).toDataStream().print();

        // Execute the Flink job
        agentsEnv.execute();
    }

    /**
     * Very small CLI parser for convenience so you can pass ES host and credentials directly.
     * Supported flags (CLI args have precedence over existing System properties): --es.host=URL,
     * --es.username=USER, --es.password=PASS, --es.apiKeyBase64=BASE64, --es.apiKeyId=ID,
     * --es.apiKeySecret=SECRET, plus optional index settings like --es.index, --es.vectorField,
     * --es.dims, --es.similarity, and Ollama settings --ollama.endpoint, --ollama.embeddingModel,
     * --ollama.chatModel.
     */
    private static void parseArgsAndSetProperties(String[] args) {
        if (args == null) return;
        for (String a : args) {
            if (a == null) continue;
            if (!a.startsWith("--")) continue;
            int eq = a.indexOf('=');
            String key;
            String val = "";
            if (eq > 2) {
                key = a.substring(2, eq);
                val = a.substring(eq + 1);
            } else {
                key = a.substring(2);
            }

            if ("es.host".equals(key)) {
                System.setProperty("ES_HOST", val);
            } else if ("es.username".equals(key)) {
                System.setProperty("ES_USERNAME", val);
            } else if ("es.password".equals(key)) {
                System.setProperty("ES_PASSWORD", val);
            } else if ("es.apiKeyBase64".equals(key)) {
                System.setProperty("ES_API_KEY_BASE64", val);
            } else if ("es.apiKeyId".equals(key)) {
                System.setProperty("ES_API_KEY_ID", val);
            } else if ("es.apiKeySecret".equals(key)) {
                System.setProperty("ES_API_KEY_SECRET", val);
            } else if ("es.index".equals(key)) {
                System.setProperty("ES_INDEX", val);
            } else if ("es.vectorField".equals(key)) {
                System.setProperty("ES_VECTOR_FIELD", val);
            } else if ("es.dims".equals(key)) {
                System.setProperty("ES_DIMS", val);
            } else if ("es.similarity".equals(key)) {
                System.setProperty("ES_SIMILARITY", val);
            } else if ("ollama.endpoint".equals(key)) {
                System.setProperty("OLLAMA_ENDPOINT", val);
            } else if ("ollama.embeddingModel".equals(key)) {
                System.setProperty("OLLAMA_EMBEDDING_MODEL", val);
            } else if ("ollama.chatModel".equals(key)) {
                System.setProperty("OLLAMA_CHAT_MODEL", val);
            } else if ("es.populate".equals(key)) {
                System.setProperty("ES_POPULATE", val);
            } else {
                // ignore unknown flags
            }
        }
    }
}
