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
 * A deferred durable call that can be awaited.
 *
 * <p>Creating a durable future does not start the call or reserve durable state. The call is
 * started only when the future is awaited directly or as part of {@link RunnerContext#gather}. This
 * type deliberately does not implement {@link java.util.concurrent.Future}: {@link #await()} lets
 * the runtime yield action execution instead of blocking its thread.
 *
 * <p>This is an opaque handle and intentionally exposes no completion polling API.
 *
 * @param <T> the result type
 */
public interface DurableFuture<T> {
    /**
     * Resolves this durable future and returns its result.
     *
     * <p>On JDK 21+, resolving a single durable future yields the current action execution while
     * its callable runs. On JDK &lt; 21, execution falls back to the synchronous path. Awaiting an
     * already resolved future returns or rethrows its locally cached outcome without consuming a
     * second durable slot.
     */
    T await() throws Exception;
}
