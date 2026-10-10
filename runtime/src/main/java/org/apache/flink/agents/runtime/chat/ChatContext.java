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

import javax.annotation.Nullable;

/**
 * Chat-related context bound to one action execution. The task retains this object across
 * suspension and passes it to generated continuation tasks. When switching or restoring tasks, the
 * shared runner context replaces its reference as a unit rather than updating the three fields
 * separately.
 *
 * <p>The manager is shared by all tasks in the operator, including Java and Python tasks. The owner
 * keeps the current action's call counter and key. The optional active call identifies the chat
 * invocation whose built-in Chat/Tool action is running; it does not identify a call that the
 * current action is waiting for. An ordinary caller therefore has no active call even while
 * awaiting chat.
 *
 * <p>This class only groups references; it neither manages calls nor checkpoints their state. The
 * references are fixed, while their targets retain their own runtime state and lifecycle.
 */
public final class ChatContext {
    private final ChatCallManager manager;
    private final ChatCallOwner owner;
    @Nullable private final ChatInvocation activeCall;

    public ChatContext(
            ChatCallManager manager, ChatCallOwner owner, @Nullable ChatInvocation activeCall) {
        this.manager = manager;
        this.owner = owner;
        this.activeCall = activeCall;
    }

    /** Returns the operator-wide manager, not a manager created for this action. */
    public ChatCallManager getManager() {
        return manager;
    }

    /** Returns the call counter and key belonging to this action execution. */
    public ChatCallOwner getOwner() {
        return owner;
    }

    /** Returns the invocation being executed, or null for an ordinary caller action. */
    @Nullable
    public ChatInvocation getActiveCall() {
        return activeCall;
    }
}
