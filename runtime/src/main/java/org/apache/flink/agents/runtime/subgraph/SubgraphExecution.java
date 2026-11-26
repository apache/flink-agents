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
package org.apache.flink.agents.runtime.subgraph;

import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;

import javax.annotation.Nullable;

/** Execution requirements and blocking waits for internal graph calls. */
public final class SubgraphExecution {

    private SubgraphExecution() {}

    /**
     * Releases the operator while the graph of the call registered under {@code id} runs, so it can
     * dispatch the graph's actions. A call whose result came from the durable slot is already done
     * and does not yield.
     *
     * <p>Waiting is all this does; the future takes the result. A call whose graph failure is its
     * own result therefore tolerates the wait's exception rather than failing the caller with it.
     *
     * <p>Yielding a continuation holds no async-pool worker. The parallel engine instead waits on
     * the caller's worker and hands off only the execution lock, so its pool must hold both the
     * caller and the graph's work.
     */
    public static void await(RunnerContextImpl context, String id) throws Exception {
        SubgraphInvocation<?> invocation = context.getSubgraphCalls().get(id);
        if (invocation.isDone()) {
            return;
        }
        try {
            JavaRunnerContextImpl javaContext =
                    context instanceof JavaRunnerContextImpl
                            ? (JavaRunnerContextImpl) context
                            : null;
            if (javaContext != null && canYieldContinuation(javaContext)) {
                invocation.awaitResult(javaContext);
            } else {
                context.resolveAsync(invocation::awaitResult);
            }
        } catch (Exception e) {
            // A failure of the waiting machinery is not the child's recorded failure, even if
            // the graph normally captures ordinary child exceptions.
            if (!invocation.isRecordedActionFailure(e) || !invocation.capturesActionFailure(e)) {
                throw e;
            }
        }
    }

    /**
     * Fail-fast guard before a chain that waits for a child graph. Waiting must release the
     * operator so it can dispatch the graph's actions; an engine that can neither yield a
     * continuation nor hand off its execution lock would block the operator and deadlock, so the
     * call fails before anything is emitted.
     */
    public static void requireReleasableOperator(@Nullable RunnerContextImpl context) {
        if (!canReleaseOperator(context)) {
            throw new UnsupportedOperationException(
                    "Resolving an internal graph call requires a runtime that releases the"
                            + " operator while the child graph runs: either stackful suspension"
                            + " (JDK 21+ with the Continuation API) or the parallel execution"
                            + " engine's lock handoff. The current runtime would block the"
                            + " operator and deadlock.");
        }
    }

    /**
     * Whether waiting for a child graph can release the operator, by either mechanism: yielding the
     * caller's continuation, or handing off the parallel engine's execution lock. A caller that is
     * not a Java context drives its call through a polling bridge and never waits here, so
     * continuation support for the graph it starts is all that is asked of it.
     */
    private static boolean canReleaseOperator(@Nullable RunnerContext context) {
        if (!(context instanceof JavaRunnerContextImpl)) {
            return ContinuationActionExecutor.isContinuationSupported();
        }
        JavaRunnerContextImpl javaContext = (JavaRunnerContextImpl) context;
        ContinuationActionExecutor executor = javaContext.getContinuationExecutor();
        if (executor == null) {
            return false;
        }
        return canYieldContinuation(javaContext) || executor.canHandOffExecutionLock();
    }

    /** Whether this caller can release the operator by yielding its own continuation. */
    private static boolean canYieldContinuation(JavaRunnerContextImpl context) {
        return ContinuationActionExecutor.isContinuationSupported()
                && context.getContinuationExecutor() != null
                && context.getContinuationContext() != null;
    }
}
