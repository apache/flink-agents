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
package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.actions.ChatModelInvoker;
import org.apache.flink.agents.plan.resource.python.PythonChatModelConnection;
import org.apache.flink.agents.plan.resource.python.PythonChatModelSetup;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pemja.core.PythonInterpreter;
import pemja.core.PythonInterpreterConfig;
import pemja.core.object.PyObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real Pemja regression tests for cross-language chat retry classification. */
@Timeout(60)
public class ChatModelCrossLanguageRetryTest {
    private static final String FIXTURE = "flink_agents.runtime.tests.chat_model_retry_bridge";

    private PythonInterpreter interpreter;
    private PythonInterpreterManager manager;
    private PythonResourceAdapterImpl adapter;

    @BeforeEach
    void setUp() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("python/flink_agents"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        PythonInterpreterConfig config =
                PythonInterpreterConfig.newBuilder()
                        .setExcType(PythonInterpreterConfig.ExecType.MULTI_THREAD)
                        .setPythonExec(System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"))
                        .addPythonPaths(root.resolve("python").toString())
                        .build();
        interpreter = new PythonInterpreter(config);
        manager = new PythonInterpreterManager(interpreter, () -> new PythonInterpreter(config));
        adapter = new PythonResourceAdapterImpl(null, manager, null);
        adapter.open();
        interpreter.exec("from flink_agents.runtime.tests import chat_model_retry_bridge");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (adapter != null) {
                adapter.close();
            }
        } finally {
            if (manager != null) {
                manager.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pythonSetupClientErrorIsNotRetriedAcrossPemja(boolean wrapped) throws Exception {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("connection", "unused");
        kwargs.put("model", "py-model");
        kwargs.put("status_code", 400);
        kwargs.put("wrapped", wrapped);
        PyObject pySetup = adapter.initPythonResource(FIXTURE, "BridgeStatusChatSetup", kwargs);
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(PythonChatModelSetup.class.getName())
                        .addInitialArgument("model", "py-model")
                        .build();
        PythonChatModelSetup setup =
                new PythonChatModelSetup(adapter, pySetup, descriptor, mock(ResourceContext.class));
        setup.open();
        try {
            RunnerContext ctx = runnerContextFor(setup);

            ChatModelInvoker.ChatAttemptFailed failure =
                    assertThrows(
                            ChatModelInvoker.ChatAttemptFailed.class,
                            () ->
                                    ChatModelInvoker.chatWithRetries(
                                            UUID.randomUUID(),
                                            "test-model",
                                            "chat",
                                            List.of(new ChatMessage(MessageRole.USER, "hi")),
                                            Map.of(),
                                            null,
                                            ctx,
                                            2,
                                            0));

            assertThat(failure.retryCount).isZero();
            assertThat(
                            ((Number)
                                            interpreter.invoke(
                                                    "chat_model_retry_bridge.get_calls", pySetup))
                                    .intValue())
                    .isEqualTo(1);
        } finally {
            setup.close();
        }
    }

    @Test
    void pythonConnectionClientErrorIsNotRetriedAcrossPemja() throws Exception {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("status_code", 400);
        PyObject pyConnection =
                adapter.initPythonResource(FIXTURE, "BridgeStatusChatConnection", kwargs);
        ResourceDescriptor connectionDescriptor =
                ResourceDescriptor.Builder.newBuilder(PythonChatModelConnection.class.getName())
                        .build();
        PythonChatModelConnection connection =
                new PythonChatModelConnection(
                        adapter, pyConnection, connectionDescriptor, mock(ResourceContext.class));
        ResourceContext resourceContext = mock(ResourceContext.class);
        when(resourceContext.getResource("py-conn", ResourceType.CHAT_MODEL_CONNECTION))
                .thenReturn(connection);
        ResourceDescriptor setupDescriptor =
                ResourceDescriptor.Builder.newBuilder(PassthroughSetup.class.getName())
                        .addInitialArgument("connection", "py-conn")
                        .addInitialArgument("model", "py-model")
                        .build();
        PassthroughSetup setup = new PassthroughSetup(setupDescriptor, resourceContext);
        setup.open();
        try {
            RunnerContext ctx = runnerContextFor(setup);

            ChatModelInvoker.ChatAttemptFailed failure =
                    assertThrows(
                            ChatModelInvoker.ChatAttemptFailed.class,
                            () ->
                                    ChatModelInvoker.chatWithRetries(
                                            UUID.randomUUID(),
                                            "test-model",
                                            "chat",
                                            List.of(new ChatMessage(MessageRole.USER, "hi")),
                                            Map.of(),
                                            null,
                                            ctx,
                                            2,
                                            0));

            assertThat(failure.retryCount).isZero();
            assertThat(
                            ((Number)
                                            interpreter.invoke(
                                                    "chat_model_retry_bridge.get_calls",
                                                    pyConnection))
                                    .intValue())
                    .isEqualTo(1);
        } finally {
            connection.close();
        }
    }

    @Test
    void pythonRetryableStatusAndPlainFailureStillUseRetryBudgetAcrossPemja() throws Exception {
        for (Integer status : new Integer[] {429, 503, null}) {
            Map<String, Object> kwargs = new HashMap<>();
            kwargs.put("connection", "unused");
            kwargs.put("model", "py-model");
            if (status != null) {
                kwargs.put("status_code", status);
            }
            PyObject pySetup = adapter.initPythonResource(FIXTURE, "BridgeStatusChatSetup", kwargs);
            ResourceDescriptor descriptor =
                    ResourceDescriptor.Builder.newBuilder(PythonChatModelSetup.class.getName())
                            .addInitialArgument("model", "py-model")
                            .build();
            PythonChatModelSetup setup =
                    new PythonChatModelSetup(
                            adapter, pySetup, descriptor, mock(ResourceContext.class));
            setup.open();
            try {
                RunnerContext ctx = runnerContextFor(setup);

                ChatModelInvoker.ChatAttemptFailed failure =
                        assertThrows(
                                ChatModelInvoker.ChatAttemptFailed.class,
                                () ->
                                        ChatModelInvoker.chatWithRetries(
                                                UUID.randomUUID(),
                                                "test-model",
                                                "chat",
                                                List.of(new ChatMessage(MessageRole.USER, "hi")),
                                                Map.of(),
                                                null,
                                                ctx,
                                                1,
                                                0));

                assertThat(failure.retryCount).isEqualTo(1);
                assertThat(
                                ((Number)
                                                interpreter.invoke(
                                                        "chat_model_retry_bridge.get_calls",
                                                        pySetup))
                                        .intValue())
                        .isEqualTo(2);
            } finally {
                setup.close();
            }
        }
    }

    @Test
    void javaStatusFailureCrossingIntoPythonKeepsRetryClassification() {
        FailingJavaSetup clientErrorSetup = new FailingJavaSetup(400);
        Number clientRetries =
                (Number)
                        interpreter.invoke(
                                "chat_model_retry_bridge.run_python_chat_against_java_setup",
                                clientErrorSetup,
                                2);
        assertThat(clientRetries.intValue()).isZero();
        assertThat(clientErrorSetup.calls).isEqualTo(1);

        FailingJavaSetup serverErrorSetup = new FailingJavaSetup(503);
        Number serverRetries =
                (Number)
                        interpreter.invoke(
                                "chat_model_retry_bridge.run_python_chat_against_java_setup",
                                serverErrorSetup,
                                1);
        assertThat(serverRetries.intValue()).isEqualTo(1);
        assertThat(serverErrorSetup.calls).isEqualTo(2);
    }

    private static RunnerContext runnerContextFor(BaseChatModelSetup setup) throws Exception {
        RunnerContext ctx = mock(RunnerContext.class);
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.CHAT_ASYNC, false);
        when(ctx.getConfig()).thenReturn(config);
        when(ctx.getResource("test-model", ResourceType.CHAT_MODEL)).thenReturn(setup);
        when(ctx.durableExecute(any()))
                .thenAnswer(invocation -> ((DurableCallable<?>) invocation.getArgument(0)).call());
        return ctx;
    }

    public static final class PassthroughSetup extends BaseChatModelSetup {
        public PassthroughSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    public static final class FailingJavaSetup extends BaseChatModelSetup {
        private final int status;
        private int calls;

        public FailingJavaSetup(int status) {
            super(
                    ResourceDescriptor.Builder.newBuilder(FailingJavaSetup.class.getName()).build(),
                    mock(ResourceContext.class));
            this.status = status;
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of();
        }

        @Override
        public org.apache.flink.agents.api.chat.messages.ChatResult chat(
                List<ChatMessage> messages,
                Map<String, Object> promptArgs,
                Map<String, Object> modelParams) {
            calls++;
            throw new RuntimeException("java wrapper", new JavaHttpStatusException(status));
        }
    }

    public static final class JavaHttpStatusException extends RuntimeException {
        private final int status;

        public JavaHttpStatusException(int status) {
            super("java status " + status);
            this.status = status;
        }

        public int statusCode() {
            return status;
        }
    }
}
