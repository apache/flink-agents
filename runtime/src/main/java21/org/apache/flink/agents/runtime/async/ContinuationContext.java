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

import jdk.internal.vm.Continuation;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/** Continuation context with JDK 21 continuation state. */
public class ContinuationContext {

    private Continuation currentContinuation;
    private volatile Future<?> pendingFuture;
    private final AtomicReference<Object> asyncResult = new AtomicReference<>();
    private final AtomicReference<Throwable> asyncException = new AtomicReference<>();

    public Continuation getCurrentContinuation() {
        return currentContinuation;
    }

    public void setCurrentContinuation(Continuation currentContinuation) {
        this.currentContinuation = currentContinuation;
    }

    public Future<?> getPendingFuture() {
        return pendingFuture;
    }

    public void setPendingFuture(Future<?> pendingFuture) {
        this.pendingFuture = pendingFuture;
    }

    public AtomicReference<Object> getAsyncResultRef() {
        return asyncResult;
    }

    public AtomicReference<Throwable> getAsyncExceptionRef() {
        return asyncException;
    }

    public void clearAsyncState() {
        pendingFuture = null;
        asyncResult.set(null);
        asyncException.set(null);
    }
}
