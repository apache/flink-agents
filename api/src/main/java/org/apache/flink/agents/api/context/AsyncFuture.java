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
 * A handle for obtaining the result of a runtime-managed call.
 *
 * <p>Use {@link #await()} to obtain the result of an individual call, or {@link
 * RunnerContext#gather} to await multiple supported calls together. Whether execution starts when
 * the handle is created or when it is awaited depends on the operation that creates it.
 *
 * <p>The handle must be used within the action execution that created it, not from a worker
 * callback or another action execution. This interface does not itself guarantee durable result
 * persistence or replay.
 *
 * @param <T> the result type
 */
public interface AsyncFuture<T> {
    /**
     * Waits for the result, starting execution if necessary.
     *
     * <p>The runtime manages execution and waiting. Repeated awaits reuse the completed result or
     * failure.
     *
     * @return the call result
     * @throws Exception if the call fails or waiting is interrupted or cancelled
     */
    T await() throws Exception;
}
