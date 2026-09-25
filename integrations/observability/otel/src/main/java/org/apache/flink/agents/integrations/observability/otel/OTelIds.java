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
package org.apache.flink.agents.integrations.observability.otel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic derivation of fixed-width OpenTelemetry ids from the opaque framework ids.
 *
 * <p>Per the design in the Agent Trace discussions, the framework keeps {@code inputRunId} and
 * {@code executionId} opaque; the exporter derives valid 128-bit trace ids and 64-bit span ids by
 * hashing them. Determinism makes re-exports idempotent: exporting the same Event Log twice
 * produces byte-identical ids, so an OTLP backend deduplicates instead of double-counting.
 */
final class OTelIds {

    /** Domain separator so a run-root span id can never collide with an execution span id. */
    private static final String RUN_SPAN_DOMAIN = "/input-run-root";

    private OTelIds() {}

    /** Derives the 32-hex-char OTel trace id for an input run. */
    static String traceId(String inputRunId) {
        return hexDigest(inputRunId, 16);
    }

    /** Derives the 16-hex-char OTel span id for an execution. */
    static String spanId(String executionId) {
        return hexDigest(executionId, 8);
    }

    /** Derives the 16-hex-char OTel span id for the synthesized run-root span. */
    static String runRootSpanId(String inputRunId) {
        return hexDigest(inputRunId + RUN_SPAN_DOMAIN, 8);
    }

    private static String hexDigest(String value, int bytes) {
        byte[] digest = sha256(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(bytes * 2);
        boolean allZero = true;
        for (int i = 0; i < bytes; i++) {
            if (digest[i] != 0) {
                allZero = false;
            }
        }
        // An all-zero id is invalid in OTel. Astronomically unlikely from SHA-256, but cheap to
        // guard: flip the last byte so the id stays deterministic and valid.
        if (allZero) {
            digest[bytes - 1] = 1;
        }
        for (int i = 0; i < bytes; i++) {
            hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return hex.toString();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
