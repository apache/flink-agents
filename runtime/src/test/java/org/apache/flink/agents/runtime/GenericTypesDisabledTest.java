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
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that an agent runs when the job disables generic types ({@code
 * pipeline.generic-types=false}), as production jobs commonly do to avoid silent Kryo fallback.
 */
@Timeout(60)
class GenericTypesDisabledTest {
    private static final String FINISH_EVENT = "finish";

    @Test
    void runsAgentWithGenericTypesDisabled() throws Exception {
        Configuration config = new Configuration();
        config.set(PipelineOptions.GENERIC_TYPES, false);
        config.set(RestartStrategyOptions.RESTART_STRATEGY, "none");
        try (StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment(config)) {
            env.setParallelism(1);
            // Checkpointing makes the operator snapshot its keyed and union list state.
            env.enableCheckpointing(50);
            DataStream<Object> output =
                    CompileUtils.connectToAgent(
                            env.fromData(List.of(1L, 2L, 1L, 2L)).keyBy(value -> value),
                            createPlan());
            List<Object> results = new ArrayList<>();
            try (CloseableIterator<Object> iterator = output.executeAndCollect()) {
                iterator.forEachRemaining(results::add);
            }
            assertThat(results).containsExactlyInAnyOrder(101L, 102L, 201L, 202L);
        }
    }

    private static AgentPlan createPlan() throws Exception {
        Action first =
                new Action(
                        "countVisits",
                        new JavaFunction(
                                Agent.class,
                                "countVisits",
                                new Class<?>[] {Event.class, RunnerContext.class}),
                        List.of(InputEvent.EVENT_TYPE));
        Action finish =
                new Action(
                        "finish",
                        new JavaFunction(
                                Agent.class,
                                "finish",
                                new Class<?>[] {Event.class, RunnerContext.class}),
                        List.of(FINISH_EVENT));
        AgentPlan plan = new AgentPlan(Map.of("countVisits", first, "finish", finish), Map.of());
        plan.getConfig().set(AgentConfigOptions.TRACE_LOG_TARGETS, Collections.emptyList());
        return plan;
    }

    /** Public actions resolved by the runtime through JavaFunction. */
    public static class Agent {
        public static void countVisits(Event event, RunnerContext context) throws Exception {
            long key = (Long) InputEvent.fromEvent(event).getInput();
            MemoryObject memory = context.getShortTermMemory();
            MemoryObject previous = memory.get("visits");
            long visits = previous == null ? 1L : (Long) previous.getValue() + 1;
            memory.set("visits", visits);
            context.getSensoryMemory().set("result", key * 100 + visits);
            context.sendEvent(new Event(FINISH_EVENT));
        }

        public static void finish(Event event, RunnerContext context) throws Exception {
            context.sendEvent(new OutputEvent(context.getSensoryMemory().get("result").getValue()));
        }
    }
}
