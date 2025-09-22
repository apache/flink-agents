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
package org.apache.flink.agents.runtime.actionstate;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.context.MemoryUpdate;

import java.util.ArrayList;
import java.util.List;

/** Class representing the state of an action after processing an event. */
public class ActionState {
    private final Event taskEvent;
    private final List<MemoryUpdate> memoryUpdates;
    private final List<Event> outputEvents;

    /** Constructs a new TaskActionState instance. */
    public ActionState(final Event taskEvent) {
        this.taskEvent = taskEvent;
        this.memoryUpdates = new ArrayList<>();
        this.outputEvents = new ArrayList<>();
    }

    public ActionState() {
        this.taskEvent = null;
        this.memoryUpdates = new ArrayList<>();
        this.outputEvents = new ArrayList<>();
    }

    /** Constructor for deserialization purposes. */
    public ActionState(
            Event taskEvent, List<MemoryUpdate> memoryUpdates, List<Event> outputEvents) {
        this.taskEvent = taskEvent;
        this.memoryUpdates = memoryUpdates != null ? memoryUpdates : new ArrayList<>();
        this.outputEvents = outputEvents != null ? outputEvents : new ArrayList<>();
    }

    /** Getters for the fields */
    public Event getTaskEvent() {
        return taskEvent;
    }

    public List<MemoryUpdate> getMemoryUpdates() {
        return memoryUpdates;
    }

    public List<Event> getOutputEvents() {
        return outputEvents;
    }

    /** Setters for the fields */
    public void addMemoryUpdate(MemoryUpdate memoryUpdate) {
        memoryUpdates.add(memoryUpdate);
    }

    public void addEvent(Event event) {
        outputEvents.add(event);
    }

    @Override
    public int hashCode() {
        int result = taskEvent != null ? taskEvent.hashCode() : 0;
        result = 31 * result + (memoryUpdates != null ? memoryUpdates.hashCode() : 0);
        result = 31 * result + (outputEvents != null ? outputEvents.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "TaskActionState{"
                + "taskEvent="
                + taskEvent
                + ", memoryUpdates="
                + memoryUpdates
                + ", outputEvents="
                + outputEvents
                + '}';
    }
}
