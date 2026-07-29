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

package org.apache.flink.agents.api.chat.model;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Abstraction of chat model connection.
 *
 * <p>Responsible for managing model service connection configurations, such as Service address, API
 * key, Connection timeout, Model name, Authentication information, etc
 */
public abstract class BaseChatModelConnection extends Resource {

    public BaseChatModelConnection(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.CHAT_MODEL_CONNECTION;
    }

    /**
     * Whether this connection can apply the provider's native structured-output API for the given
     * model.
     *
     * <p>Capability is <b>model-dependent</b>, not connection-wide: a single provider connection
     * commonly serves both models that accept a native schema parameter and models that do not. It
     * is therefore evaluated against the <i>effective</i> model at request-build time — the model
     * actually being called, which per-request parameters may override.
     *
     * <p>The default {@code false} keeps a connection on the prompt-engineering fallback. An
     * unrecognized model must report {@code false} so that it degrades to the fallback rather than
     * failing at the provider.
     *
     * @param effectiveModel the model the request will be issued against, may be null
     * @return true if a schema can be applied natively for {@code effectiveModel}
     */
    protected boolean supportsNativeStructuredOutput(String effectiveModel) {
        return false;
    }

    /**
     * Process a chat request and return a chat response.
     *
     * @param messages the input chat messages
     * @param tools the tools can be called by the model
     * @param modelParams the additional arguments passed to the model
     * @return the chat response containing model outputs
     */
    public abstract ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams);

    /**
     * Process a chat request that carries an output schema, and return a chat response.
     *
     * <p>{@code outputSchema} is framework-level execution metadata, kept off {@code modelParams}
     * so that it can never reach a provider SDK request as a generation parameter. It is either a
     * POJO {@link Class} or an {@link org.apache.flink.agents.api.agents.OutputSchema} (a {@code
     * RowTypeInfo} wrapper); the two cases are distinguished by the connection that consumes it.
     *
     * <p>A schema must not be handed to a connection that has no native translation for it: this
     * default implementation rejects a non-null {@code outputSchema} rather than dropping it, so an
     * unconstrained response can never be mistaken for a schema-conforming one. A null {@code
     * outputSchema} delegates to {@link #chat(List, List, Map)}. A connection that does translate a
     * schema into a native provider parameter overrides this overload, and reports its capability
     * via {@link #supportsNativeStructuredOutput(String)}.
     *
     * @param messages the input chat messages
     * @param tools the tools can be called by the model
     * @param modelParams the additional arguments passed to the model
     * @param outputSchema the schema the response should conform to, or null for an unconstrained
     *     response
     * @return the chat response containing model outputs
     * @throws UnsupportedOperationException if {@code outputSchema} is non-null and this connection
     *     has no native structured-output translation
     */
    public ChatMessage chat(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> modelParams,
            @Nullable Object outputSchema) {
        if (outputSchema != null) {
            throw new UnsupportedOperationException(
                    getClass().getName()
                            + " has no native structured-output translation, so it cannot honor"
                            + " the given output schema. Override chat(List, List, Map, Object) to"
                            + " translate the schema natively, or pass no schema so the caller"
                            + " applies the prompt-engineering fallback.");
        }
        return chat(messages, tools, modelParams);
    }
}
