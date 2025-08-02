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

package org.apache.flink.agents.runtime.env;

import org.apache.flink.agents.api.Action;
import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.AgentBuilder;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Implementation of AgentsExecutionEnvironment for local execution.
 *
 * <p>This environment is primarily used for testing and development, allowing agents to be executed
 * locally without a Flink cluster.
 */
public class LocalExecutionEnvironment extends AgentsExecutionEnvironment {

    @Override
    public AgentBuilder fromList(List<Object> input) {
        return new LocalAgentBuilder(input);
    }

    @Override
    public <T, K> AgentBuilder fromDataStream(DataStream<T> input, KeySelector<T, K> keySelector) {
        throw new UnsupportedOperationException(
                "LocalExecutionEnvironment does not support fromDataStream. Use RemoteExecutionEnvironment instead.");
    }

    @Override
    public <K> AgentBuilder fromTable(
            Table input, StreamTableEnvironment tableEnv, KeySelector<Object, K> keySelector) {
        throw new UnsupportedOperationException(
                "LocalExecutionEnvironment does not support fromTable. Use RemoteExecutionEnvironment instead.");
    }

    @Override
    public void execute() throws Exception {
        // For local execution, the execution happens immediately when toList() is
        // called
        // This method is kept for API compatibility
    }

    /** Implementation of AgentBuilder for local execution environment. */
    private static class LocalAgentBuilder implements AgentBuilder {

        private final List<Object> input;
        private Agent agent;
        private List<Map<String, Object>> output;

        public LocalAgentBuilder(List<Object> input) {
            this.input = input;
        }

        @Override
        public AgentBuilder apply(Agent agent) {
            this.agent = agent;
            return this;
        }

        @Override
        public List<Map<String, Object>> toList() {
            if (agent == null) {
                throw new IllegalStateException("Must apply agent before calling toList");
            }

            if (output == null) {
                output = executeAgent();
            }

            return output;
        }

        @Override
        public DataStream<Object> toDataStream() {
            throw new UnsupportedOperationException(
                    "LocalAgentBuilder does not support toDataStream. Use toList instead.");
        }

        @Override
        public Table toTable(Schema schema) {
            throw new UnsupportedOperationException(
                    "LocalAgentBuilder does not support toTable. Use toList instead.");
        }

        private List<Map<String, Object>> executeAgent() {
            List<Map<String, Object>> results = new ArrayList<>();
            Map<Class<? extends Event>, List<Method>> actionMap = buildActionMap(agent);

            for (Object inputData : input) {
                LocalRunnerContext context = new LocalRunnerContext();

                // Extract key from input data (similar to Python implementation)
                String key = extractKey(inputData);

                // Create input event and start processing
                InputEvent inputEvent = new InputEvent(inputData);
                Queue<Event> eventQueue = new LinkedList<>();
                eventQueue.offer(inputEvent);

                // Process events until queue is empty
                while (!eventQueue.isEmpty()) {
                    Event event = eventQueue.poll();
                    List<Method> actions = actionMap.get(event.getClass());

                    if (actions != null) {
                        for (Method action : actions) {
                            try {
                                context.clearEvents();
                                action.invoke(agent, event, context);

                                // Add any new events to the queue for further processing
                                for (Event newEvent : context.getEvents()) {
                                    if (newEvent instanceof OutputEvent) {
                                        // Wrap output in Map format like Python implementation
                                        Map<String, Object> outputMap = new HashMap<>();
                                        outputMap.put(key, ((OutputEvent) newEvent).getOutput());
                                        results.add(outputMap);
                                    } else {
                                        eventQueue.offer(newEvent);
                                    }
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(
                                        "Failed to execute action: " + action.getName(), e);
                            }
                        }
                    }
                }
            }

            return results;
        }

        private Map<Class<? extends Event>, List<Method>> buildActionMap(Agent agent) {
            Map<Class<? extends Event>, List<Method>> actionMap = new HashMap<>();

            for (Method method : agent.getClass().getDeclaredMethods()) {
                Action actionAnnotation = method.getAnnotation(Action.class);
                if (actionAnnotation != null) {
                    method.setAccessible(true);

                    for (Class<? extends Event> eventType : actionAnnotation.listenEvents()) {
                        actionMap.computeIfAbsent(eventType, k -> new ArrayList<>()).add(method);
                    }
                }
            }

            return actionMap;
        }

        /**
         * Extract key from input data similar to Python implementation. Tries to extract 'key'
         * field, then 'k' field, or generates UUID if neither exists.
         */
        private String extractKey(Object inputData) {
            if (inputData instanceof Map) {
                Map<?, ?> mapData = (Map<?, ?>) inputData;

                // Try 'key' field first
                if (mapData.containsKey("key")) {
                    return String.valueOf(mapData.get("key"));
                }

                // Try 'k' field as fallback
                if (mapData.containsKey("k")) {
                    return String.valueOf(mapData.get("k"));
                }
            }

            // Generate UUID if no key found (like Python implementation)
            return java.util.UUID.randomUUID().toString();
        }
    }

    /** Simple implementation of RunnerContext for local execution. */
    private static class LocalRunnerContext implements RunnerContext {

        private final List<Event> events = new ArrayList<>();

        @Override
        public void sendEvent(Event event) {
            events.add(event);
        }

        @Override
        public org.apache.flink.agents.api.context.MemoryObject getShortTermMemory()
                throws Exception {
            throw new UnsupportedOperationException("Memory not supported in local execution");
        }

        @Override
        public org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup getAgentMetricGroup() {
            throw new UnsupportedOperationException("Metrics not supported in local execution");
        }

        @Override
        public org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup getActionMetricGroup() {
            throw new UnsupportedOperationException("Metrics not supported in local execution");
        }

        public List<Event> getEvents() {
            return new ArrayList<>(events);
        }

        public void clearEvents() {
            events.clear();
        }
    }
}
