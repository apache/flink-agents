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

package org.apache.flink.agents.api;

import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test for {@link ReactAgent}. */
class ReactAgentTest {

    @Test
    void testGenerateToolCallsDefaultImplementation() throws Exception {
        TestReactAgent agent = new TestReactAgent(new HashMap<>());
        Map<String, Object[]> result = agent.generateToolCalls("test prompt");
        assertTrue(result.isEmpty(), "Default generateToolCalls should return empty map");
    }

    @Test
    void testCallToolDefaultImplementation() {
        TestReactAgent agent = new TestReactAgent(new HashMap<>());
        Map.Entry<String, Object> result = agent.callTool("testTool", "arg1", "arg2");
        assertEquals("testTool", result.getKey());
        assertEquals("result", result.getValue());
    }

    @Test
    void testEvaluateResultDefaultImplementation() {
        TestReactAgent agent = new TestReactAgent(new HashMap<>());
        Map<String, Object> results = Collections.singletonMap("tool1", "output1");
        String evaluation = agent.evaluateResult(results);
        assertEquals("EXIT", evaluation);
    }

    @Test
    void testHandleInputEventWithMockContext() {
        TestReactAgent agent = new TestReactAgent(new HashMap<>());
        MockRunnerContext context = new MockRunnerContext();

        agent.handleInputEvent(new InputEvent("test input"), context);

        assertEquals(1, context.sentEvents.size());
        assertTrue(context.sentEvents.get(0) instanceof ReactAgent.ReasoningEvent);
        ReactAgent.ReasoningEvent reasoningEvent =
                (ReactAgent.ReasoningEvent) context.sentEvents.get(0);
        assertEquals("test input", reasoningEvent.getReasoning());
    }

    @Test
    void testHandleReasoningEventWithToolCalls() throws Exception {
        Map<String, Object[]> toolCalls = new HashMap<>();
        toolCalls.put("tool1", new Object[] {"arg1"});
        TestReactAgent agent = new TestReactAgent(toolCalls);
        MockRunnerContext context = new MockRunnerContext();

        agent.handleReasoningEvent(agent.new ReasoningEvent("test reasoning"), context);

        assertEquals(1, context.sentEvents.size());
        assertTrue(context.sentEvents.get(0) instanceof ReactAgent.ToolCallEvents);
        ReactAgent.ToolCallEvents toolCallEvent =
                (ReactAgent.ToolCallEvents) context.sentEvents.get(0);
        assertEquals(toolCalls, toolCallEvent.getToolCalls());
    }

    @Test
    void testHandleReasoningEventWithoutToolCalls() throws Exception {
        TestReactAgent agent = new TestReactAgent(new HashMap<>());
        MockRunnerContext context = new MockRunnerContext();

        agent.handleReasoningEvent(agent.new ReasoningEvent("test reasoning"), context);

        assertEquals(1, context.sentEvents.size());
        assertTrue(context.sentEvents.get(0) instanceof OutputEvent);
        OutputEvent outputEvent = (OutputEvent) context.sentEvents.get(0);
        assertEquals("done", outputEvent.getOutput());
    }

    @Test
    void testHandleToolCallsEvent() {
        TestReactAgent agent = new TestReactAgent(new HashMap<>());
        MockRunnerContext context = new MockRunnerContext();
        Map<String, Object[]> toolCalls = new HashMap<>();
        toolCalls.put("tool1", new Object[] {"arg1"});

        agent.handleToolCallsEvent(agent.new ToolCallEvents(toolCalls), context);

        assertEquals(1, context.sentEvents.size());
        assertTrue(context.sentEvents.get(0) instanceof ReactAgent.EvaluateEvent);
        ReactAgent.EvaluateEvent evaluateEvent =
                (ReactAgent.EvaluateEvent) context.sentEvents.get(0);
        assertTrue(evaluateEvent.getResults().containsKey("tool1"));
    }

    @Test
    void testHandleEvaluateEvent() throws Exception {
        TestReactAgent agent = new TestReactAgent(new HashMap<>());
        MockRunnerContext context = new MockRunnerContext();

        agent.handleEvaluateEvent(
                agent.new EvaluateEvent(Collections.singletonMap("key", "value")), context);

        assertEquals(1, context.sentEvents.size());
        assertTrue(context.sentEvents.get(0) instanceof ReactAgent.ReasoningEvent);
        ReactAgent.ReasoningEvent reasoningEvent =
                (ReactAgent.ReasoningEvent) context.sentEvents.get(0);
        assertEquals("EXIT", reasoningEvent.getReasoning());
    }

    /** Test implementation of ReactAgent for testing purposes. */
    private static class TestReactAgent extends ReactAgent {
        private final Map<String, Object[]> toolCalls;

        public TestReactAgent(Map<String, Object[]> toolCalls) {
            this.toolCalls = toolCalls;
        }

        @Override
        protected Map<String, Object[]> generateToolCalls(String prompt) {
            return toolCalls;
        }

        @Override
        protected Map.Entry<String, Object> callTool(String toolName, Object... args) {
            return Map.entry(toolName, "result");
        }
    }

    /** Simple mock implementation of RunnerContext for testing. */
    private static class MockRunnerContext implements RunnerContext {
        final List<Event> sentEvents = new ArrayList<>();

        @Override
        public void sendEvent(Event event) {
            sentEvents.add(event);
        }

        @Override
        public MemoryObject getShortTermMemory() throws Exception {
            return null;
        }
    }
}
