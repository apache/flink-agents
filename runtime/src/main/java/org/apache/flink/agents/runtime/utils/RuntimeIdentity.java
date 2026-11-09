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

import org.apache.flink.agents.api.Event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/** Replay-stable identities of input rounds, action executions, and emitted event occurrences. */
public final class RuntimeIdentity {
    private RuntimeIdentity() {}

    public static String deriveRootEventOccurrenceId(
            String businessKeyIdentity, long sequenceNumber) {
        return hash(
                "root", Objects.requireNonNull(businessKeyIdentity), Long.toString(sequenceNumber));
    }

    public static String deriveActionExecutionId(Event event, String actionName) {
        return hash("action", requireOccurrenceId(event), Objects.requireNonNull(actionName));
    }

    public static String deriveEmittedEventOccurrenceId(String executionId, long ordinal) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Output ordinal must be nonnegative: " + ordinal);
        }
        return hash("output", Objects.requireNonNull(executionId), Long.toString(ordinal));
    }

    public static String requireOccurrenceId(Event event) {
        return Objects.requireNonNull(
                event.getOccurrenceId(),
                "Event occurrence identity must be assigned before execution: " + event.getType());
    }

    /** Length-prefixed components prevent collisions between different tuples. */
    public static String hash(String... components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String component : components) {
                byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Live emission counter: shared across yields, recreated when an action restarts. */
    public static final class OutputIdentityAllocator {
        private final String executionId;
        private long nextOrdinal;

        public OutputIdentityAllocator(String executionId) {
            this.executionId = Objects.requireNonNull(executionId);
        }

        public String nextId() {
            return deriveEmittedEventOccurrenceId(executionId, nextOrdinal++);
        }

        public void assign(Event event) {
            if (event.getOccurrenceId() != null) {
                throw new IllegalArgumentException("Each send requires a new Event instance");
            }
            event.setOccurrenceId(nextId());
        }
    }
}
