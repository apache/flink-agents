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

import javax.annotation.Nullable;

/**
 * The parallel engine failed to move an action task's execution between a worker and the mailbox:
 * suspending the task's context before the lock is released, or resuming it after ownership is
 * re-acquired.
 *
 * <p>Either is a framework fault rather than the action's own outcome, so the factories below hand
 * back an {@link UncapturableException} already wrapped around it and construction stays
 * package-private: a lifecycle fault can neither be recorded as a business action result nor reach
 * a folding gate bare.
 */
public final class ActionTaskLifecycleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ActionTaskLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The task's context could not be released before the execution lock was handed off. */
    public static UncapturableException suspendFailed(Throwable cause) {
        return new UncapturableException(
                new ActionTaskLifecycleException("Could not suspend action task", cause));
    }

    /**
     * The task's context could not be rebound after ownership was re-acquired. {@code
     * supplierFailure} is whatever the handed-off call had itself failed with, kept as suppressed
     * on the shell so the framework fault does not hide it.
     */
    public static UncapturableException resumeFailed(
            Throwable cause, @Nullable Throwable supplierFailure) {
        UncapturableException shell =
                new UncapturableException(
                        new ActionTaskLifecycleException("Could not resume action task", cause));
        if (supplierFailure != null) {
            shell.addSuppressed(supplierFailure);
        }
        return shell;
    }
}
