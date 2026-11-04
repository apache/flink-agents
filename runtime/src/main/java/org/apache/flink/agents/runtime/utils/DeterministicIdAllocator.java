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

package org.apache.flink.agents.runtime.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Session and call ordinals scoped to one action execution and one target. */
public final class DeterministicIdAllocator {
    private final String namespace;
    private long sessionOrdinal;
    private final Map<String, Long> perSessionCallOrdinals = new HashMap<>();

    public DeterministicIdAllocator(String executionId, String targetName) {
        this.namespace =
                RuntimeIdentity.hash(
                        "call",
                        Objects.requireNonNull(executionId),
                        Objects.requireNonNull(
                                targetName, "An id allocation target name is required"));
    }

    public String nextSessionId() {
        return namespace + "-" + sessionOrdinal++;
    }

    public String nextCallId(String sessionId) {
        long ordinal = perSessionCallOrdinals.merge(sessionId, 1L, Long::sum);
        return RuntimeIdentity.hash(
                "call-id", namespace, Objects.requireNonNull(sessionId), Long.toString(ordinal));
    }
}
