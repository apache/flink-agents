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

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryRef;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.examples.DataStreamIntegrationExample.ItemData;

/**
 * A simple example agent used for explaining integrating agents with DataStream.
 *
 * <p>This agent processes input events by adding a prefix and a suffix to the input data, counting
 * the number of visits, and emitting an output event.
 */
public class DataStreamAgent extends Agent {

    /** Custom event type for internal agent communication. */
    public static class ProcessedEvent extends Event {
        private final MemoryRef itemRef;

        public ProcessedEvent(MemoryRef itemRef) {
            this.itemRef = itemRef;
        }

        public MemoryRef getItemRef() {
            return itemRef;
        }
    }

    /**
     * Action that processes incoming input events.
     *
     * @param event The input event to process
     * @param ctx The runner context for sending events
     */
    @Action(listenEvents = {InputEvent.class})
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        InputEvent inputEvent = (InputEvent) event;
        ItemData item = (ItemData) inputEvent.getInput();

        // Get short-term memory and update the visit counter for the current key.
        MemoryObject stm = ctx.getShortTermMemory();
        int currentCount = 0;
        if (stm.isExist("visit_count")) {
            currentCount = (int) stm.get("visit_count").getValue();
        }
        int newCount = currentCount + 1;
        stm.set("visit_count", newCount);

        // Send a custom event for further processing
        MemoryRef itemRef = stm.set("input_data", item);
        ctx.sendEvent(new ProcessedEvent(itemRef));
    }

    /**
     * Action that handles processed events and generates output.
     *
     * @param event The processed event
     * @param ctx The runner context for sending events
     */
    @Action(listenEvents = {ProcessedEvent.class})
    public static void generateOutput(Event event, RunnerContext ctx) throws Exception {
        ProcessedEvent processedEvent = (ProcessedEvent) event;
        MemoryRef itemRef = processedEvent.getItemRef();

        // Process the input data using short-term memory
        MemoryObject stm = ctx.getShortTermMemory();
        ItemData originalData = (ItemData) stm.get(itemRef).getValue();
        originalData.visit_count = (int) stm.get("visit_count").getValue();

        // Generate final output
        String output = "Processed: " + originalData.toString() + " [Agent Complete]";
        ctx.sendEvent(new OutputEvent(output));
    }
}
