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
package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.StructuredOutputStrategy;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * Python-based implementation of ChatModelSetup that bridges Java and Python chat model
 * functionality. This class wraps a Python chat model setup object and provides Java interface
 * compatibility while delegating actual chat operations to the underlying Python implementation.
 */
public class PythonChatModelSetup extends BaseChatModelSetup implements PythonResourceWrapper {
    static final String FROM_JAVA_CHAT_MESSAGE = "python_java_utils.from_java_chat_message";

    static final String TO_JAVA_CHAT_MESSAGE = "python_java_utils.to_java_chat_message";

    private final PyObject chatModelSetup;
    private final PythonResourceAdapter adapter;
    private boolean closed;

    public PythonChatModelSetup(
            PythonResourceAdapter adapter,
            PyObject chatModelSetup,
            ResourceDescriptor descriptor,
            ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.chatModelSetup = chatModelSetup;
        this.adapter = adapter;
    }

    @Override
    public void open() {
        this.adapter.callMethod(chatModelSetup, "open", Collections.emptyMap());
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            Map<String, Object> modelParams) {
        checkState(
                chatModelSetup != null,
                "ChatModelSetup is not initialized. Cannot perform chat operation.");

        Map<String, Object> kwargs = new HashMap<>(modelParams);

        try (PythonObjectScope scope = new PythonObjectScope()) {
            List<Object> pythonMessages = new ArrayList<>();
            for (ChatMessage message : messages) {
                pythonMessages.add(scope.own(adapter.toPythonChatMessage(message)));
            }

            kwargs.put("messages", pythonMessages);
            kwargs.put("prompt_args", promptArgs != null ? promptArgs : Collections.emptyMap());

            Object pythonMessageResponse =
                    scope.own(adapter.callMethod(chatModelSetup, "chat", kwargs));
            return adapter.fromPythonChatMessage(pythonMessageResponse);
        }
    }

    /**
     * False, so a caller keeps describing the schema in the prompt, which works here. No connection
     * is bound on this side, and {@link #chat(List, Map, Map)} carries only messages and prompt
     * arguments across the bridge, so a schema has no way to travel natively.
     *
     * @throws IllegalArgumentException if {@code outputSchema} is non-null and the strategy is
     *     {@link StructuredOutputStrategy#NATIVE}, which this setup cannot honor
     */
    @Override
    public boolean willApplyNativeStructuredOutput(@Nullable Object outputSchema) {
        if (outputSchema != null && structuredOutputStrategy == StructuredOutputStrategy.NATIVE) {
            throw new IllegalArgumentException(
                    "Structured output strategy NATIVE was requested, but a Python chat model"
                            + " setup reached from Java cannot carry an output schema across the"
                            + " bridge. Use AUTO or PROMPT, or configure structured output on the"
                            + " Python side.");
        }
        return false;
    }

    /**
     * Always refuses rather than dropping the schema, so an unconstrained response can never be
     * mistaken for a schema-conforming one.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public ChatMessage chatStructured(
            List<ChatMessage> messages,
            @Nullable Map<String, Object> modelParams,
            Object outputSchema) {
        throw new UnsupportedOperationException(
                "A Python chat model setup cannot be given an output schema from Java: the bridge"
                        + " carries only messages and prompt arguments to the Python setup's chat."
                        + " Apply the schema on the Python side instead.");
    }

    @Override
    public Object getPythonResource() {
        return chatModelSetup;
    }

    @Override
    public PythonResourceAdapter getPythonResourceAdapter() {
        return adapter;
    }

    @Override
    public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
        super.setMetricGroup(metricGroup);
        setPythonResourceMetricGroup(metricGroup);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of();
    }

    @Override
    public void close() throws Exception {
        if (closed || chatModelSetup == null) {
            return;
        }
        closed = true;
        try (chatModelSetup) {
            adapter.callMethod(chatModelSetup, "close", Map.of());
        }
    }
}
