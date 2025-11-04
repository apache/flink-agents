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
    private final List<MemoryUpdate> sensoryMemoryUpdates;
    private final List<MemoryUpdate> shortTermMemoryUpdates;
    private final List<Event> outputEvents;

    /** Constructs a new TaskActionState instance. */
    public ActionState(final Event taskEvent) {
        this.taskEvent = taskEvent;
        this.sensoryMemoryUpdates = new ArrayList<>();
        this.shortTermMemoryUpdates = new ArrayList<>();
        this.outputEvents = new ArrayList<>();
    }

    public ActionState() {
        this.taskEvent = null;
        this.sensoryMemoryUpdates = new ArrayList<>();
        this.shortTermMemoryUpdates = new ArrayList<>();
        this.outputEvents = new ArrayList<>();
    }

    /** Constructor for deserialization purposes. */
    public ActionState(
            Event taskEvent,
            List<MemoryUpdate> sensoryMemoryUpdates,
            List<MemoryUpdate> shortTermMemoryUpdates,
            List<Event> outputEvents) {
        this.taskEvent = taskEvent;
        this.sensoryMemoryUpdates =
                sensoryMemoryUpdates != null ? sensoryMemoryUpdates : new ArrayList<>();
        this.shortTermMemoryUpdates =
                shortTermMemoryUpdates != null ? shortTermMemoryUpdates : new ArrayList<>();
        this.outputEvents = outputEvents != null ? outputEvents : new ArrayList<>();
    }

    /** Getters for the fields */
    public Event getTaskEvent() {
        return taskEvent;
    }

    public List<MemoryUpdate> getSensoryMemoryUpdates() {
        return sensoryMemoryUpdates;
    }

    public List<MemoryUpdate> getShortTermMemoryUpdates() {
        return shortTermMemoryUpdates;
    }

    public List<Event> getOutputEvents() {
        return outputEvents;
    }

    /** Setters for the fields */
    public void addSensoryMemoryUpdate(MemoryUpdate memoryUpdate) {
        sensoryMemoryUpdates.add(memoryUpdate);
    }

    /** Setters for the fields */
    public void addShortTermMemoryUpdate(MemoryUpdate memoryUpdate) {
        shortTermMemoryUpdates.add(memoryUpdate);
    }

    public void addEvent(Event event) {
        outputEvents.add(event);
    }

    @Override
    public int hashCode() {
        int result = taskEvent != null ? taskEvent.hashCode() : 0;
        result =
                31 * result
                        + (sensoryMemoryUpdates.isEmpty() ? 0 : sensoryMemoryUpdates.hashCode());
        result =
                31 * result
                        + (shortTermMemoryUpdates.isEmpty()
                                ? 0
                                : shortTermMemoryUpdates.hashCode());
        result = 31 * result + (outputEvents.isEmpty() ? 0 : outputEvents.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "TaskActionState{"
                + "taskEvent="
                + taskEvent
                + ", sensoryMemoryUpdates="
                + sensoryMemoryUpdates
                + ", shortTermMemoryUpdates="
                + shortTermMemoryUpdates
                + ", outputEvents="
                + outputEvents
                + '}';
    }
}
