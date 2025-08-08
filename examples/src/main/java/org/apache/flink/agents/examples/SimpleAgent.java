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

package org.apache.flink.agents.examples;

import org.apache.flink.agents.api.Action;
import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.RunnerContext;

/**
 * A simple example agent that demonstrates basic agent functionality.
 *
 * <p>This agent processes input events by adding a prefix to the input data and emitting an output
 * event.
 */
public class SimpleAgent extends Agent {

    /** Custom event type for internal agent communication. */
    public static class ProcessedEvent extends Event {
        private final String processedData;

        public ProcessedEvent(String processedData) {
            this.processedData = processedData;
        }

        public String getProcessedData() {
            return processedData;
        }
    }

    /**
     * Action that processes incoming input events.
     *
     * @param event The input event to process
     * @param ctx The runner context for sending events
     */
    @Action(listenEvents = {InputEvent.class})
    public void processInput(Event event, RunnerContext ctx) {
        InputEvent inputEvent = (InputEvent) event;
        Object input = inputEvent.getInput();

        // Process the input data
        String processedData = "Processed: " + input.toString();

        // Send a custom event for further processing
        ctx.sendEvent(new ProcessedEvent(processedData));
    }

    /**
     * Action that handles processed events and generates output.
     *
     * @param event The processed event
     * @param ctx The runner context for sending events
     */
    @Action(listenEvents = {ProcessedEvent.class})
    public void generateOutput(Event event, RunnerContext ctx) {
        ProcessedEvent processedEvent = (ProcessedEvent) event;
        String processedData = processedEvent.getProcessedData();

        // Generate final output
        String output = processedData + " [Agent Complete]";
        ctx.sendEvent(new OutputEvent(output));
    }
}
