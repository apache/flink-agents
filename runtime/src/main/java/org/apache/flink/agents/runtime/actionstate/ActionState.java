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
import org.apache.flink.agents.runtime.operator.ActionTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ActionState {
    private final Event taskEvent;
    private final List<MemoryUpdate> memoryUpdates;
    private final List<Event> outputEvents;
    private ActionTask generatedActionTask;

    /** Constructs a new TaskActionState instance. */
    public ActionState(final Event taskEvent) {
        this.taskEvent = taskEvent;
        memoryUpdates = new ArrayList<>();
        outputEvents = new ArrayList<>();
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

    public Optional<ActionTask> getGeneratedActionTask() {
        return Optional.ofNullable(generatedActionTask);
    }

    /** Setters for the fields */
    public void addMemoryUpdate(MemoryUpdate memoryUpdate) {
        memoryUpdates.add(memoryUpdate);
    }

    public void addEvent(Event event) {
        outputEvents.add(event);
    }

    public void setGeneratedActionTask(ActionTask generatedActionTask) {
        this.generatedActionTask = generatedActionTask;
    }

    @Override
    public int hashCode() {
        int result = taskEvent != null ? taskEvent.hashCode() : 0;
        result = 31 * result + (memoryUpdates != null ? memoryUpdates.hashCode() : 0);
        result = 31 * result + (outputEvents != null ? outputEvents.hashCode() : 0);
        result = 31 * result + (generatedActionTask != null ? generatedActionTask.hashCode() : 0);
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
                + ", generatedActionTask="
                + generatedActionTask
                + '}';
    }
}
