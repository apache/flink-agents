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

package org.apache.flink.agents.runtime.subagent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks sub-agent handles submitted by one action execution but not resolved yet: the runtime
 * future implementations record handles here directly, and {@link BaseSubagentSetup} owns one per
 * task, checking it when the task finishes.
 *
 * <p>Per-task heap state owned by the base for the duration of one action execution.
 * Mailbox-confined: no synchronization. The registry preserves insertion order so failure messages
 * list the dropped handles deterministically.
 */
public final class PendingSubagentCallRegistry {

    private final Set<String> pendingCalls = new LinkedHashSet<>();

    /** Records a handle; duplicate identities collapse to one entry. */
    public void trackPendingSubagentCall(String callIdentity) {
        pendingCalls.add(callIdentity);
    }

    /** Drops a resolved handle; no-op when the identity is unknown. */
    public void untrackPendingSubagentCall(String callIdentity) {
        pendingCalls.remove(callIdentity);
    }

    public boolean isEmpty() {
        return pendingCalls.isEmpty();
    }

    /**
     * Fails when the finished action left a sub-agent handle unresolved: a deferred request was
     * never issued, an async run's outcome was never collected. Clears the registry before throwing
     * so the failure cannot be reported twice.
     *
     * @param actionName the action being checked, named in the failure message.
     */
    public void checkEmpty(String actionName) {
        if (!pendingCalls.isEmpty()) {
            List<String> dropped = new ArrayList<>(pendingCalls);
            pendingCalls.clear();
            throw new IllegalStateException(
                    "Action "
                            + actionName
                            + " finished without resolving the sub-agent calls it submitted: "
                            + dropped
                            + ". Resolve every handle returned by submit(), individually or through "
                            + "SubagentFutures.");
        }
    }
}
