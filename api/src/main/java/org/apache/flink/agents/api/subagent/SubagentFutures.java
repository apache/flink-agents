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

import java.util.List;

/**
 * A group of sub-agent handles to be resolved together. A group is not itself an invocation and
 * carries no {@code (sessionId, callId)} identity.
 */
public abstract class SubagentFutures {

    /** Whether every handle in the group has reached a terminal state. */
    public abstract boolean isDone();

    /**
     * Waits for every handle in the group and returns their outcomes in the order the handles were
     * added. Like {@link SubagentFuture#await()}, failures surface through failed {@link
     * SubagentResult}s.
     */
    public abstract List<SubagentResult> awaitAll() throws Exception;

    /** Requests cancellation of every handle in the group. */
    public void cancel() {}

    /** Adds more handles to the group. */
    public abstract SubagentFutures combine(SubagentFuture... others);
}
