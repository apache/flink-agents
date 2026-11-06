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

import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.chat.ChatSubgraphDefinition;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphFuture;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Java-specific implementation of RunnerContext that includes ContinuationActionExecutor for async
 * execution support.
 */
public class JavaRunnerContextImpl extends RunnerContextImpl {
    private final ContinuationActionExecutor continuationExecutor;
    private ContinuationContext continuationContext;

    public JavaRunnerContextImpl(
            FlinkAgentsMetricGroupImpl agentMetricGroup,
            Runnable mailboxThreadChecker,
            AgentPlan agentPlan,
            ResourceCache resourceCache,
            String jobIdentifier,
            ContinuationActionExecutor continuationExecutor) {
        super(agentMetricGroup, mailboxThreadChecker, agentPlan, resourceCache, jobIdentifier);
        this.continuationExecutor = continuationExecutor;
    }

    public ContinuationActionExecutor getContinuationExecutor() {
        return continuationExecutor;
    }

    public void setContinuationContext(ContinuationContext continuationContext) {
        this.continuationContext = continuationContext;
    }

    public ContinuationContext getContinuationContext() {
        return continuationContext;
    }

    @Override
    public DurableFuture<ChatResult> chat(ChatRequestEvent request) {
        return new SubgraphFuture<>(
                this,
                () -> ChatSubgraphDefinition.invocationFor(this),
                request,
                response -> response.getResponse());
    }

    @Override
    public <T> CompletableFuture<T> submitAsync(Callable<T> callable) {
        if (continuationExecutor == null) {
            return super.submitAsync(callable);
        }
        return continuationExecutor.submitAsync(continuationContext, callable);
    }

    @Override
    public void awaitCompletion(CompletableFuture<?> future) throws Exception {
        try {
            awaitCompletableFuture(future);
        } catch (ExecutionException error) {
            AsyncFutureImpl.rethrowFatal(error.getCause());
            throw (Exception) error.getCause();
        }
    }

    @Override
    public <T> T resolveAsync(Callable<T> callable) throws Exception {

        Supplier<T> wrappedSupplier =
                () -> {
                    T innerResult = null;
                    Exception innerException = null;
                    try {
                        innerResult = callable.call();
                    } catch (Exception e) {
                        innerException = e;
                    }

                    if (innerException != null) {
                        throw new DurableExecutionRuntimeException(innerException);
                    }
                    return innerResult;
                };

        try {
            if (continuationExecutor == null || continuationContext == null) {
                return wrappedSupplier.get();
            } else {
                return continuationExecutor.executeAsync(continuationContext, wrappedSupplier);
            }
        } catch (DurableExecutionRuntimeException e) {
            throw (Exception) e.getCause();
        }
    }

    /**
     * Awaits a {@link CompletableFuture} by yielding the continuation, without occupying an
     * async-pool worker. Falls back to blocking {@code get()} when continuations are unavailable.
     */
    public <T> T awaitCompletableFuture(CompletableFuture<T> future) throws Exception {
        if (continuationExecutor != null && continuationContext != null) {
            return continuationExecutor.awaitFuture(continuationContext, future);
        }
        return future.get();
    }

    @Override
    public void close() throws Exception {
        super.close();
    }
}
