package org.apache.flink.agents.runtime.memory;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Short-Term Memory TTL functionality.
 */
public class ShortTermMemoryTTLIntegrationTest {

    /**
     * Test Agent that tracks visit counts in short-term memory.
     */
    public static class TTLTestAgent extends Agent {

        public static long ttlMs;

        public TTLTestAgent() {
        }

        public TTLTestAgent(long ttlMs) {
            super();
            TTLTestAgent.ttlMs = ttlMs;
        }

        @Action(listenEvents = {InputEvent.class})
        public static void input(org.apache.flink.agents.api.Event event, RunnerContext ctx) throws Exception {
            InputEvent inputEvent = (InputEvent) event;
            String inputData = (String) inputEvent.getInput();

            MemoryObject stm = ctx.getShortTermMemory();

            MemoryObject memoryObj = stm.get(inputData);
            Object existingValue = null;
            int currentCount = 0;

            if (memoryObj != null && !memoryObj.isNestedObject()) {
                existingValue = memoryObj.getValue();
            }

            if (existingValue != null) {
                if (existingValue instanceof Integer) {
                    currentCount = (Integer) existingValue;
                } else if (existingValue instanceof Number) {
                    currentCount = ((Number) existingValue).intValue();
                }
            }

            int newCount = currentCount + 1;
            stm.set(inputData, newCount);

            String output = String.format(
                    "%s|%s",
                    inputData,
                    (existingValue == null ? "NEW" : "EXISTING")
            );

            Thread.sleep(ttlMs);

            ctx.sendEvent(new OutputEvent(output));
        }
    }

    @Test
    void testTTLConfigurationNotApplied() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        AgentsExecutionEnvironment agentEnv = AgentsExecutionEnvironment.getExecutionEnvironment(env);
        AgentConfiguration agentsConfig = (AgentConfiguration) agentEnv.getConfig();

        long ttlMs = 2000L;
        agentsConfig.set(AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_MS, ttlMs);
        agentsConfig.set(
                AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_UPDATE_TYPE,
                StateTtlConfig.UpdateType.OnCreateAndWrite
        );
        agentsConfig.set(
                AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_VISIBILITY,
                StateTtlConfig.StateVisibility.NeverReturnExpired
        );

        // Create test data - send events with delays
        List<String> testData = new ArrayList<>();
        testData.add("event1");
        testData.add("event2");
        testData.add("event1");

        DataStream<String> inputStream = env.fromCollection(testData);

        DataStream<Object> outputStream = agentEnv
                .fromDataStream(inputStream, x -> "test_key") // Fixed key
                .apply(new TTLTestAgent())
                .toDataStream();

        List<String> results = new ArrayList<>();
        outputStream.map(Object::toString).executeAndCollect().forEachRemaining(results::add);

        System.out.println(results);

        assertFalse(results.isEmpty(), "Should have produced output");
        assertEquals(3, results.size(), "Should have exactly 3 outputs");
        String result1 = results.get(0);
        String result2 = results.get(1);
        String result3 = results.get(2);
        assertEquals("event1|NEW", result1, "error output");
        assertEquals("event2|NEW", result2, "error output");
        assertEquals("event1|EXISTING", result3, "error output");


    }

    @Test
    void testTTLConfigurationApplied() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        AgentsExecutionEnvironment agentEnv = AgentsExecutionEnvironment.getExecutionEnvironment(env);
        AgentConfiguration agentsConfig = (AgentConfiguration) agentEnv.getConfig();

        long ttlMs = 1000L;
        agentsConfig.set(AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_MS, ttlMs);
        agentsConfig.set(
                AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_UPDATE_TYPE,
                StateTtlConfig.UpdateType.OnCreateAndWrite
        );
        agentsConfig.set(
                AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_VISIBILITY,
                StateTtlConfig.StateVisibility.NeverReturnExpired
        );

        // Create test data - send events with delays
        List<String> testData = new ArrayList<>();
        testData.add("event1");
        testData.add("event2");
        testData.add("event1");

        DataStream<String> inputStream = env.fromCollection(testData);

        DataStream<Object> outputStream = agentEnv
                .fromDataStream(inputStream, x -> "test_key") // Fixed key
                .apply(new TTLTestAgent(ttlMs + 1000))
                .toDataStream();

        List<String> results = new ArrayList<>();
        outputStream.map(Object::toString).executeAndCollect().forEachRemaining(results::add);

        System.out.println(results);

        assertFalse(results.isEmpty(), "Should have produced output");
        assertEquals(3, results.size(), "Should have exactly 3 outputs");
        String result1 = results.get(0);
        String result2 = results.get(1);
        String result3 = results.get(2);
        assertEquals("event1|NEW", result1, "error output");
        assertEquals("event2|NEW", result2, "error output");
        assertEquals("event1|NEW", result3, "error output");
    }

}
