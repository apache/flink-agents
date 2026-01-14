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

import java.util.function.Supplier;

/**
 * Executor for Java actions that supports asynchronous execution.
 *
 * <p>This is the JDK 11 version that falls back to synchronous execution. On JDK 21+, the
 * Multi-release JAR will use a version that leverages Continuation API for true async execution.
 */
public class ContinuationActionExecutor {

    /** Creates a new ContinuationActionExecutor. */
    public ContinuationActionExecutor() {}

    /**
     * Executes the action. In JDK 11, this simply runs the action synchronously.
     *
     * @param context the continuation context
     * @param action the action to execute
     * @return true if the action completed, false if it yielded (always true in JDK 11)
     */
    public boolean executeAction(ContinuationContext context, Runnable action) {
        action.run();
        return true;
    }

    /**
     * Asynchronously executes the provided supplier. In JDK 11, this falls back to synchronous
     * execution.
     *
     * @param context the continuation context
     * @param supplier the supplier to execute
     * @param <T> the result type
     * @return the result of the supplier
     */
    public <T> T executeAsync(ContinuationContext context, Supplier<T> supplier) {
        // JDK 11: Fall back to synchronous execution
        return supplier.get();
    }

    public void close() {}

    /**
     * Returns whether continuation-based async execution is supported.
     *
     * @return true if Continuation API is available (JDK 21+), false otherwise
     */
    public static boolean isContinuationSupported() {
        return false;
    }
}
