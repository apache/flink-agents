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
package org.apache.flink.agents.runtime.async;

import org.apache.flink.agents.api.context.Outcome;

import java.util.List;

/**
 * Result of a durable batch execution plus per-slot start metadata.
 *
 * <p>{@code started[i]} is {@code true} only when slot {@code i}'s worker actually began executing
 * the supplier, not merely when it was handed to the thread pool. A slot that was queued but never
 * ran (for example, when the pool is saturated at the batch deadline) reports {@code false}, so the
 * caller leaves it pending for re-execution on recovery instead of recording a false failure.
 */
public final class BatchExecutionResult<T> {
    private final List<Outcome<T>> outcomes;
    private final boolean[] started;

    public BatchExecutionResult(List<Outcome<T>> outcomes, boolean[] started) {
        this.outcomes = outcomes;
        this.started = started.clone();
    }

    public List<Outcome<T>> getOutcomes() {
        return outcomes;
    }

    public boolean wasStarted(int index) {
        return started[index];
    }
}
