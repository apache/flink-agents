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

import org.apache.flink.agents.api.subagent.SubagentFutures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A group may not mix internal and deferred handles, in either order: the two consult the calling
 * action's durable journal in different phases, so mixing them currently breaks durable recovery.
 * The homogeneous groups that must keep working are covered by the end-to-end batch tests.
 */
public class SubagentFutureGroupMixedBatchTest {

    /** The reported combination: an external deferred call combined with an internal one. */
    @Test
    void internalAndDeferredAreRefusedInEitherOrder() {
        assertRefused(internal("call-i").combine(deferred("call-d")), "call-i", "call-d");
        assertRefused(deferred("call-d").combine(internal("call-i")), "call-i", "call-d");
    }

    /** Reordering does not help, which is why the rule is about the mixture and not the order. */
    @Test
    void anInternalHandleSeparatedByADeferredOneIsRefused() {
        assertRefused(
                internal("call-i1").combine(deferred("call-d"), internal("call-i2")),
                "call-i1",
                "call-d");
    }

    /** Were the check any later, {@link #deferred} would fail these with an AssertionError. */
    @Test
    void refusalHappensBeforeAnyHandleIsPrepared() {
        SubagentFutures group = deferred("call-d").combine(internal("call-i"));

        assertThatThrownBy(group::awaitAll).isInstanceOf(IllegalStateException.class);
    }

    private static void assertRefused(
            SubagentFutures group, String internalCallId, String deferredCallId) {
        assertThatThrownBy(group::awaitAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "cannot mix the internal call InternalSubagentFuture session#"
                                + internalCallId)
                .hasMessageContaining(
                        "with the deferred call DeferredSubagentFuture session#" + deferredCallId);
    }

    /** The rule is decided before any prepare, so neither handle needs a context or a setup. */
    private static InternalSubagentFuture internal(String callId) {
        return new InternalSubagentFuture("session", callId, null, null, "prompt", null);
    }

    /** Fails the test if the group ever prepares a member it should have refused. */
    private static DeferredSubagentFuture deferred(String callId) {
        return new DeferredSubagentFuture(
                "session",
                callId,
                null,
                null,
                () -> {
                    throw new AssertionError("the group must refuse before preparing any member");
                });
    }
}
