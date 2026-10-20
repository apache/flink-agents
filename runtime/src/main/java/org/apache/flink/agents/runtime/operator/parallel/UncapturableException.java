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
package org.apache.flink.agents.runtime.operator.parallel;

import org.apache.flink.util.ExceptionUtils;

/**
 * The shell every framework-level fault is raised in, so that it fails the job rather than becoming
 * an answer the caller cannot distinguish from its child graph's own.
 *
 * <p>"Uncapturable" names a recording contract, not Java's: this is an ordinary unchecked
 * exception, and what refuses it is every boundary that would otherwise turn a failure into a
 * recorded outcome. Two fold an action's failure into a subgraph call's terminal result, {@code
 * SubgraphInvocation.capturesActionFailure} and {@code SubgraphExecution.terminal}; the generic
 * durable boundary in {@code RunnerContextImpl} writes a call's terminal state to the journal.
 * Those are the only writers, so nothing carrying this shell is journaled — which is what makes the
 * fault recoverable: a restart re-runs the action once its cause is corrected instead of replaying
 * a recorded failure forever.
 *
 * <p>The real fault is always the cause. Construction is package-private and the faults using it
 * expose only factories returning this type, so one cannot reach a gate bare. It is not an {@link
 * Error}: an Error would fly through every {@code catch (Exception)} on the path and silently
 * disable the generic failure handling there.
 */
public final class UncapturableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UncapturableException(Throwable frameworkFault) {
        super(frameworkFault);
    }

    /**
     * Whether {@code fault} is or carries this shell, the test every recording boundary shares. The
     * scan walks causes because a boundary meets the shell inside the {@code ExecutionException} an
     * awaited future rethrows as often as it meets it unwrapped.
     */
    public static boolean isUncapturable(Throwable fault) {
        return ExceptionUtils.findThrowable(fault, UncapturableException.class).isPresent();
    }
}
