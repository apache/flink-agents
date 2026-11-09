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
package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.context.AsyncFuture;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;

import javax.annotation.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/** Runtime lifecycle shared by individually awaited and gathered calls. */
public interface SchedulableFuture<T> extends AsyncFuture<T> {
    /** The creating context, or null for a completed value that has no owner. */
    @Nullable
    RunnerContext getOwnerContext();

    /** Stable identity of the underlying call; mapped views must share this identity. */
    default Object getCallIdentity() throws Exception {
        return this;
    }

    /**
     * Checks execution requirements before any participant reserves durable state or starts work.
     */
    default void validate(RunnerContextImpl context) throws Exception {
        context.checkMailboxThread();
        if (getOwnerContext() != null && getOwnerContext() != context) {
            throw new IllegalArgumentException("Foreign async future");
        }
    }

    /**
     * Prepares without starting work. Returns a cached/replayed outcome, or null if execution is
     * needed. Durable calls reserve their journal positions here, in input order.
     */
    @Nullable
    Outcome<T> prepare(RunnerContextImpl context) throws Exception;

    /** Starts execution or waiting; cached results return an already-completed notification. */
    CompletableFuture<?> start() throws Exception;

    /**
     * Collects and records the result after the notification completes. A non-null timeout instead
     * settles an unfinished call after cancellation. Cached outcomes are always preserved, and
     * repeated calls must not record another durable result.
     */
    Outcome<T> finish(@Nullable TimeoutException timeout) throws Exception;

    /** Fences queued work before the scheduler cancels any participant or records timeouts. */
    default void preventStart() {}

    /** Cancels outstanding work without recording cancellation as a terminal call outcome. */
    void cancel();

    /** Releases registrations and other call resources on every exit path; must be idempotent. */
    void close();
}
