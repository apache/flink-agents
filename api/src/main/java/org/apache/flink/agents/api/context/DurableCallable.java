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
package org.apache.flink.agents.api.context;

import javax.annotation.Nullable;

import java.util.concurrent.Callable;

/**
 * A callable interface for durable execution that requires a stable identifier.
 *
 * <p>This interface is used with {@link RunnerContext#durableExecute} and {@link
 * RunnerContext#durableExecuteAsync} to ensure that each durable call has a stable, unique
 * identifier that persists across job restarts.
 *
 * @param <T> the type of the result
 */
public interface DurableCallable<T> {

    /**
     * Returns a stable identifier for this durable call.
     *
     * <p>This identifier must be unique within the action and deterministic for the same logical
     * operation. The ID is used to match cached results during recovery.
     */
    String getId();

    /** Returns the class of the result for deserialization during recovery. */
    Class<T> getResultClass();

    /**
     * Executes the durable operation and returns the result.
     *
     * <p>This method will be called only if there is no cached result for this call. The result
     * must be JSON-serializable.
     */
    T call() throws Exception;

    /**
     * Returns an optional callable used to reconcile an in-flight durable call during recovery.
     *
     * <p>Returning {@code null} disables reconcile and preserves the existing completion-only
     * durable execution semantics.
     *
     * <p>If a reconcile callable is provided, it is invoked only when recovery revisits a matching
     * {@code PENDING} slot for this durable call.
     *
     * <p>The reconcile callable should follow these rules:
     *
     * <ul>
     *   <li>Return a result when recovery can determine that the durable call already succeeded.
     *       The runtime will finalize the current slot as succeeded and replay that result.
     *   <li>Throw the terminal business exception when recovery can determine that the durable call
     *       already failed. The runtime will finalize the current slot as failed and replay that
     *       exception.
     *   <li>Throw {@link ReconcileFallbackException} when recovery cannot determine a terminal
     *       state. The runtime will continue by executing the original {@link #call()}.
     * </ul>
     *
     * <p>If reconcile logic encounters query failures, temporary external-system unavailability, or
     * any other condition where it cannot determine a terminal state, it should wrap that
     * underlying exception in {@link ReconcileFallbackException} rather than surfacing it as a
     * terminal failure.
     */
    default @Nullable Callable<T> reconcile() {
        return null;
    }
}
