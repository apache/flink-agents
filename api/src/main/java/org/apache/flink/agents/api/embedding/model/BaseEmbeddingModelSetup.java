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

package org.apache.flink.agents.api.embedding.model;

import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Base class for embedding model setup configurations.
 *
 * <p>This class provides common setup functionality for embedding models, including connection
 * management and model configuration.
 */
public abstract class BaseEmbeddingModelSetup extends Resource {
    protected final String connectionName;
    protected String model;

    @Nullable protected BaseEmbeddingModelConnection connection;

    public BaseEmbeddingModelSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.connectionName = descriptor.getArgument("connection");
        this.model = descriptor.getArgument("model");
    }

    /**
     * Trigger construction for resource objects.
     *
     * <p>Currently, in cross-language invocation scenarios, constructing resource object within an
     * async thread may encounter issues. We resolved this issue by moving the construction of the
     * resources object out of the method to be async executed and invoking it in the main thread.
     */
    @Override
    public void open() throws Exception {
        this.connection =
                (BaseEmbeddingModelConnection)
                        resourceContext.getResource(
                                connectionName, ResourceType.EMBEDDING_MODEL_CONNECTION);
    }

    public abstract Map<String, Object> getParameters();

    @Override
    public ResourceType getResourceType() {
        return ResourceType.EMBEDDING_MODEL;
    }

    /**
     * Get the embedding model connection.
     *
     * @return The embedding model connection instance
     */
    @VisibleForTesting
    public BaseEmbeddingModelConnection getConnection() {
        Preconditions.checkNotNull(
                connection,
                "Connection is not initialized. Ensure open() is called before embed().");
        return connection;
    }

    /**
     * Get the model name.
     *
     * @return The model name
     */
    public String getModel() {
        return model;
    }

    /**
     * Record embedding token usage metrics for the given model on this setup's bound metric group.
     *
     * <p>Mirrors {@code BaseChatModelSetup#recordTokenMetrics} but records input-side tokens only,
     * since embeddings have no completion tokens. Counters are placed under the same {@code model}
     * key-value group used by chat metrics, so embedding and chat usage for a model share one
     * dimension.
     *
     * <p>Unlike the chat path, embedding calls do not run inside a plan action that hands in a
     * request-scoped metric group (vector-store, RAG, and direct calls reach this setup directly),
     * so the resource-bound metric group injected via {@link #setMetricGroup} is used instead.
     *
     * @param modelName the name of the model used
     * @param promptTokens the number of prompt tokens
     * @param totalTokens the total number of tokens reported by the provider
     */
    public void recordTokenMetrics(String modelName, long promptTokens, long totalTokens) {
        Preconditions.checkArgument(
                modelName != null && !modelName.isBlank(), "Model name must not be null or blank.");
        FlinkAgentsMetricGroup metricGroup = getMetricGroup();
        if (metricGroup == null) {
            return;
        }
        FlinkAgentsMetricGroup modelGroup = metricGroup.getSubGroup("model", modelName);
        modelGroup.getCounter("promptTokens").inc(promptTokens);
        modelGroup.getCounter("totalTokens").inc(totalTokens);
    }

    /**
     * Record the provider-reported embedding token usage, if any, onto this setup's bound metric
     * group. Called from {@link #embedWithUsage} so direct calls and vector-store/RAG paths are
     * both covered without each provider repeating the recording.
     */
    protected void recordTokenUsage(@Nullable EmbeddingTokenUsage tokenUsage) {
        if (tokenUsage == null || model == null || model.isBlank()) {
            return;
        }
        recordTokenMetrics(model, tokenUsage.getPromptTokens(), tokenUsage.getTotalTokens());
    }

    /**
     * Generate embeddings for the given text.
     *
     * <p>Token usage metrics are only recorded by {@link #embedWithUsage}; this method discards
     * provider usage because it is not returned.
     *
     * @param text The input text to generate embeddings for
     * @return An array of floating-point values representing the text embeddings
     */
    public float[] embed(String text) {
        return this.embed(text, Collections.emptyMap());
    }

    public float[] embed(String text, Map<String, Object> parameters) {
        Map<String, Object> params = this.getParameters();
        params.putAll(parameters);
        return getConnection().embed(text, params);
    }

    public EmbeddingResult<float[]> embedWithUsage(String text) {
        return embedWithUsage(text, Collections.emptyMap());
    }

    public EmbeddingResult<float[]> embedWithUsage(String text, Map<String, Object> parameters) {
        Map<String, Object> params = this.getParameters();
        params.putAll(parameters);
        BaseEmbeddingModelConnection currentConnection = getConnection();
        EmbeddingResult<float[]> result = currentConnection.embedWithUsage(text, params);
        recordTokenUsage(result.getTokenUsage());
        return result;
    }

    /**
     * Generate embeddings for multiple texts.
     *
     * @param texts The list of input texts to generate embeddings for
     * @return A list of arrays, each containing floating-point values representing the text
     *     embeddings
     */
    public List<float[]> embed(List<String> texts) {
        return this.embed(texts, Collections.emptyMap());
    }

    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
        Map<String, Object> params = this.getParameters();
        params.putAll(parameters);
        return getConnection().embed(texts, params);
    }

    public EmbeddingResult<List<float[]>> embedWithUsage(List<String> texts) {
        return embedWithUsage(texts, Collections.emptyMap());
    }

    public EmbeddingResult<List<float[]>> embedWithUsage(
            List<String> texts, Map<String, Object> parameters) {
        Map<String, Object> params = this.getParameters();
        params.putAll(parameters);
        BaseEmbeddingModelConnection currentConnection = getConnection();
        EmbeddingResult<List<float[]>> result = currentConnection.embedWithUsage(texts, params);
        recordTokenUsage(result.getTokenUsage());
        return result;
    }
}
