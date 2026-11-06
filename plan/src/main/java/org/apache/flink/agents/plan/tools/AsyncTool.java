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

package org.apache.flink.agents.plan.tools;

import org.apache.flink.agents.api.context.AsyncFuture;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;

/** Optional asynchronous execution capability for a {@link Tool}. */
public interface AsyncTool {

    /**
     * Invoke the tool using the current action's execution context.
     *
     * <p>The implementation owns execution and any required durable semantics. The caller must not
     * wrap this invocation in another durable call. The returned future can participate in {@link
     * RunnerContext#gather}; returning a future does not imply that execution has not started.
     *
     * <p>Implementations use {@code callContext} to record execution timestamps and, if needed,
     * register a cancellation callback. The asynchronous entry point is used even when tool calls
     * are configured to run sequentially; in that case the caller awaits each future individually.
     */
    AsyncFuture<ToolResponse> callAsync(
            RunnerContext ctx, ToolParameters parameters, ToolCallContext callContext)
            throws Exception;
}
