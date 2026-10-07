/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flink.agents.runtime.metrics;

import org.apache.flink.agents.api.trace.ExecutionTraceContext;

import javax.annotation.Nullable;

/** Records metrics for one execution entity type from a terminal lifecycle sample. */
interface ExecutionMetricRecorder {

    enum Outcome {
        SUCCEEDED,
        FAILED
    }

    /** Returns the execution entity type consumed by this recorder. */
    String entityType();

    /** Records a terminal execution whose lifecycle has already been resolved by the caller. */
    void record(
            FlinkAgentsMetricGroupImpl actionMetricGroup,
            ExecutionTraceContext traceContext,
            Outcome outcome,
            @Nullable Long latencyMs);
}
