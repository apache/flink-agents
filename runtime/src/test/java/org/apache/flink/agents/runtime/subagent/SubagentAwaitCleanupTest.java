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

package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises handled await failures through dispatch and operator action finalization. */
public class SubagentAwaitCleanupTest {
    @ParameterizedTest
    @CsvSource({
        "deferred,prepare,false", "deferred,prepare,true",
        "deferred,call,false", "deferred,call,true",
        "async,status,false", "async,status,true",
        "async,fetch,false", "async,fetch,true",
        "deferred,result,false", "deferred,result,true",
        "async,result,false", "async,result,true"
    })
    @Timeout(30)
    void modelHandledFailureAllowsActionToFinish(String mode, String stage, boolean parallel)
            throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "child",
                ResourceType.AGENT,
                ResourceDescriptor.Builder.newBuilder(
                                (mode.equals("deferred") ? DeferredSetup.class : AsyncSetup.class)
                                        .getName())
                        .addInitialArgument(
                                SubagentSetup.FIELD_INPUT_SCHEMA, "{\"type\":\"object\"}")
                        .addInitialArgument("stage", stage)
                        .build());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubagentAwaitCleanupTest.class.getMethod(
                        "request", Event.class, RunnerContext.class));
        agent.addAction(
                new String[] {ToolResponseEvent.EVENT_TYPE},
                SubagentAwaitCleanupTest.class.getMethod(
                        "receive", Event.class, RunnerContext.class));
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, false);
        config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, parallel);
        config.set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 2);
        AgentPlan compiled = new AgentPlan(agent, config);
        Map<String, Action> actions = new LinkedHashMap<>(compiled.getActions());
        actions.remove("chat_model_action");
        AgentPlan plan = new AgentPlan(actions, compiled.getResourceProviders(), config);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<Long, Object>(plan, true),
                        (KeySelector<Long, Long>) value -> 0L,
                        TypeInformation.of(Long.class))) {
            harness.open();
            harness.processElement(new StreamRecord<>(1L));
            ((ActionExecutionOperator<?, ?>) harness.getOperator()).waitInFlightEventsFinished();
            assertThat(harness.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly("handled");
        }
    }

    @ParameterizedTest
    @CsvSource({
        "deferred,error",
        "deferred,cancel",
        "deferred,interrupt",
        "async,error",
        "async,cancel",
        "async,interrupt"
    })
    @SuppressWarnings({"unchecked", "rawtypes"})
    void directAwaitPreservesFailureAndReleasesHandle(String mode, String failureKind)
            throws Exception {
        Exception failure =
                failureKind.equals("cancel")
                        ? new CancellationException("await failed")
                        : failureKind.equals("interrupt")
                                ? new InterruptedException("await failed")
                                : new IllegalStateException("await failed");
        RunnerContext ctx = mock(RunnerContext.class);
        when(ctx.durableExecuteAsync(any(DurableCallable.class)))
                .thenReturn(
                        (DurableFuture)
                                () -> {
                                    throw failure;
                                });
        PendingSubagentCallRegistry registry = new PendingSubagentCallRegistry("caller");
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(
                                (mode.equals("deferred") ? DeferredSetup.class : AsyncSetup.class)
                                        .getName())
                        .addInitialArgument("stage", "result")
                        .build();
        SubagentFuture future =
                new SubagentFutureImpl(
                        "s",
                        "c",
                        ctx,
                        () ->
                                ctx.durableExecuteAsync(
                                        mode.equals("deferred")
                                                ? new DeferredSetup(descriptor, null)
                                                        .prepare(ctx, "hello", "s", "c")
                                                : new AsyncSetup(descriptor, null)
                                                        .awaitResult(ctx, "s", "c")),
                        registry,
                        () -> false,
                        () -> {});
        assertThatThrownBy(future::await).isSameAs(failure);
        registry.checkEmpty();
    }

    public static void request(Event event, RunnerContext ctx) {
        ctx.sendEvent(
                new ToolRequestEvent(
                        "model",
                        List.of(
                                new ToolCallBlock(
                                        "call-1", "_subagent_child", Map.of("question", "hello")),
                                new ToolCallBlock(
                                        "call-2",
                                        "_subagent_child",
                                        Map.of("question", "hello")))));
    }

    public static void receive(Event event, RunnerContext ctx) {
        ToolResponseEvent response = ToolResponseEvent.fromEvent(event);
        for (String id : List.of("call-1", "call-2")) {
            assertThat(response.getSuccess().get(id)).isFalse();
            assertThat(response.getError().get(id)).contains("remote unavailable");
        }
        ctx.sendEvent(new OutputEvent("handled"));
    }

    public static class DeferredSetup extends BaseDeferredSubagentSetup {
        private final String stage;

        public DeferredSetup(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
            stage = descriptor.getArgument("stage");
        }

        @Override
        protected DurableCallable<SubagentResult> prepare(
                RunnerContext ctx, Object prompt, String sessionId, String callId) {
            if (stage.equals("prepare")) {
                throw new IllegalStateException("remote unavailable");
            }
            return new DurableCallable<SubagentResult>() {
                @Override
                public String getId() {
                    return sessionId + "#" + callId;
                }

                @Override
                public Class<SubagentResult> getResultClass() {
                    return SubagentResult.class;
                }

                @Override
                public SubagentResult call() {
                    if (stage.equals("call")) {
                        throw new IllegalStateException("remote unavailable");
                    }
                    return SubagentResult.error("remote unavailable");
                }
            };
        }
    }

    public static class AsyncSetup extends BaseAsyncSubagentSetup {
        private final String stage;

        public AsyncSetup(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
            stage = descriptor.getArgument("stage");
        }

        @Override
        protected void callSubmitRequest(String sessionId, String callId, Object prompt) {}

        @Override
        protected RunStatus callQueryStatus(String sessionId, String callId) {
            if (stage.equals("status")) {
                throw new IllegalStateException("remote unavailable");
            }
            return RunStatus.completed();
        }

        @Override
        protected SubagentResult callFetchResult(String sessionId, String callId) {
            if (stage.equals("fetch")) {
                throw new IllegalStateException("remote unavailable");
            }
            return SubagentResult.error("remote unavailable");
        }
    }
}
