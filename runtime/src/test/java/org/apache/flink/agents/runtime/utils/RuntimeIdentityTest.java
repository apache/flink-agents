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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeIdentityTest {
    @Test
    void identitiesFollowCausalityRatherThanPayloadOrCompletionOrder() {
        String root = RuntimeIdentity.deriveRootEventOccurrenceId("key", 1);
        Event input = new Event("input");
        input.setOccurrenceId(root);
        String first = RuntimeIdentity.deriveActionExecutionId(input, "first");
        String second = RuntimeIdentity.deriveActionExecutionId(input, "second");
        assertThat(first).isNotEqualTo(second);
        assertThat(root).isNotEqualTo(RuntimeIdentity.deriveRootEventOccurrenceId("key", 2));
        assertThat(root).isNotEqualTo(RuntimeIdentity.deriveRootEventOccurrenceId("other", 1));
        RuntimeIdentity.OutputIdentityAllocator outputs =
                new RuntimeIdentity.OutputIdentityAllocator(first);
        Event one = new Event("same");
        Event two = new Event("same");
        outputs.assign(one);
        outputs.assign(two);
        assertThat(one.getOccurrenceId()).isNotEqualTo(two.getOccurrenceId());
        assertThatThrownBy(() -> outputs.assign(one)).isInstanceOf(IllegalArgumentException.class);
        Event replay = new Event("same");
        new RuntimeIdentity.OutputIdentityAllocator(first).assign(replay);
        assertThat(replay.getOccurrenceId()).isEqualTo(one.getOccurrenceId());
        assertThat(RuntimeIdentity.deriveEmittedEventOccurrenceId(second, 0))
                .isNotEqualTo(one.getOccurrenceId());
        assertThat(RuntimeIdentity.hash("ab", "c")).isNotEqualTo(RuntimeIdentity.hash("a", "bc"));
    }
}
