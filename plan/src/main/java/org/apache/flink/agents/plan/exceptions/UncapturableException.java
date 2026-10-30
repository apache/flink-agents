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
package org.apache.flink.agents.plan.exceptions;

import org.apache.flink.annotation.Internal;

/**
 * A framework fault that must reach the runtime instead of becoming an application response or a
 * recorded durable outcome. The original fault is retained as the cause.
 */
@Internal
public final class UncapturableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final int MAX_CAUSE_DEPTH = 64;

    private UncapturableException(Throwable cause) {
        super(cause);
    }

    /** Marks a failure of a framework-owned operation, retaining an existing marker unchanged. */
    public static UncapturableException wrap(Throwable cause) {
        return cause instanceof UncapturableException
                ? (UncapturableException) cause
                : new UncapturableException(cause);
    }

    /**
     * Finds the marker through explicit causes; messages and suppressed errors do not classify it.
     */
    public static boolean isUncapturable(Throwable failure) {
        for (int depth = 0; failure != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (failure instanceof UncapturableException) {
                return true;
            }
            Throwable next = failure.getCause();
            if (next == failure) {
                break;
            }
            failure = next;
        }
        return false;
    }

    /** Preserves the complete cause chain when a conversion cannot throw a checked exception. */
    public static void rethrowIfPresent(Throwable failure) {
        if (isUncapturable(failure)) {
            throw wrap(failure);
        }
    }
}
