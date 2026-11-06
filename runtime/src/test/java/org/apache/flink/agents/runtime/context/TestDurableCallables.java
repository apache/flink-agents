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

import org.apache.flink.agents.api.context.DurableCallable;

import java.util.concurrent.Callable;

/** Shared durable callable fixtures for runner context tests. */
class TestDurableCallable<T> implements DurableCallable<T> {
    private final String id;
    private final Class<T> resultClass;
    private final Callable<T> callSupplier;
    private int callCount;

    TestDurableCallable(String id, Class<T> resultClass, Callable<T> callSupplier) {
        this.id = id;
        this.resultClass = resultClass;
        this.callSupplier = callSupplier;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Class<T> getResultClass() {
        return resultClass;
    }

    @Override
    public T call() throws Exception {
        callCount++;
        return callSupplier.call();
    }

    int getCallCount() {
        return callCount;
    }
}

/** Shared reconciliable durable callable fixture for runner context tests. */
class TestReconcilableCallable<T> implements DurableCallable<T> {
    private final String id;
    private final Class<T> resultClass;
    private final Callable<T> callSupplier;
    private final Callable<T> reconcileSupplier;
    private int callCount;
    private int reconcileCount;

    TestReconcilableCallable(
            String id,
            Class<T> resultClass,
            Callable<T> callSupplier,
            Callable<T> reconcileSupplier) {
        this.id = id;
        this.resultClass = resultClass;
        this.callSupplier = callSupplier;
        this.reconcileSupplier = reconcileSupplier;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Class<T> getResultClass() {
        return resultClass;
    }

    @Override
    public T call() throws Exception {
        callCount++;
        return callSupplier.call();
    }

    @Override
    public Callable<T> reconciler() {
        return () -> {
            reconcileCount++;
            return reconcileSupplier.call();
        };
    }

    int getCallCount() {
        return callCount;
    }

    int getReconcileCount() {
        return reconcileCount;
    }
}
