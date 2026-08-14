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

import java.util.Objects;

/**
 * One machine-readable converter diagnostic, mirroring the warning model of {@code trace_tree.py}
 * ({@code code} + id + {@code message} + optional file location) so the two Event Log tools share
 * one diagnostic vocabulary.
 *
 * <p>Codes emitted by this converter:
 *
 * <ul>
 *   <li>{@link #MALFORMED_RECORD}: an input record could not be parsed; it was skipped.
 *   <li>{@link #INCOMPLETE_EXECUTION}: a start record has no terminal record. The span is still
 *       exported, closed at the observed start timestamp (zero duration) with status UNSET and the
 *       {@code flink_agents.execution.incomplete} attribute — a missing terminal cannot be
 *       distinguished between a crash, a best-effort Event Log write that dropped the terminal
 *       record, and recovery discarding the transient start/terminal pairing.
 *   <li>{@link #MISSING_START}: a terminal record has no start record (same export policy).
 * </ul>
 */
public final class ConverterDiagnostic {

    public static final String MALFORMED_RECORD = "MALFORMED_RECORD";
    public static final String INCOMPLETE_EXECUTION = "INCOMPLETE_EXECUTION";
    public static final String MISSING_START = "MISSING_START";

    private final String code;
    private final String executionId;
    private final String message;
    private final String filePath;

    ConverterDiagnostic(String code, String executionId, String message, String filePath) {
        this.code = Objects.requireNonNull(code);
        this.executionId = executionId;
        this.message = Objects.requireNonNull(message);
        this.filePath = filePath;
    }

    public String getCode() {
        return code;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getMessage() {
        return message;
    }

    public String getFilePath() {
        return filePath;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(code);
        if (executionId != null) {
            sb.append(" [executionId=").append(executionId).append(']');
        }
        if (filePath != null) {
            sb.append(" [file=").append(filePath).append(']');
        }
        return sb.append(": ").append(message).toString();
    }
}
