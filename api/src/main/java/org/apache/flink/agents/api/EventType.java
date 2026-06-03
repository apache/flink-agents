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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Compile-time constants for built-in event types and a runtime registry for user-defined events.
 *
 * <p>Usage in {@code @Action}:
 *
 * <ul>
 *   <li>Built-in events: {@code @Action(EventType.InputEvent)}
 *   <li>User-defined events: {@code @Action("MyCustomEvent")}
 * </ul>
 *
 * <p>Resolution via {@link #lookupOrSelf}: built-in &rarr; user-registered &rarr; passthrough.
 */
public final class EventType {

    public static final String InputEvent = "_input_event";
    public static final String OutputEvent = "_output_event";
    public static final String ChatRequestEvent = "_chat_request_event";
    public static final String ChatResponseEvent = "_chat_response_event";
    public static final String ToolRequestEvent = "_tool_request_event";
    public static final String ToolResponseEvent = "_tool_response_event";
    public static final String ContextRetrievalRequestEvent = "_context_retrieval_request_event";
    public static final String ContextRetrievalResponseEvent = "_context_retrieval_response_event";

    private static final Map<String, String> BUILTIN;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("InputEvent", InputEvent);
        m.put("OutputEvent", OutputEvent);
        m.put("ChatRequestEvent", ChatRequestEvent);
        m.put("ChatResponseEvent", ChatResponseEvent);
        m.put("ToolRequestEvent", ToolRequestEvent);
        m.put("ToolResponseEvent", ToolResponseEvent);
        m.put("ContextRetrievalRequestEvent", ContextRetrievalRequestEvent);
        m.put("ContextRetrievalResponseEvent", ContextRetrievalResponseEvent);
        BUILTIN = Collections.unmodifiableMap(m);
    }

    private static final ConcurrentMap<String, String> USER_REGISTERED = new ConcurrentHashMap<>();

    private EventType() {}

    /**
     * Registers a user-defined event class so that its simple name resolves to its {@code
     * EVENT_TYPE} value. The class must declare {@code public static final String EVENT_TYPE}.
     * Idempotent for the same {@code (name, EVENT_TYPE)} pair.
     *
     * @param eventClass the event class to register
     */
    public static void register(Class<? extends Event> eventClass) {
        if (eventClass == null) {
            throw new IllegalArgumentException("eventClass must not be null");
        }
        String name = eventClass.getSimpleName();
        if (BUILTIN.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Short name '" + name + "' collides with a built-in EventType");
        }
        String eventType = readEventTypeField(eventClass);
        String previous = USER_REGISTERED.putIfAbsent(name, eventType);
        if (previous != null && !previous.equals(eventType)) {
            throw new IllegalStateException(
                    "Short name '"
                            + name
                            + "' already registered with EVENT_TYPE='"
                            + previous
                            + "', cannot re-register with EVENT_TYPE='"
                            + eventType
                            + "'");
        }
    }

    /**
     * Returns the {@code EVENT_TYPE} for a registered short name, or {@code null} if unknown.
     * Built-in names take precedence over user-registered ones.
     */
    public static String lookup(String name) {
        if (name == null) {
            return null;
        }
        String v = BUILTIN.get(name);
        if (v != null) {
            return v;
        }
        return USER_REGISTERED.get(name);
    }

    /** Like {@link #lookup}, but returns {@code name} unchanged if not registered. */
    public static String lookupOrSelf(String name) {
        String v = lookup(name);
        return v != null ? v : name;
    }

    /** Returns {@code true} if {@code name} is a registered short name. */
    public static boolean isKnown(String name) {
        return lookup(name) != null;
    }

    /** Returns an unmodifiable snapshot of all registrations (built-in + user-registered). */
    public static Map<String, String> all() {
        Map<String, String> snapshot = new HashMap<>(BUILTIN);
        snapshot.putAll(USER_REGISTERED);
        return Collections.unmodifiableMap(snapshot);
    }

    private static String readEventTypeField(Class<? extends Event> eventClass) {
        Field field;
        try {
            field = eventClass.getDeclaredField("EVENT_TYPE");
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException(
                    eventClass.getName() + " must declare 'static final String EVENT_TYPE'", e);
        }
        int mods = field.getModifiers();
        if (!Modifier.isStatic(mods)
                || !Modifier.isFinal(mods)
                || field.getType() != String.class) {
            throw new IllegalArgumentException(
                    eventClass.getName() + ".EVENT_TYPE must be static final String");
        }
        try {
            field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof String) || ((String) value).isEmpty()) {
                throw new IllegalArgumentException(
                        eventClass.getName() + ".EVENT_TYPE must be a non-empty String");
            }
            return (String) value;
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Cannot read " + eventClass.getName() + ".EVENT_TYPE", e);
        }
    }

    /** Test-only: reset user registrations between unit tests. */
    static void clearUserRegisteredForTesting() {
        USER_REGISTERED.clear();
    }
}
