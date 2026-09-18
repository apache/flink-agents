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
package org.apache.flink.agents.integrations.embeddingmodels.openai;

import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.util.HashMap;
import java.util.Map;

/**
 * Embedding model setup for the OpenAI Embeddings API.
 *
 * <p>Arguments mirror the Python {@code OpenAIEmbeddingModelSetup}: {@code connection} and {@code
 * model} (required), {@code encoding_format} (default {@code "float"}, or {@code "base64"}), {@code
 * dimensions} (text-embedding-3 models only), {@code user}, and {@code additional_kwargs}, a map of
 * extra request body properties sent with every request whose keys may not repeat the typed request
 * fields. Blank string arguments are treated as absent. Per-call parameters override these entry by
 * entry, so a per-call {@code additional_kwargs} map replaces the setup's map rather than merging
 * with it.
 */
public class OpenAIEmbeddingModelSetup extends BaseEmbeddingModelSetup {

    static final String DEFAULT_ENCODING_FORMAT = "float";
    static final String CONNECTION = "connection";

    private final String encodingFormat;
    private final Integer dimensions;
    private final String user;
    private final Map<String, Object> additionalKwargs;

    public OpenAIEmbeddingModelSetup(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        // Fail fast at construction; the connection re-applies the same rules per call so that
        // per-call overrides are validated identically.
        super(validate(descriptor), resourceContext);
        this.encodingFormat =
                OpenAIEmbeddingModelConnection.parseEncodingFormat(
                        descriptor.getArgument(OpenAIEmbeddingModelConnection.ENCODING_FORMAT));
        this.dimensions =
                OpenAIEmbeddingModelConnection.parseDimensions(
                        descriptor.getArgument(OpenAIEmbeddingModelConnection.DIMENSIONS));
        this.user =
                OpenAIEmbeddingModelConnection.blankToNull(
                        OpenAIEmbeddingModelConnection.requireString(
                                descriptor.getArgument(OpenAIEmbeddingModelConnection.USER),
                                OpenAIEmbeddingModelConnection.USER));
        this.additionalKwargs =
                OpenAIEmbeddingModelConnection.parseAdditionalKwargs(
                        descriptor.getArgument(OpenAIEmbeddingModelConnection.ADDITIONAL_KWARGS));
        // Values are sent as JSON on every request; probe the conversion here so a value the SDK
        // cannot
        // serialize fails at construction rather than on the first embed call.
        for (Map.Entry<String, Object> entry : additionalKwargs.entrySet()) {
            if (entry.getValue() != null) {
                OpenAIEmbeddingModelConnection.toJsonValue(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Validates {@code connection} and {@code model} before {@link BaseEmbeddingModelSetup} casts
     * them to Strings, so a missing or non-string value fails here with a named error instead of a
     * {@link ClassCastException} or a lookup failure at {@code open()}.
     */
    private static ResourceDescriptor validate(ResourceDescriptor descriptor) {
        Object connection = descriptor.getArgument(CONNECTION);
        String name = OpenAIEmbeddingModelConnection.requireString(connection, CONNECTION);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "OpenAI embedding requires a non-empty 'connection' on the embedding model setup.");
        }
        Object model = descriptor.getArgument(OpenAIEmbeddingModelConnection.MODEL);
        OpenAIEmbeddingModelConnection.parseModel(model);
        return descriptor;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(OpenAIEmbeddingModelConnection.MODEL, model);
        parameters.put(OpenAIEmbeddingModelConnection.ENCODING_FORMAT, encodingFormat);
        if (dimensions != null) {
            parameters.put(OpenAIEmbeddingModelConnection.DIMENSIONS, dimensions);
        }
        if (user != null) {
            parameters.put(OpenAIEmbeddingModelConnection.USER, user);
        }
        if (!additionalKwargs.isEmpty()) {
            parameters.put(
                    OpenAIEmbeddingModelConnection.ADDITIONAL_KWARGS,
                    new HashMap<>(additionalKwargs));
        }
        return parameters;
    }
}
