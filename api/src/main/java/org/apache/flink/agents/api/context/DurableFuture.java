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
 * A handle for a deferred call with durable execution and recovery support.
 *
 * <p>Creating a durable future does not start the call or reserve durable state. The call is
 * started only when the future is awaited directly or, for supported handles, as part of {@link
 * RunnerContext#gather}.
 *
 * <p>The runtime records the call's outcome when durable execution is enabled and reuses it during
 * recovery. Execution requirements and recovery behavior depend on the operation that creates the
 * handle.
 *
 * @param <T> the result type
 */
public interface DurableFuture<T> extends AsyncFuture<T> {}
