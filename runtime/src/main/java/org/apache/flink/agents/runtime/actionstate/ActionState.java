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

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /**
     * Records of completed durable_execute/durable_execute_async calls for fine-grained recovery.
     */
    private final List<CallResult> callResults;

    /** Indicates whether the action has completed execution. */
    private boolean completed;

    /** Default constructor for Jackson deserialization. */
    private ActionState() {
        this.taskEvent = null;
        this.sensoryMemoryUpdates = new ArrayList<>();
        this.shortTermMemoryUpdates = new ArrayList<>();
        this.outputEvents = new ArrayList<>();
        this.callResults = new ArrayList<>();
        this.completed = false;
    }

    /** Constructs a new TaskActionState instance. */
    public ActionState(final Event taskEvent) {
        this.taskEvent = taskEvent;
        this.sensoryMemoryUpdates = new ArrayList<>();
        this.shortTermMemoryUpdates = new ArrayList<>();
        this.outputEvents = new ArrayList<>();
        this.callResults = new ArrayList<>();
        this.completed = false;
    }

    /** Constructor for deserialization purposes. */
    public ActionState(
            Event taskEvent,
            List<MemoryUpdate> sensoryMemoryUpdates,
            List<MemoryUpdate> shortTermMemoryUpdates,
            List<Event> outputEvents,
            List<CallResult> callResults,
            boolean completed) {
        this.taskEvent = taskEvent;
        this.sensoryMemoryUpdates =
                sensoryMemoryUpdates != null ? sensoryMemoryUpdates : new ArrayList<>();
        this.shortTermMemoryUpdates =
                shortTermMemoryUpdates != null ? shortTermMemoryUpdates : new ArrayList<>();
        this.outputEvents = outputEvents != null ? outputEvents : new ArrayList<>();
        this.callResults = callResults != null ? callResults : new ArrayList<>();
        this.completed = completed;
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

    /** Gets the list of call results for fine-grained durable execution. */
    public List<CallResult> getCallResults() {
        return callResults;
    }

    /**
     * Adds a call result for a completed durable_execute/durable_execute_async call.
     *
     * @param callResult the call result to add
     */
    public void addCallResult(CallResult callResult) {
        callResults.add(callResult);
    }

    /**
     * Gets the call result at the specified index.
     *
     * @param index the index of the call result
     * @return the call result at the specified index, or null if index is out of bounds
     */
    public CallResult getCallResult(int index) {
        if (index >= 0 && index < callResults.size()) {
            return callResults.get(index);
        }
        return null;
    }

    /**
     * Gets the number of call results.
     *
     * @return the number of call results
     */
    @JsonIgnore
    public int getCallResultCount() {
        return callResults.size();
    }

    /**
     * Clears all call results. This should be called when the action completes to reduce storage
     * overhead.
     */
    public void clearCallResults() {
        callResults.clear();
    }

    /**
     * Clears call results from the specified index onwards. This is used when a non-deterministic
     * call order is detected during recovery.
     *
     * @param fromIndex the index from which to clear results (inclusive)
     */
    public void clearCallResultsFrom(int fromIndex) {
        if (fromIndex >= 0 && fromIndex < callResults.size()) {
            callResults.subList(fromIndex, callResults.size()).clear();
        }
    }

    /** Returns whether the action has completed execution. */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Marks the action as completed and clears call results. This should be called when the action
     * finishes execution to indicate that recovery should skip the entire action.
     */
    public void markCompleted() {
        this.completed = true;
        this.callResults.clear();
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
        result = 31 * result + (callResults.isEmpty() ? 0 : callResults.hashCode());
        result = 31 * result + (completed ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ActionState that = (ActionState) o;
        return completed == that.completed
                && java.util.Objects.equals(taskEvent, that.taskEvent)
                && java.util.Objects.equals(sensoryMemoryUpdates, that.sensoryMemoryUpdates)
                && java.util.Objects.equals(shortTermMemoryUpdates, that.shortTermMemoryUpdates)
                && java.util.Objects.equals(outputEvents, that.outputEvents)
                && java.util.Objects.equals(callResults, that.callResults);
    }

    @Override
    public String toString() {
        return "ActionState{"
                + "taskEvent="
                + taskEvent
                + ", sensoryMemoryUpdates="
                + sensoryMemoryUpdates
                + ", shortTermMemoryUpdates="
                + shortTermMemoryUpdates
                + ", outputEvents="
                + outputEvents
                + ", callResults="
                + callResults
                + ", completed="
                + completed
                + '}';
    }
}
