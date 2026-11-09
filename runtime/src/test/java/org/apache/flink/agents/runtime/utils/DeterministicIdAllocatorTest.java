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
package org.apache.flink.agents.runtime.utils;

import org.apache.flink.agents.api.Event;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DeterministicIdAllocatorTest {
    private Event occurrence(String id) {
        Event event = new Event("ask");
        event.setOccurrenceId(id);
        return event;
    }

    @Test
    void identicalPayloadOccurrencesHaveDistinctCallIds() {
        String parent =
                RuntimeIdentity.deriveActionExecutionId(
                        occurrence(RuntimeIdentity.deriveRootEventOccurrenceId("key", 1)), "emit");
        String first =
                RuntimeIdentity.deriveActionExecutionId(
                        occurrence(RuntimeIdentity.deriveEmittedEventOccurrenceId(parent, 0)),
                        "ask");
        String second =
                RuntimeIdentity.deriveActionExecutionId(
                        occurrence(RuntimeIdentity.deriveEmittedEventOccurrenceId(parent, 1)),
                        "ask");
        assertThat(new DeterministicIdAllocator(first, "chat").nextSessionId())
                .isNotEqualTo(new DeterministicIdAllocator(second, "chat").nextSessionId());
    }

    @Test
    void replayIsStableButTargetsAndExecutionsAreDistinct() {
        DeterministicIdAllocator first = new DeterministicIdAllocator("execution", "chat");
        DeterministicIdAllocator replay = new DeterministicIdAllocator("execution", "chat");
        String session = first.nextSessionId();
        assertThat(replay.nextSessionId()).isEqualTo(session);
        assertThat(first.nextCallId(session)).isEqualTo(replay.nextCallId(session));
        assertThat(first.nextCallId(session)).isEqualTo(replay.nextCallId(session));
        assertThat(first.nextSessionId()).isEqualTo(replay.nextSessionId()).isNotEqualTo(session);
        assertThat(new DeterministicIdAllocator("other", "chat").nextSessionId())
                .isNotEqualTo(session);
        assertThat(new DeterministicIdAllocator("execution", "subagent").nextSessionId())
                .isNotEqualTo(session);
    }

    @Test
    void explicitSessionStillScopesCallsToExecutionAndTarget() {
        String first = new DeterministicIdAllocator("first", "target").nextCallId("shared-session");
        assertThat(new DeterministicIdAllocator("second", "target").nextCallId("shared-session"))
                .isNotEqualTo(first);
        assertThat(new DeterministicIdAllocator("first", "other").nextCallId("shared-session"))
                .isNotEqualTo(first);
        assertThat(new DeterministicIdAllocator("first", "target").nextCallId("shared-session"))
                .isEqualTo(first);
    }

    @Test
    void missingIdentityAndTargetAreRejected() {
        assertThatNullPointerException()
                .isThrownBy(
                        () -> RuntimeIdentity.deriveActionExecutionId(new Event("ask"), "action"));
        assertThatNullPointerException()
                .isThrownBy(() -> new DeterministicIdAllocator("execution", null));
    }
}
