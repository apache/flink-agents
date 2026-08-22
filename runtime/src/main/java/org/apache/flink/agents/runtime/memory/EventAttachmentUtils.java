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
package org.apache.flink.agents.runtime.memory;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryRef;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Stores event attachments in sensory memory while events cross action boundaries. */
public final class EventAttachmentUtils {

    private static final String ATTACHMENT_ROOT = "__event_attachments__";

    private static final TypeSerializer<Event> EVENT_SERIALIZER_TEMPLATE =
            TypeInformation.of(Event.class).createSerializer(new SerializerConfigImpl());

    private static final ThreadLocal<TypeSerializer<Event>> EVENT_SERIALIZER =
            ThreadLocal.withInitial(EVENT_SERIALIZER_TEMPLATE::duplicate);

    private EventAttachmentUtils() {}

    /** Stores concrete attachment values and replaces them with sensory-memory references. */
    public static void storeEventAttachments(Event event, RunnerContext context) throws Exception {
        if (event.getAttachments().isEmpty()) {
            return;
        }

        if (OutputEvent.EVENT_TYPE.equals(event.getType())) {
            String keys =
                    event.getAttachments().keySet().stream()
                            .sorted()
                            .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Output events cannot carry attachments: event_id="
                            + event.getId()
                            + ", event_type="
                            + event.getType()
                            + ", key="
                            + keys);
        }

        for (Map.Entry<String, Object> entry : event.getAttachments().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof MemoryRef) {
                MemoryRef reference = (MemoryRef) value;
                if (!MemoryObject.MemoryType.SENSORY.equals(reference.getType())) {
                    throw new IllegalArgumentException(
                            "Event attachments must use sensory memory references: event_id="
                                    + event.getId()
                                    + ", event_type="
                                    + event.getType()
                                    + ", key="
                                    + key
                                    + ", memory_type="
                                    + reference.getType());
                }
                continue;
            }

            MemoryRef reference =
                    context.getSensoryMemory().set(buildAttachmentPath(event.getId(), key), value);

            event.getAttachments().put(key, reference);
        }
    }

    /** Returns an action-owned Event copy with sensory-memory references resolved. */
    public static Event loadEventAttachments(Event event, RunnerContext context) throws Exception {
        Event actionEvent = EVENT_SERIALIZER.get().copy(event);
        for (Map.Entry<String, Object> entry : actionEvent.getAttachments().entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof MemoryRef)) {
                continue;
            }
            MemoryRef reference = (MemoryRef) value;

            MemoryObject attachment = context.getSensoryMemory().get(reference);
            if (attachment == null) {
                throw new IllegalStateException(
                        "Event attachment does not exist in sensory memory: "
                                + reference.getPath());
            }
            actionEvent.getAttachments().put(entry.getKey(), attachment.getValue());
        }
        return actionEvent;
    }

    /** Builds the sensory-memory path for one event attachment. */
    public static String buildAttachmentPath(UUID eventId, String key) {
        if (eventId == null) {
            throw new IllegalArgumentException("Event attachment requires a non-null event id.");
        }
        if (key == null) {
            throw new IllegalArgumentException("Event attachment key must not be null.");
        }
        return ATTACHMENT_ROOT + "." + eventId + "." + hashAttachmentKey(key);
    }

    private static String hashAttachmentKey(String key) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                sb.append(String.format("%02x", value));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }
}
