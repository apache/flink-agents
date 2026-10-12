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
 * A deferred asynchronous call owned by a runner context.
 *
 * <p>Creating a handle does not start work. Await it directly, or await a batch composed with
 * {@link RunnerContext#gather}. Awaiting a resolved handle reuses its local result or exception.
 * Persistence across recovery is an additional guarantee of {@link DurableFuture}.
 *
 * <p>Handles belong to the action execution that created them. They must only be awaited or
 * gathered by that action, never from a worker callback or another action. Unawaited handles
 * perform no work and may be discarded when the action finishes. This interface deliberately
 * exposes neither polling nor cancellation; cancellation of the owning execution is managed by the
 * runtime.
 *
 * @param <T> the result type
 */
public interface AsyncFuture<T> {
    /**
     * Starts the call if necessary and waits using the runner's execution mechanism.
     *
     * <p>On JDK 21+ this yields the action while workers execute the calls. On older JDKs, a single
     * call runs on the action thread and a gathered batch runs on async workers. With the parallel
     * execution engine enabled, the action releases the shared execution lock while executing or
     * waiting, then re-acquires it and restores its context before returning.
     *
     * <p>Ordinary exceptions are cached locally and rethrown on later awaits; cancellation
     * (including interruption) propagates without caching a terminal outcome.
     */
    T await() throws Exception;
}
