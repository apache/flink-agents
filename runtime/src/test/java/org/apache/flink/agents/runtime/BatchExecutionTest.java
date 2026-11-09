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
package org.apache.flink.agents.runtime;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** End-to-end coverage of batch backend key switches and ordinary backend concurrency. */
@Timeout(60)
public class BatchExecutionTest {
    private static final String FINISH_EVENT = "finish";
    private static volatile CountDownLatch concurrentInputs;

    @ParameterizedTest
    @CsvSource({
        "true, true, false",
        "true, true, true",
        "false, true, false",
        "false, true, true",
        "false, false, false",
        "false, false, true"
    })
    void preservesMemoryAcrossInputsAndActions(
            boolean batchBackend, boolean sortedInputs, boolean parallel) throws Exception {
        assumeFalse(ContinuationActionExecutor.isContinuationSupported());
        AgentPlan plan = createPlan("countVisits", parallel);
        // Deliberately interleave keys. Batch sorting groups them; with sorting disabled the
        // ordinary backend must also retain memory when a previously seen key returns.
        List<Object> results =
                execute(plan, batchBackend, sortedInputs, List.of(1L, 2L, 1L, 2L, 1L, 2L));
        assertThat(results).containsExactlyInAnyOrder(101L, 102L, 103L, 201L, 202L, 203L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permitsConcurrentKeysWithoutBatchBackend(boolean sortedInputs) throws Exception {
        assumeFalse(ContinuationActionExecutor.isContinuationSupported());
        concurrentInputs = new CountDownLatch(2);
        try {
            // Both calls must start in the same operator subtask before either may finish.
            // An unconditional input drain would time out instead of passing this assertion.
            assertThat(
                            execute(
                                    createPlan("awaitOtherKey", true),
                                    false,
                                    sortedInputs,
                                    List.of(1L, 2L)))
                    .containsExactlyInAnyOrder(1L, 2L);
        } finally {
            concurrentInputs = null;
        }
    }

    private static AgentPlan createPlan(String firstAction, boolean parallel) throws Exception {
        Action first =
                new Action(
                        firstAction,
                        new JavaFunction(
                                BatchAgent.class,
                                firstAction,
                                new Class<?>[] {Event.class, RunnerContext.class}),
                        List.of(InputEvent.EVENT_TYPE));
        Action finish =
                new Action(
                        "finish",
                        new JavaFunction(
                                BatchAgent.class,
                                "finish",
                                new Class<?>[] {Event.class, RunnerContext.class}),
                        List.of(FINISH_EVENT));
        AgentPlan plan = new AgentPlan(Map.of(firstAction, first, "finish", finish), Map.of());
        plan.getConfig().set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, parallel);
        plan.getConfig().set(AgentConfigOptions.TRACE_LOG_TARGETS, Collections.emptyList());
        return plan;
    }

    private static List<Object> execute(
            AgentPlan plan, boolean batchBackend, boolean sortedInputs, List<Long> input)
            throws Exception {
        Configuration config = new Configuration();
        config.set(ExecutionOptions.RUNTIME_MODE, RuntimeExecutionMode.BATCH);
        config.set(ExecutionOptions.USE_BATCH_STATE_BACKEND, batchBackend);
        config.set(ExecutionOptions.SORT_INPUTS, sortedInputs);
        config.set(RestartStrategyOptions.RESTART_STRATEGY, "none");
        try (StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment(config)) {
            env.setParallelism(1);
            DataStream<Object> output =
                    CompileUtils.connectToAgent(env.fromData(input).keyBy(value -> value), plan);
            List<Object> results = new ArrayList<>();
            try (CloseableIterator<Object> iterator = output.executeAndCollect()) {
                iterator.forEachRemaining(results::add);
            }
            return results;
        }
    }

    /** Public actions resolved by the runtime through JavaFunction. */
    public static class BatchAgent {
        public static void countVisits(Event event, RunnerContext context) throws Exception {
            long key = (Long) InputEvent.fromEvent(event).getInput();
            MemoryObject memory = context.getShortTermMemory();
            MemoryObject previous = memory.get("visits");
            long visits = previous == null ? 1L : (Long) previous.getValue() + 1;
            memory.set("visits", visits);
            long result = context.executeAsync(() -> key * 100 + visits).await();
            context.getSensoryMemory().set("result", result);
            context.sendEvent(new Event(FINISH_EVENT));
        }

        public static void finish(Event event, RunnerContext context) throws Exception {
            context.sendEvent(new OutputEvent(context.getSensoryMemory().get("result").getValue()));
        }

        public static void awaitOtherKey(Event event, RunnerContext context) throws Exception {
            long key = (Long) InputEvent.fromEvent(event).getInput();
            long result =
                    context.executeAsync(
                                    () -> {
                                        concurrentInputs.countDown();
                                        if (!concurrentInputs.await(20, TimeUnit.SECONDS)) {
                                            throw new IllegalStateException(
                                                    "Different keys did not execute concurrently");
                                        }
                                        return key;
                                    })
                            .await();
            context.sendEvent(new OutputEvent(result));
        }
    }
}
