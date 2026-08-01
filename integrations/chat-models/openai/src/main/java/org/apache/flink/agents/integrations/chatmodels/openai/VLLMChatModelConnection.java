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
package org.apache.flink.agents.integrations.chatmodels.openai;

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.util.HashMap;
import java.util.Map;

/**
 * Chat model connection for a <a href="https://docs.vllm.ai">vLLM</a> server.
 *
 * <p>vLLM exposes an OpenAI-compatible API, so this connection reuses {@link
 * OpenAICompletionsConnection} with vLLM-friendly defaults:
 *
 * <ul>
 *   <li><b>api_base_url</b> (optional): defaults to {@code http://localhost:8000/v1}, the default
 *       address of {@code vllm serve}
 *   <li><b>api_key</b> (optional): defaults to a placeholder value, since vLLM servers started
 *       without {@code --api-key} do not require a credential (the underlying OpenAI SDK requires a
 *       non-empty key, but the server ignores it). Set it explicitly when the server is started
 *       with {@code --api-key}.
 * </ul>
 *
 * <p>All other connection arguments ({@code timeout}, {@code max_retries}, {@code default_headers},
 * {@code model}) behave exactly as in {@link OpenAICompletionsConnection}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelConnection
 *   public static ResourceDescriptor vllm() {
 *     return ResourceDescriptor.Builder.newBuilder(VLLMChatModelConnection.class.getName())
 *             .addInitialArgument("api_base_url", "http://my-vllm-host:8000/v1")
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class VLLMChatModelConnection extends OpenAICompletionsConnection {

    /** Default base URL of a local {@code vllm serve} instance. */
    public static final String DEFAULT_VLLM_API_BASE_URL = "http://localhost:8000/v1";

    /**
     * Placeholder credential used when the vLLM server is started without {@code --api-key}. The
     * OpenAI SDK requires a non-empty key, but the server ignores its value.
     */
    public static final String DEFAULT_VLLM_API_KEY = "EMPTY";

    public VLLMChatModelConnection(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(withVLLMDefaults(descriptor), resourceContext);
    }

    private static ResourceDescriptor withVLLMDefaults(ResourceDescriptor descriptor) {
        Map<String, Object> arguments = new HashMap<>(descriptor.getInitialArguments());
        Object apiBaseUrl = arguments.get("api_base_url");
        if (apiBaseUrl == null
                || (apiBaseUrl instanceof String && ((String) apiBaseUrl).isBlank())) {
            arguments.put("api_base_url", DEFAULT_VLLM_API_BASE_URL);
        }
        Object apiKey = arguments.get("api_key");
        if (apiKey == null || (apiKey instanceof String && ((String) apiKey).isBlank())) {
            arguments.put("api_key", DEFAULT_VLLM_API_KEY);
        }
        return new ResourceDescriptor(descriptor.getClazz(), arguments);
    }
}
