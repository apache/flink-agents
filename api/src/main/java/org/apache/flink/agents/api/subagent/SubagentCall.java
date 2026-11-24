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
package org.apache.flink.agents.api.subagent;

import javax.annotation.Nullable;

import java.util.concurrent.Callable;

/**
 * Execution and optional recovery logic for a deferred sub-agent invocation. The framework owns the
 * durable identity and result serialization type.
 */
@FunctionalInterface
public interface SubagentCall extends Callable<SubagentResult> {

    /**
     * Returns optional recovery logic for an invocation with a persisted pending record.
     *
     * <p>A recorded terminal outcome is replayed without invoking either callback. For a pending
     * invocation, the runtime executes this reconciler instead of {@link #call()}; returning {@code
     * null} causes it to execute {@code call()} again. The reconciler's result or ordinary
     * exception is recorded as the invocation's outcome, subject to the same durable failure rules
     * as {@code call()}.
     */
    default @Nullable Callable<SubagentResult> reconciler() {
        return null;
    }
}
