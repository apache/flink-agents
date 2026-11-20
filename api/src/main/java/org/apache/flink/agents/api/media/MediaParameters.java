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

package org.apache.flink.agents.api.media;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Recursively snapshots nested {@link Map} and {@link List} model parameters for immutability. */
final class MediaParameters {
    private MediaParameters() {}

    static Map<String, Object> copy(@Nullable Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();
        visiting.put(parameters, Boolean.TRUE);
        Map<String, Object> result = new LinkedHashMap<>(parameters.size());
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "key");
            result.put(key, copyValue(entry.getValue(), visiting));
        }
        return Collections.unmodifiableMap(result);
    }

    @Nullable
    private static Object copyValue(
            @Nullable Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (value instanceof Map) {
            if (visiting.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("modelParameters must not contain a cycle");
            }
            try {
                Map<?, ?> source = (Map<?, ?>) value;
                Map<Object, Object> copied = new LinkedHashMap<>(source.size());
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    copied.put(entry.getKey(), copyValue(entry.getValue(), visiting));
                }
                return Collections.unmodifiableMap(copied);
            } finally {
                visiting.remove(value);
            }
        }
        if (value instanceof List) {
            if (visiting.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("modelParameters must not contain a cycle");
            }
            try {
                List<?> source = (List<?>) value;
                List<Object> copied = new ArrayList<>(source.size());
                for (Object element : source) {
                    copied.add(copyValue(element, visiting));
                }
                return Collections.unmodifiableList(copied);
            } finally {
                visiting.remove(value);
            }
        }
        return value;
    }
}
