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

import org.apache.flink.agents.api.event.AgentRunBeginEvent;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.event.LongTermGetEvent;
import org.apache.flink.agents.api.event.LongTermSearchEvent;
import org.apache.flink.agents.api.event.LongTermUpdateEvent;
import org.apache.flink.agents.api.event.MemoryEvent;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.event.SensoryReadEvent;
import org.apache.flink.agents.api.event.SensoryWriteEvent;
import org.apache.flink.agents.api.event.ShortTermReadEvent;
import org.apache.flink.agents.api.event.ShortTermWriteEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.annotation.VisibleForTesting;

import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Centralized registry that restores built-in event types at the JSON / cross-language boundary.
 *
 * <p>Events cross the Python/Java boundary as JSON and are deserialized into the base {@link
 * Event}, whose {@code attributes} is a {@code Map<String, Object>}. Nested typed values (for
 * example {@link org.apache.flink.agents.api.chat.messages.ChatMessage}) therefore arrive as
 * generic maps, and infrastructure that runs before an Action — the EventRouter, the Trace Log, and
 * event listeners — observes an untyped event even for a known built-in type.
 *
 * <p>{@link #restore(Event)} maps each built-in event type to its existing {@code fromEvent}
 * reconstruction path, so a single call at the lowest-level deserialization entry point ({@link
 * Event#fromJson(String)}) covers every downstream boundary. The contract is:
 *
 * <ul>
 *   <li>A registered built-in type is reconstructed into its concrete subclass, preserving Event
 *       ID, source timestamp, upstream Event ID, upstream Action name, and attachments.
 *   <li>An unknown or user-defined type is returned unchanged as a generic {@link Event}.
 *   <li>Restoration is idempotent: reconstructing an already-typed event is safe, so Actions that
 *       still call {@code fromEvent} themselves keep working.
 *   <li>A malformed built-in event fails clearly with an {@link IllegalArgumentException} rather
 *       than surfacing an opaque reconstruction error.
 * </ul>
 *
 * <p>The memory observation types all dispatch through {@link MemoryEvent#fromEvent(Event)}, which
 * selects the concrete subclass from the event type.
 */
public final class BuiltInEvents {

    /** Built-in event type to its reconstructor. Memory subtypes share {@link MemoryEvent}. */
    private static final Map<String, UnaryOperator<Event>> REGISTRY =
            Map.ofEntries(
                    Map.entry(InputEvent.EVENT_TYPE, InputEvent::fromEvent),
                    Map.entry(OutputEvent.EVENT_TYPE, OutputEvent::fromEvent),
                    Map.entry(ChatRequestEvent.EVENT_TYPE, ChatRequestEvent::fromEvent),
                    Map.entry(ChatResponseEvent.EVENT_TYPE, ChatResponseEvent::fromEvent),
                    Map.entry(ToolRequestEvent.EVENT_TYPE, ToolRequestEvent::fromEvent),
                    Map.entry(ToolResponseEvent.EVENT_TYPE, ToolResponseEvent::fromEvent),
                    Map.entry(
                            ContextRetrievalRequestEvent.EVENT_TYPE,
                            ContextRetrievalRequestEvent::fromEvent),
                    Map.entry(
                            ContextRetrievalResponseEvent.EVENT_TYPE,
                            ContextRetrievalResponseEvent::fromEvent),
                    Map.entry(ModelRoutingEvent.EVENT_TYPE, ModelRoutingEvent::fromEvent),
                    Map.entry(AgentRunBeginEvent.EVENT_TYPE, AgentRunBeginEvent::fromEvent),
                    Map.entry(ShortTermWriteEvent.EVENT_TYPE, MemoryEvent::fromEvent),
                    Map.entry(ShortTermReadEvent.EVENT_TYPE, MemoryEvent::fromEvent),
                    Map.entry(SensoryWriteEvent.EVENT_TYPE, MemoryEvent::fromEvent),
                    Map.entry(SensoryReadEvent.EVENT_TYPE, MemoryEvent::fromEvent),
                    Map.entry(LongTermUpdateEvent.EVENT_TYPE, MemoryEvent::fromEvent),
                    Map.entry(LongTermGetEvent.EVENT_TYPE, MemoryEvent::fromEvent),
                    Map.entry(LongTermSearchEvent.EVENT_TYPE, MemoryEvent::fromEvent));

    /**
     * Restores a registered built-in event to its concrete subclass; unknown or user-defined types
     * are returned unchanged.
     *
     * @param base the deserialized event, which may be {@code null}
     * @return the reconstructed built-in event, or {@code base} when its type is not registered
     * @throws IllegalArgumentException if a registered built-in event cannot be reconstructed
     */
    public static Event restore(Event base) {
        if (base == null) {
            return null;
        }
        UnaryOperator<Event> reconstructor = REGISTRY.get(base.getType());
        if (reconstructor == null) {
            return base;
        }
        try {
            return reconstructor.apply(base);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Malformed built-in event of type '" + base.getType() + "'", e);
        }
    }

    /**
     * Returns the registered built-in event type strings.
     *
     * <p>Visible for testing so the registry can be checked against {@link
     * EventType#allConstants()} to guard against drift when a new built-in event type is added.
     */
    @VisibleForTesting
    static Set<String> registeredTypes() {
        return REGISTRY.keySet();
    }

    private BuiltInEvents() {}
}
