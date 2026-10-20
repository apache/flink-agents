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
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.plan.AgentPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ChatSubgraphDefinitionTest {
    private final UUID requestId = UUID.randomUUID();
    private final ChatSubgraphDefinition definition =
            new ChatSubgraphDefinition(mock(AgentPlan.class));

    @Test
    void requiresExactlyOneResponse() {
        ChatResponseEvent response =
                ChatResponseEvent.success(requestId, new ChatResult(ChatMessage.assistant("ok")));
        // Event conversion reconstructs the typed event; identity is not part of the contract.
        assertThat(definition.finish(List.of(response))).isEqualTo(response);
        assertThatThrownBy(() -> definition.finish(List.of()))
                .hasMessageContaining("exactly one response");
        assertThatThrownBy(() -> definition.finish(List.of(response, response)))
                .hasMessageContaining("exactly one response");
    }

    @Test
    void ordinaryObservationEventsRemainPrivateAndRuntimeFailuresAreNotCaptured() {
        assertThat(definition.matchActions(new Event("observation"))).isEmpty();
        assertThat(definition.isOutput(new Event("observation"))).isFalse();
        // A chat response is terminal for its graph, so the failure policy does not apply.
        assertThat(definition.capturesActionFailures()).isFalse();
    }
}
