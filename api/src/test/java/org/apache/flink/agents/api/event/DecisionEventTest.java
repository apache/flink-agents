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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionEventTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void requestRestoresTypedPayloadIdentityAndAttachments() throws Exception {
        DecisionRequest request =
                new DecisionRequest(
                        Map.of("receipt", true),
                        Map.of("eligible", DecisionRequest.Question.predicate("Has a receipt?")));
        DecisionRequestEvent event = new DecisionRequestEvent("registered-model", request);
        event.setAttachment("reference", Map.of("key", "review"));
        event.setSourceTimestamp(123L);
        DecisionRequestEvent reconstructed = DecisionRequestEvent.fromEvent(event);
        assertThat(reconstructed.getSourceTimestamp()).isEqualTo(123L);
        DecisionRequestEvent restored =
                (DecisionRequestEvent) Event.fromJson(MAPPER.writeValueAsString(event));
        assertThat(restored.getModel()).isEqualTo("registered-model");
        assertThat(MAPPER.<JsonNode>valueToTree(restored.getRequest()))
                .isEqualTo(MAPPER.valueToTree(request));
        assertEnvelope(event, restored);
        assertThat(MAPPER.<JsonNode>valueToTree(DecisionRequestEvent.fromEvent(restored)))
                .isEqualTo(MAPPER.valueToTree(restored));
    }

    @Test
    void refusalRemainsSuccessfulAndRetryDefaultsRoundTrip() throws Exception {
        DecisionResult result =
                new DecisionResult(
                        Map.of("restricted", new DecisionResult.RefusalAnswer("Cannot answer")),
                        null,
                        null,
                        Map.of());
        DecisionResponseEvent event =
                DecisionResponseEvent.success(UUID.randomUUID(), result, 0, 0);
        event.getAttributes().remove("retry_count");
        event.getAttributes().remove("total_retry_wait_sec");
        DecisionResponseEvent restored =
                (DecisionResponseEvent) Event.fromJson(MAPPER.writeValueAsString(event));
        assertThat(restored.isSuccess()).isTrue();
        assertThat(restored.getResponse().getAnswers().get("restricted"))
                .isInstanceOf(DecisionResult.RefusalAnswer.class);
        assertThat(restored.getRequestId()).isEqualTo(event.getRequestId());
        assertThat(restored.getRetryCount()).isZero();
        assertThat(restored.getTotalRetryWaitSec()).isZero();
        assertThatThrownBy(restored::getError).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureSerializesWithoutReadingThrowingResponseGetter() throws Exception {
        UUID requestId = UUID.randomUUID();
        DecisionResponseEvent event =
                DecisionResponseEvent.failed(requestId, "TimeoutError: timed out", 2, 3);
        event.setAttachment("reference", Map.of("key", "review"));
        DecisionResponseEvent restored =
                (DecisionResponseEvent) Event.fromJson(MAPPER.writeValueAsString(event));
        assertEnvelope(event, restored);
        assertThat(restored.isFailed()).isTrue();
        assertThat(restored.getError()).isEqualTo("TimeoutError: timed out");
        assertThat(restored.getRetryCount()).isEqualTo(2);
        assertThat(restored.getTotalRetryWaitSec()).isEqualTo(3);
        assertThat(restored.getAttr("response")).isNull();
        assertThatThrownBy(restored::getResponse)
                .isInstanceOfSatisfying(
                        DecisionResponseEvent.DecisionResponseException.class,
                        error -> assertThat(error.getRequestId()).isEqualTo(requestId))
                .hasMessage("TimeoutError: timed out");
        assertThat(MAPPER.<JsonNode>valueToTree(DecisionResponseEvent.fromEvent(restored)))
                .isEqualTo(MAPPER.valueToTree(restored));
    }

    @Test
    void malformedEnvelopesAreRejected() {
        assertThatThrownBy(() -> DecisionResponseEvent.success(UUID.randomUUID(), null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecisionResponseEvent.failed(UUID.randomUUID(), "", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                DecisionRequestEvent.fromEvent(
                                        new Event(
                                                DecisionRequestEvent.EVENT_TYPE,
                                                Map.of("model", "decision"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                DecisionRequestEvent.fromEvent(
                                        new Event(
                                                DecisionRequestEvent.EVENT_TYPE,
                                                Map.of("model", "decision", "request", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                DecisionResponseEvent.fromEvent(
                                        new Event(
                                                DecisionResponseEvent.EVENT_TYPE,
                                                Map.of(
                                                        "request_id",
                                                        UUID.randomUUID(),
                                                        "status",
                                                        "UNKNOWN"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertEnvelope(Event original, Event restored) {
        assertThat(restored.getId()).isEqualTo(original.getId());
        assertThat(restored.getAttachments()).isEqualTo(original.getAttachments());
    }
}
