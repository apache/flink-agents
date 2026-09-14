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

import java.util.LinkedHashSet;
import java.util.Set;

/** The per-action-execution set of sub-agent handles submitted but not yet resolved. */
public final class PendingSubagentCallRegistry {

    private final Set<String> pendingCalls = new LinkedHashSet<>();

    /** The action the pending handles belong to, named in the failure message. */
    private String actionName;

    public PendingSubagentCallRegistry(String actionName) {
        this.actionName = actionName;
    }

    /** Adopts the continuation's action when the execution moves onto another task. */
    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    /** Records a handle. Duplicate identities collapse to one entry. */
    public void trackPendingSubagentCall(String callIdentity) {
        pendingCalls.add(callIdentity);
    }

    /** Drops a resolved handle and does nothing when the identity is unknown. */
    public void untrackPendingSubagentCall(String callIdentity) {
        pendingCalls.remove(callIdentity);
    }

    public boolean isEmpty() {
        return pendingCalls.isEmpty();
    }

    /** Fails the action when it left a sub-agent handle unresolved. */
    public void checkEmpty() {
        if (!pendingCalls.isEmpty()) {
            throw new IllegalStateException(
                    "Action "
                            + actionName
                            + " finished without resolving the sub-agent calls it submitted: "
                            + pendingCalls
                            + ". Resolve every handle returned by submit(), individually or through "
                            + "SubagentFutures.");
        }
    }
}
