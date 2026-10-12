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

package org.apache.flink.agents.plan.utils;

import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CancellationException;

/** Classifies cancellation signals without reading or changing the current thread's state. */
public final class CancellationUtils {
    private static final int MAX_CAUSE_DEPTH = 64;

    private CancellationUtils() {}

    /** Whether the cause chain contains an explicit thread-interruption signal. */
    public static boolean isInterruption(Throwable failure) {
        return containsCancellation(failure, false);
    }

    /**
     * Whether the cause chain contains an interruption or an explicit task cancellation. Bare
     * InterruptedIOException and SocketTimeoutException are ordinary IO failures. Suppressed
     * exceptions and exception messages do not determine cancellation.
     */
    public static boolean isCancellation(Throwable failure) {
        return containsCancellation(failure, true);
    }

    private static boolean containsCancellation(Throwable failure, boolean includeCancellation) {
        int depth = 0;
        for (Throwable current = failure;
                current != null && depth < MAX_CAUSE_DEPTH;
                current = current.getCause(), depth++) {
            if (current instanceof InterruptedException
                    || current instanceof ClosedByInterruptException
                    || (includeCancellation && current instanceof CancellationException)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
