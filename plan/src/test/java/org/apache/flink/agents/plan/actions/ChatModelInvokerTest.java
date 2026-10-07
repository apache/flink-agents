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
package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests for {@link ChatModelInvoker}. */
class ChatModelInvokerTest {

    @AfterEach
    void clearInterruptStatus() {
        // Prevents a leftover interrupt flag (e.g. if the assertion below the interruption test
        // ever fails before consuming it) from failing an unrelated later test's real Thread.sleep
        // backoff with a spurious InterruptedException.
        Thread.interrupted();
    }

    @Test
    void testChatWithRetriesDoesNotRetryOnInterruption() throws Exception {
        RunnerContext ctx = mock(RunnerContext.class);
        BaseChatModelSetup chatModel = mock(BaseChatModelSetup.class);
        ReadableConfiguration config = mock(ReadableConfiguration.class);
        when(ctx.getConfig()).thenReturn(config);
        when(config.get(AgentExecutionOptions.CHAT_ASYNC)).thenReturn(false);
        when(ctx.getResource("test-model", ResourceType.CHAT_MODEL)).thenReturn(chatModel);
        when(ctx.getActionMetricGroup()).thenReturn(mock(FlinkAgentsMetricGroup.class));
        when(ctx.durableExecute(any())).thenThrow(new InterruptedException("cancelled"));

        // Clear any interrupt status left over from a previous test before asserting on it below.
        Thread.interrupted();

        assertThrows(
                InterruptedException.class,
                () ->
                        ChatModelInvoker.chatWithRetries(
                                UUID.randomUUID(),
                                "test-model",
                                "durable-call-id",
                                List.of(),
                                Map.of(),
                                null,
                                ctx,
                                3,
                                0));

        // Only the first attempt should have run: retry backoff must not consume more attempts
        // after a cancellation interrupts the call.
        verify(ctx, times(1)).durableExecute(any());
        assertTrue(Thread.interrupted(), "interrupt status should be restored on the thread");
    }

    @Test
    void durableInfrastructureFailureIsNotRetriedOrConverted() throws Exception {
        RunnerContext ctx = mock(RunnerContext.class);
        BaseChatModelSetup model = mock(BaseChatModelSetup.class);
        ReadableConfiguration config = mock(ReadableConfiguration.class);
        when(ctx.getConfig()).thenReturn(config);
        when(config.get(AgentExecutionOptions.CHAT_ASYNC)).thenReturn(false);
        when(ctx.getResource("test-model", ResourceType.CHAT_MODEL)).thenReturn(model);
        IllegalStateException failure = new IllegalStateException("state persistence failed");
        when(ctx.durableExecute(any())).thenThrow(failure);
        org.junit.jupiter.api.Assertions.assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                ChatModelInvoker.chatWithRetries(
                                        UUID.randomUUID(),
                                        "test-model",
                                        "chat",
                                        List.of(),
                                        Map.of(),
                                        null,
                                        ctx,
                                        3,
                                        0)));
        verify(ctx, times(1)).durableExecute(any());
    }

    @Test
    void providerFailureReplaysFromSerializedOutcome() throws Exception {
        RunnerContext ctx = mock(RunnerContext.class);
        BaseChatModelSetup model = mock(BaseChatModelSetup.class);
        ReadableConfiguration config = mock(ReadableConfiguration.class);
        when(ctx.getConfig()).thenReturn(config);
        when(config.get(AgentExecutionOptions.CHAT_ASYNC)).thenReturn(false);
        when(ctx.getResource("test-model", ResourceType.CHAT_MODEL)).thenReturn(model);
        when(model.chat(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("invalid model"));
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        byte[][] stored = new byte[1][];
        when(ctx.durableExecute(any()))
                .thenAnswer(
                        inv -> {
                            org.apache.flink.agents.api.context.DurableCallable<?> callable =
                                    inv.getArgument(0);
                            if (stored[0] == null) {
                                stored[0] = mapper.writeValueAsBytes(callable.call());
                            }
                            return mapper.readValue(stored[0], callable.getResultClass());
                        });
        for (int replay = 0; replay < 2; replay++) {
            ChatModelInvoker.ChatAttemptFailed failure =
                    assertThrows(
                            ChatModelInvoker.ChatAttemptFailed.class,
                            () ->
                                    ChatModelInvoker.chatWithRetries(
                                            UUID.randomUUID(),
                                            "test-model",
                                            "chat",
                                            List.of(),
                                            Map.of(),
                                            null,
                                            ctx,
                                            0,
                                            0));
            assertTrue(
                    ChatModelInvoker.errorText(failure.error)
                            .contains("IllegalArgumentException: invalid model"));
        }
        verify(model, times(1)).chat(any(), any(), any());
    }

    @Test
    void testChatWithRetriesRestoresInterruptFlagFromRetryBackoffSleep() throws Exception {
        RunnerContext ctx = mock(RunnerContext.class);
        BaseChatModelSetup chatModel = mock(BaseChatModelSetup.class);
        ReadableConfiguration config = mock(ReadableConfiguration.class);
        when(ctx.getConfig()).thenReturn(config);
        when(config.get(AgentExecutionOptions.CHAT_ASYNC)).thenReturn(false);
        when(ctx.getResource("test-model", ResourceType.CHAT_MODEL)).thenReturn(chatModel);
        when(ctx.getActionMetricGroup()).thenReturn(mock(FlinkAgentsMetricGroup.class));
        // The interrupt fires from within the retry backoff's Thread.sleep, not from the call
        // itself: set the flag first so Thread.sleep throws immediately, at no wall-clock cost.
        when(ctx.durableExecute(any()))
                .thenAnswer(
                        invocation -> {
                            Thread.currentThread().interrupt();
                            return new ChatModelInvoker.InvocationOutcome(
                                    null, "transient failure");
                        });

        assertThrows(
                InterruptedException.class,
                () ->
                        ChatModelInvoker.chatWithRetries(
                                UUID.randomUUID(),
                                "test-model",
                                "durable-call-id",
                                List.of(),
                                Map.of(),
                                null,
                                ctx,
                                1,
                                1));

        // Only the first attempt should have run: the sleep before the retry throws before a
        // second call is made.
        verify(ctx, times(1)).durableExecute(any());
        // This is the only assertion that distinguishes the fix from the pre-fix code: the
        // InterruptedException/times(1) shape above passes either way, since Thread.sleep still
        // aborts the retry loop on both. Only the restored flag proves the backoff sleep's catch
        // block restores it instead of leaving it cleared.
        assertTrue(Thread.interrupted(), "interrupt status should be restored on the thread");
    }

    @Test
    void testChatWithRetriesRetriesOnOrdinaryFailure() throws Exception {
        RunnerContext ctx = mock(RunnerContext.class);
        BaseChatModelSetup chatModel = mock(BaseChatModelSetup.class);
        ReadableConfiguration config = mock(ReadableConfiguration.class);
        when(ctx.getConfig()).thenReturn(config);
        when(config.get(AgentExecutionOptions.CHAT_ASYNC)).thenReturn(false);
        when(ctx.getResource("test-model", ResourceType.CHAT_MODEL)).thenReturn(chatModel);
        when(ctx.getActionMetricGroup()).thenReturn(mock(FlinkAgentsMetricGroup.class));
        when(ctx.durableExecute(any()))
                .thenReturn(new ChatModelInvoker.InvocationOutcome(null, "transient failure"));

        assertThrows(
                ChatModelInvoker.ChatAttemptFailed.class,
                () ->
                        ChatModelInvoker.chatWithRetries(
                                UUID.randomUUID(),
                                "test-model",
                                "durable-call-id",
                                List.of(),
                                Map.of(),
                                null,
                                ctx,
                                2,
                                0));

        // An ordinary failure must still consume the full retry budget (initial attempt + 2
        // retries), confirming the interruption fix doesn't disturb normal retry behavior.
        verify(ctx, times(3)).durableExecute(any());
    }
}
