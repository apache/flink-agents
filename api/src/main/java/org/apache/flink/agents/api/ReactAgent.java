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

import org.apache.flink.agents.api.context.RunnerContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for defining ReactAgent. ReactAgent is a special type of Agent that uses LLM to
 * control the
 */
public abstract class ReactAgent extends Agent {
    /** ToolCallsEvent is an event used to call tools with arguments. */
    public class ToolCallEvents extends Event {
        private final Map<String, Object[]> toolCalls;

        public ToolCallEvents(Map<String, Object[]> toolCalls) {
            this.toolCalls = toolCalls;
        }

        public Map<String, Object[]> getToolCalls() {
            return toolCalls;
        }
    }

    /** ReasoningEvent is an event that contains a prompt for reasoning. */
    public class ReasoningEvent extends Event {
        private final String prompt;

        public ReasoningEvent(String prompt) {
            this.prompt = prompt;
        }

        public String getReasoning() {
            return prompt;
        }
    }

    /** EvaluateEvent is an event that contains the result of tool calls evaluation. */
    public class EvaluateEvent extends Event {
        private final Map<String, Object> results;

        public EvaluateEvent(Map<String, Object> results) {
            this.results = results;
        }

        public Map<String, Object> getResults() {
            return results;
        }
    }

    /**
     * Handles input events by sending a ReasoningEvent with the input prompt.
     *
     * @param event the input event is the input that triggers the agent run
     * @param context the context in which the agent is running, used to send events
     */
    @Action(listenEvents = {InputEvent.class})
    public void handleInputEvent(InputEvent event, RunnerContext context) {
        String prompt = event.getInput().toString();
        context.sendEvent(new ReasoningEvent(prompt));
    }

    /**
     * Handles ReasoningEvent by generating tool calls based on the reasoning prompt. If tool calls
     * are generated, it sends a ToolCallEvents event with the generated calls. If no tool calls are
     * generated, it sends an OutputEvent with a "done" message.
     *
     * @param event the ReasoningEvent containing the prompt for reasoning
     * @param context
     */
    @Action(listenEvents = {ReasoningEvent.class})
    public void handleReasoningEvent(ReasoningEvent event, RunnerContext context) throws Exception {
        String prompt = event.getReasoning();
        Map<String, Object[]> toolCalls = generateToolCalls(prompt);
        if (!toolCalls.isEmpty()) {
            context.sendEvent(new ToolCallEvents(toolCalls));
        } else {
            // If no tool calls are generated, we can send an output event with empty results
            context.sendEvent(new OutputEvent("done"));
        }
    }

    @Action(listenEvents = {ToolCallEvents.class})
    public void handleToolCallsEvent(ToolCallEvents event, RunnerContext context) {
        // Handle tool call event logic here
        Map<String, Object> toolCallResults = new HashMap<>();
        for (Map.Entry<String, Object[]> entry : event.getToolCalls().entrySet()) {
            String toolName = entry.getKey();
            Object[] args = entry.getValue();
            // Process each tool call with its arguments
            // This can be overridden to implement specific tool call handling logic
            toolCallResults.put(toolName, callTool(toolName, args));
        }
        context.sendEvent(new EvaluateEvent(toolCallResults));
    }

    @Action(listenEvents = {EvaluateEvent.class})
    public void handleEvaluateEvent(EvaluateEvent event, RunnerContext context) throws Exception {
        String prompt = evaluateResult(event.getResults());
        context.sendEvent(new ReasoningEvent(prompt));
    }

    /**
     * Generates tool calls based on the provided prompt. This method should be overridden to
     * implement specific logic for generating tool calls. * For example, it could use an LLM to
     * determine which tools to call and with what arguments.
     *
     * @param prompt the reasoning prompt that will be used to generate tool calls
     * @return a map of tool names to their arguments, where each key is the tool name and the value
     *     is an array of arguments
     * @throws Exception if there's an error generating tool calls
     */
    protected Map<String, Object[]> generateToolCalls(String prompt) throws Exception {
        // This method should be overridden to generate tool calls based on the prompt
        // For example, it could use an LLM to determine which tools to call and with what arguments
        return Map.of();
    }

    protected Map.Entry<String, Object> callTool(String toolName, Object... args) {
        // This method can be overridden to implement specific tool call logic
        // For example, it could invoke a specific tool with the provided arguments
        Object result = "";
        return Map.entry(toolName, result);
    }

    protected String evaluateResult(Map<String, Object> results) {
        // This method can be overridden to implement specific evaluation logic
        // For example, it could use an LLM to determine the evaluation of the results
        return "EXIT";
    }
}
