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
}
