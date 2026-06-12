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

import java.util.List;
import java.util.Map;

/**
 * Abstraction of chat model connection.
 *
 * <p>Responsible for managing model service connection configurations, such as Service address, API
 * key, Connection timeout, Model name, Authentication information, etc
 */
public abstract class BaseChatModelConnection extends Resource {

    /**
     * Reserved {@code modelParams} key carrying the raw output schema (a POJO {@link Class} or an
     * {@link org.apache.flink.agents.api.agents.OutputSchema}) down to the connection so it can
     * apply the provider's native structured-output mechanism. The key is intra-language only and
     * must be removed before the provider SDK call so it never reaches the request body.
     */
    public static final String STRUCTURED_OUTPUT_SCHEMA_KEY = "__structured_output_schema__";

    public BaseChatModelConnection(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.CHAT_MODEL_CONNECTION;
    }

    /**
     * Whether this connection applies the provider's native structured-output API when an output
     * schema is supplied. Connections that translate a schema into a native provider parameter
     * override this to return {@code true}; the default false keeps non-native connections on the
     * prompt-engineering fallback.
     *
     * @return true if this connection supports native structured output
     */
    protected boolean supportsNativeStructuredOutput() {
        return false;
    }

    /**
     * Removes and returns the reserved structured-output schema from {@code modelParams}. Every
     * connection must call this so the reserved key never leaks into the provider SDK request;
     * native connections additionally use the returned value to build the native parameter.
     *
     * @param modelParams the mutable model parameters map (may be null)
     * @return the raw output schema if present, otherwise null
     */
    protected static Object popStructuredOutputSchema(Map<String, Object> modelParams) {
        if (modelParams == null) {
            return null;
        }
        return modelParams.remove(STRUCTURED_OUTPUT_SCHEMA_KEY);
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
}
