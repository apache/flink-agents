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
     * <p>Return {@code null} to disable reconcile for this durable call and fall back to the
     * existing durable execution behavior. During recovery, the runtime replays a previously
     * completed durable result when one is available; otherwise it executes the original {@link
     * #call()}.
     *
     * <p>If a reconcile callable is provided, the runtime invokes it only when recovery revisits
     * this durable call and finds that the original execution result has not yet been persisted.
     *
     * <p>The reconcile callable should follow these rules:
     *
     * <ul>
     *   <li>Return the result to provide the recovered successful outcome for this durable call.
     *       The runtime persists and replays that recovered result.
     *   <li>Throw an exception to provide the recovered failed outcome for this durable call. The
     *       runtime persists and replays that recovered failure.
     * </ul>
     */
    default @Nullable Callable<T> reconciler() {
        return null;
    }
}
