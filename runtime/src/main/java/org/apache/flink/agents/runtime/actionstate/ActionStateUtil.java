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

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Utility class for action state related operations. */
public class ActionStateUtil {

    private static final JsonMapper MAPPER =
            JsonMapper.builder()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .build();
    private static final String KEY_SEPARATOR = "_";

    public static String generateKey(
            @Nonnull Object key, long seqNum, @Nonnull Action action, @Nonnull Event event)
            throws IOException {
        Preconditions.checkNotNull(key, "key cannot be null.");
        Preconditions.checkNotNull(action, "action cannot be null.");
        Preconditions.checkNotNull(event, "event cannot be null.");
        return String.join(
                KEY_SEPARATOR,
                key.toString(),
                String.valueOf(seqNum),
                generateUUIDForEvent(event),
                generateUUIDForAction(action));
    }

    public static List<String> parseKey(String key) {
        Preconditions.checkNotNull(key, "key cannot be null.");
        String[] parts = key.split(KEY_SEPARATOR);
        Preconditions.checkArgument(parts.length == 4, "Invalid key format.");
        return List.of(parts);
    }

    private static String generateUUIDForEvent(Event event) throws IOException {
        return String.valueOf(
                UUID.nameUUIDFromBytes(MAPPER.writeValueAsBytes(event.getAttributes())));
    }

    private static String generateUUIDForAction(Action action) throws IOException {
        return String.valueOf(
                UUID.nameUUIDFromBytes(
                        String.valueOf(action.hashCode()).getBytes(StandardCharsets.UTF_8)));
    }
}
