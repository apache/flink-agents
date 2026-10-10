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
package org.apache.flink.agents.runtime.chat;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.runtime.subagent.SubagentIdAllocator;

/**
 * Caller key and chat-call counter for one action execution: one action processing one event. This
 * is not an action definition, an executor, or the state of an individual chat invocation. Multiple
 * chat calls issued during that execution share this owner, but each has its own {@link
 * ChatInvocation}.
 *
 * <p>The action task's context creates and retains this object. Suspending and resuming the action,
 * including transferring its context to a generated successor task, preserves the same owner.
 * Switching the shared runner context to another action installs that action's owner instead.
 *
 * <p>{@link #nextCallId()} assigns deterministic IDs from the caller's key, sequence number, action
 * name, event, and call ordinal. On failure recovery this owner is recreated; replaying the same
 * caller facts and call order reproduces the same call IDs for durable-result lookup. The owner
 * itself is not checkpointed. The caller key also lets the manager clean up abandoned calls when
 * record processing finishes.
 *
 * <p>Call ID allocation is confined to the action/mailbox thread.
 */
public final class ChatCallOwner {
    private final SubagentIdAllocator ids;
    private final Object key;

    /**
     * Creates a fresh runtime owner with a deterministic namespace for its chat calls.
     *
     * @param key the caller's keyed record, also used to clean up abandoned calls
     * @param sequenceNumber the caller action task's sequence number
     * @param actionName the caller action's name
     * @param event the event being processed by the caller action
     */
    public ChatCallOwner(Object key, long sequenceNumber, String actionName, Event event) {
        this.key = key;
        ids = new SubagentIdAllocator(key, sequenceNumber, actionName, event, "builtin-chat");
    }

    /** Returns the next replay-stable chat invocation ID; advances this owner's call ordinal. */
    String nextCallId() {
        return ids.nextSessionId();
    }

    /** Returns the caller's key for record-scoped cleanup by {@link ChatCallManager}. */
    Object getKey() {
        return key;
    }
}
