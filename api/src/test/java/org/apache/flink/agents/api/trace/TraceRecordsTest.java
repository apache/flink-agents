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
package org.apache.flink.agents.api.trace;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Tests for TraceRecord construction and error details. */
class TraceRecordsTest {

    private final TraceContext context = TraceContext.forAction(null, "action", "input");

    @Test
    void executionFailedUsesDeepestCause() {
        IllegalArgumentException root = new IllegalArgumentException("root");
        RuntimeException error = new RuntimeException("outer", root);

        TraceRecord record = TraceRecords.failed(context, error, "action_execution_failed");

        assertThat(record.getAttributes().get("errorType")).isEqualTo(root.getClass().getName());
        assertThat(record.getAttributes().get("errorMessage")).isEqualTo("root");
        assertThat(record.getStatus()).isEqualTo(TraceRecord.Statuses.FAILED);
        assertThat(record.getProblemCategory()).isEqualTo("action_execution_failed");
        assertThat(record.getAttributes()).doesNotContainKeys("status", "problemCategory");
    }

    @Test
    void executionFailedTerminatesForCyclicCauseChain() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        TraceRecord record =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(1), () -> TraceRecords.failed(context, first, null));

        assertThat(record.getAttributes().get("errorType")).isEqualTo(first.getClass().getName());
        assertThat(record.getAttributes().get("errorMessage")).isEqualTo("first");
    }
}
