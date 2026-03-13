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
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.ResourceCache;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;

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
    public <T> T durableExecuteAsync(DurableCallable<T> callable) throws Exception {
        String functionId = callable.getId();
        String argsDigest = "";

        java.util.Optional<T> cachedResult =
                tryGetCachedResult(functionId, argsDigest, callable.getResultClass());
        if (cachedResult.isPresent()) {
            return cachedResult.get();
        }

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

        T result = null;
        Exception originalException = null;
        try {
            if (continuationExecutor == null || continuationContext == null) {
                result = wrappedSupplier.get();
            } else {
                result = continuationExecutor.executeAsync(continuationContext, wrappedSupplier);
            }
        } catch (DurableExecutionRuntimeException e) {
            originalException = (Exception) e.getCause();
        }

        recordDurableCompletion(functionId, argsDigest, result, originalException);

        if (originalException != null) {
            throw originalException;
        }
        return result;
    }
}
