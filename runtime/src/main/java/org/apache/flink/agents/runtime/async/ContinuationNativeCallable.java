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

import org.apache.flink.agents.api.context.DurableCallable;

/**
 * Marks a {@link DurableCallable} that suspends by yielding the caller's continuation, and must
 * therefore run inline on the mailbox thread instead of being dispatched to an async-pool worker.
 *
 * <p>A waiting caller that occupies a pool worker holds a resource its own callee may need to make
 * progress, which self-deadlocks once concurrent or nested waits exhaust the pool (issue #1213). A
 * callable marked here awaits by yielding, so the worker pool stays free for the callee.
 *
 * <p>Only meaningful on JDK 21+, where the continuation the callable yields is the caller's; the
 * callers that produce such callables gate on {@code requireReleasableOperator} beforehand.
 */
public interface ContinuationNativeCallable<T> extends DurableCallable<T> {}
