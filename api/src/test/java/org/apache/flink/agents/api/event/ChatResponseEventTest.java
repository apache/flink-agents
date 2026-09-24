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
package org.apache.flink.agents.api.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ChatResponseEventTest {
    @Test
    void failureSurvivesEventSerializationWithoutCallingResponseGetter() throws Exception {
        UUID requestId = UUID.randomUUID();
        ChatResponseEvent original =
                ChatResponseEvent.failed(requestId, "TimeoutException: timed out", 2, 3);
        original.setUpstreamEventId(UUID.randomUUID());
        ChatResponseEvent restored =
                ChatResponseEvent.fromEvent(
                        Event.fromJson(new ObjectMapper().writeValueAsString(original)));
        assertThat(restored.getId()).isEqualTo(original.getId());
        assertThat(restored.getUpstreamEventId()).isEqualTo(original.getUpstreamEventId());
        assertThat(restored.isFailed()).isTrue();
        assertThat(restored.getError()).isEqualTo("TimeoutException: timed out");
        assertThat(restored.getRetryCount()).isEqualTo(2);
        assertThat(restored.getTotalRetryWaitSec()).isEqualTo(3);
        assertThatThrownBy(restored::getResponse)
                .isInstanceOfSatisfying(
                        ChatResponseEvent.ChatResponseException.class,
                        e -> assertThat(e.getRequestId()).isEqualTo(requestId))
                .hasMessage("TimeoutException: timed out");
    }

    @Test
    void successHasResponseButNoError() throws Exception {
        ChatResponseEvent restored =
                ChatResponseEvent.fromEvent(
                        Event.fromJson(
                                new ObjectMapper()
                                        .writeValueAsString(
                                                ChatResponseEvent.success(
                                                        UUID.randomUUID(),
                                                        ChatMessage.assistant("ok")))));
        assertThat(restored.isSuccess()).isTrue();
        assertThat(restored.getResponse().getContent()).isEqualTo("ok");
        assertThatThrownBy(restored::getError).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingStatusAndConflictingPayloads() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("request_id", UUID.randomUUID());
        attrs.put("response", ChatMessage.assistant("ok"));
        assertThatThrownBy(() -> new ChatResponseEvent(UUID.randomUUID(), attrs))
                .isInstanceOf(IllegalArgumentException.class);
        attrs.put("status", ChatResponseEvent.FAILED);
        attrs.put("error", "failure");
        assertThatThrownBy(() -> new ChatResponseEvent(UUID.randomUUID(), attrs))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatResponseEvent.failed(UUID.randomUUID(), "", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reactConsumerPropagatesUnhandledFailure() {
        ChatResponseEvent response =
                ChatResponseEvent.failed(UUID.randomUUID(), "provider failed", 0, 0);
        assertThatThrownBy(
                        () ->
                                org.apache.flink.agents.api.agents.ReActAgent.stopAction(
                                        response, null))
                .isInstanceOf(ChatResponseEvent.ChatResponseException.class)
                .hasMessage("provider failed");
    }
}
