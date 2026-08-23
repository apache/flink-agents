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
     * <p>No connection translates an {@link org.apache.flink.agents.api.agents.OutputSchema}, and
     * so a {@code RowTypeInfo}, natively, and what follows differs by connection. One that
     * overrides this overload applies its native parameter only for a POJO {@link Class}, so it
     * skips the {@code RowTypeInfo} and leaves the request unchanged, and the caller keeps the
     * prompt-engineering fallback. One that does not override it rejects the {@code RowTypeInfo}
     * through the default body above, which refuses every non-null schema alike. The skip is a
     * deliberate, permanent fallback rather than a translation still to be written; the rejection
     * is the separate case of a connection that could otherwise only drop the schema silently.
     *
     * <p>An overriding connection renders a POJO with its provider SDK's own schema generator, and
     * a render failure is not reported here because those generators produce a schema for every
     * class they are handed. The asymmetry with the Python side is imposed by the vendor libraries
     * rather than chosen: Pydantic genuinely raises on a model it cannot express, and the Python
     * connections wrap that. A schema the SDK does render is sent as rendered even when it declares
     * no properties; whether such a document is usable is the receiving provider's to judge.
     *
     * <p>The ReAct prompt path is the one place either failure is refused, and it renders through a
     * different generator, Jackson, rather than through any provider SDK. Its output is pasted
     * verbatim into the instruction prompt and is therefore the deliverable, so a POJO Jackson
     * cannot render, and one it renders without constraining anything, are both rejected there.
     * Because the two paths use different generators, neither rejection says anything about what a
     * connection does with the same POJO.
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
