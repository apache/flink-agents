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

/**
 * Interface for filtering events in event logging and listening.
 *
 * <p>EventFilter allows fine-grained control over which events are processed by event logs and
 * event listeners. Implementations can filter based on event type, attributes, or custom logic.
 */
@FunctionalInterface
public interface EventFilter {
    /**
     * Determines whether an event should be processed.
     *
     * @param event The event to evaluate
     * @param context The context associated with the event
     * @return true if the event should be processed, false otherwise
     */
    boolean accept(Event event, EventContext context);

    /**
     * Creates a filter that accepts events of the specified types.
     *
     * @param eventTypes The event types to accept
     * @return An EventFilter that accepts only the specified event types
     */
    @SafeVarargs
    static EventFilter byEventType(Class<? extends Event>... eventTypes) {
        return (event, context) -> {
            for (Class<? extends Event> eventType : eventTypes) {
                if (eventType.isInstance(event)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** A filter that accepts all events. */
    EventFilter ACCEPT_ALL = (event, context) -> true;

    /** A filter that rejects all events. */
    EventFilter REJECT_ALL = (event, context) -> false;
}
