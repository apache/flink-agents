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
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.apache.flink.shaded.guava31.com.google.common.base.Preconditions;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Utility class for action state related operations. */
public class ActionStateUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String generateKey(
            @Nonnull Object key, @Nonnull Action action, @Nonnull Event event) throws IOException {
        Preconditions.checkNotNull(key, "key cannot be null.");
        Preconditions.checkNotNull(action, "action cannot be null.");
        Preconditions.checkNotNull(event, "event cannot be null.");
        return key
                + "-"
                + generateUUIDForEvent(event)
                + "-"
                + UUID.nameUUIDFromBytes(
                        String.valueOf(action.hashCode()).getBytes(StandardCharsets.UTF_8));
    }

    public static String generateUUIDForEvent(Event event) throws IOException {
        if (event instanceof InputEvent) {
            InputEvent inputEvent = (InputEvent) event;
            byte[] inputEventBytes = MAPPER.writeValueAsBytes(inputEvent);
            return String.valueOf(UUID.nameUUIDFromBytes(inputEventBytes));
        } else if (event instanceof PythonEvent) {
            PythonEvent pythonEvent = (PythonEvent) event;
            byte[] pythonEventBytes = MAPPER.writeValueAsBytes(pythonEvent);
            return String.valueOf(UUID.nameUUIDFromBytes(pythonEventBytes));
        } else {
            return String.valueOf(
                    UUID.nameUUIDFromBytes(
                            event.getAttributes().toString().getBytes(StandardCharsets.UTF_8)));
        }
    }
}
