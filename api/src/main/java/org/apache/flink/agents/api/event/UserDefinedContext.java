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

package org.apache.flink.agents.api.event;

import org.apache.flink.agents.api.Event;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional opaque application data carried on a built-in request and echoed on its response.
 *
 * <p>The value is not interpreted, not injected into prompts or tool arguments, and omitted
 * entirely when absent so existing events keep their serialized shape.
 *
 * <p>Maps and lists, including nested ones, are copied when attached. A later change to the
 * caller's object does not change an event that already holds the value. Other values are stored as
 * given.
 */
final class UserDefinedContext {

    static final String ATTRIBUTE = "context";

    private UserDefinedContext() {}

    static void set(Event event, @Nullable Object context) {
        if (context == null) {
            event.getAttributes().remove(ATTRIBUTE);
        } else {
            event.setAttr(ATTRIBUTE, snapshot(context));
        }
    }

    /**
     * Returns a detached copy of {@code value}. Maps and lists are copied recursively so the event
     * does not share the caller's mutable structure.
     */
    static Object snapshot(Object value) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(snapshot(entry.getKey()), snapshot(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(snapshot(item));
            }
            return copy;
        }
        return value;
    }

    @Nullable
    static Object get(Event event) {
        return event.getAttr(ATTRIBUTE);
    }
}
