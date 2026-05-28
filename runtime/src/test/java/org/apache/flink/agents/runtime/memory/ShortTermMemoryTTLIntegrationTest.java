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
package org.apache.flink.agents.runtime.memory;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.agents.ShortTermMemoryTtlUpdate;
import org.apache.flink.agents.api.agents.ShortTermMemoryTtlVisibility;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Integration test for Short-Term Memory TTL functionality. */
class ShortTermMemoryTTLIntegrationTest {

    private static final String MEMORY_KEY = "test_key";

    private static final class TestInput {
        public String eventKey;
        public long sleepMs;

        private TestInput() {}

        private TestInput(String eventKey, long sleepMs) {
            this.eventKey = eventKey;
            this.sleepMs = sleepMs;
        }
    }

    public static class TTLTestAgent extends Agent {

        @Action(listenEventTypes = {InputEvent.EVENT_TYPE})
        public static void input(org.apache.flink.agents.api.Event event, RunnerContext ctx)
                throws Exception {
            InputEvent inputEvent = (InputEvent) event;
            TestInput input = (TestInput) inputEvent.getInput();

            MemoryObject shortTermMemory = ctx.getShortTermMemory();
            MemoryObject memoryObject = shortTermMemory.get(input.eventKey);

            Object existingValue = null;
            int currentCount = 0;
            if (memoryObject != null && !memoryObject.isNestedObject()) {
                existingValue = memoryObject.getValue();
                if (existingValue instanceof Integer) {
                    currentCount = (Integer) existingValue;
                } else if (existingValue instanceof Number) {
                    currentCount = ((Number) existingValue).intValue();
                }
            }

            shortTermMemory.set(input.eventKey, currentCount + 1);
            Thread.sleep(input.sleepMs);
            ctx.sendEvent(
                    new OutputEvent(
                            input.eventKey + "|" + (existingValue == null ? "NEW" : "EXISTING")));
        }
    }

    @Test
    void testValueStillVisibleBeforeTTLExpiry() throws Exception {
        List<String> results = runScenario(1000L, 0L, true, true);

        assertEquals(List.of("event1|NEW", "event2|NEW", "event1|EXISTING"), results);
    }

    @Test
    void testTTLConfigurationDisabledWithZeroTtl() throws Exception {
        List<String> results = runScenario(0L, 2000L, true, true);

        assertEquals(List.of("event1|NEW", "event2|NEW", "event1|EXISTING"), results);
    }

    @Test
    void testTTLConfigurationDisabledByDefault() throws Exception {
        List<String> results = runScenario(0L, 2000L, false, true);

        assertEquals(List.of("event1|NEW", "event2|NEW", "event1|EXISTING"), results);
    }

    @Test
    void testValueExpiresAfterTTL() throws Exception {
        List<String> results = runScenario(1000L, 2000L, true, true);

        assertEquals(List.of("event1|NEW", "event2|NEW", "event1|NEW"), results);
    }

    @Test
    void testTTLConfigurationAppliedWithDefaultUpdateTypeAndVisibility() throws Exception {
        List<String> results = runScenario(1000L, 2000L, true, false);

        assertEquals(List.of("event1|NEW", "event2|NEW", "event1|NEW"), results);
    }

    private static List<String> runScenario(
            long ttlMs, long sleepMs, boolean configureTtlMs, boolean configureTtlOptions)
            throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        AgentsExecutionEnvironment agentEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        AgentConfiguration agentsConfig = (AgentConfiguration) agentEnv.getConfig();
        if (configureTtlMs) {
            agentsConfig.set(AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_MS, ttlMs);
        }
        if (configureTtlOptions) {
            agentsConfig.set(
                    AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_UPDATE_TYPE,
                    ShortTermMemoryTtlUpdate.ON_CREATE_AND_WRITE);
            agentsConfig.set(
                    AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_VISIBILITY,
                    ShortTermMemoryTtlVisibility.NEVER_RETURN_EXPIRED);
        }

        List<TestInput> testData = new ArrayList<>();
        testData.add(new TestInput("event1", sleepMs));
        testData.add(new TestInput("event2", sleepMs));
        testData.add(new TestInput("event1", sleepMs));

        DataStream<TestInput> inputStream = env.fromCollection(testData);
        DataStream<Object> outputStream =
                agentEnv.fromDataStream(inputStream, x -> MEMORY_KEY)
                        .apply(new TTLTestAgent())
                        .toDataStream();

        List<String> results = new ArrayList<>();
        outputStream.map(Object::toString).executeAndCollect().forEachRemaining(results::add);
        return results;
    }
}
