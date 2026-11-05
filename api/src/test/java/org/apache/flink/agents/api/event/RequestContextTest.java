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
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.vectorstores.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Optional opaque context on built-in request and response events. */
class RequestContextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> context() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("task", "summary");
        context.put("item_id", "42");
        context.put("n", 1);
        context.put("ok", true);
        context.put("tags", List.of("a"));
        return context;
    }

    @Test
    void omittedContextIsAbsentFromTheSerializedEvent() throws Exception {
        ChatRequestEvent request = new ChatRequestEvent("model", List.of(ChatMessage.user("hi")));
        assertThat(request.getContext()).isNull();
        assertThat(request.getAttributes()).doesNotContainKey("context");
        assertThat(MAPPER.writeValueAsString(request)).doesNotContain("\"context\"");

        ChatResponseEvent response =
                ChatResponseEvent.success(
                        UUID.randomUUID(), new ChatResult(ChatMessage.assistant("ok")));
        assertThat(response.getContext()).isNull();
        assertThat(MAPPER.writeValueAsString(response)).doesNotContain("\"context\"");
    }

    @Test
    void emptyContextIsKeptAndNullClearsIt() {
        Map<String, Object> empty = new HashMap<>();
        ChatRequestEvent request =
                new ChatRequestEvent("model", List.of(ChatMessage.user("hi"))).withContext(empty);
        assertThat(request.getAttributes()).containsKey("context");
        assertThat(request.getContext()).isEqualTo(empty);

        request.withContext(null);
        assertThat(request.getAttributes()).doesNotContainKey("context");
        assertThat(request.getContext()).isNull();
    }

    @Test
    void eachRequestAndResponseRoundTripsItsOwnContext() throws Exception {
        Map<String, Object> context = context();
        UUID requestId = UUID.randomUUID();
        DecisionRequest decisionRequest =
                new DecisionRequest(
                        "evidence",
                        Map.of("urgent", DecisionRequest.Question.predicate("Needs attention?")));
        DecisionResult decisionResult =
                new DecisionResult(
                        Map.of("urgent", new DecisionResult.PredicateAnswer(0.8)),
                        null,
                        null,
                        Map.of());

        List<Event> events =
                List.of(
                        new ChatRequestEvent(
                                        "model",
                                        List.of(ChatMessage.user("hi")),
                                        Map.of("tone", "brief"),
                                        null,
                                        context)
                                .withContext(List.of("also-a-context")),
                        new ChatRequestEvent(
                                "model", List.of(ChatMessage.user("hi")), null, null, "summary-42"),
                        ChatResponseEvent.success(
                                        requestId, new ChatResult(ChatMessage.assistant("ok")))
                                .withContext(context),
                        ChatResponseEvent.failed(requestId, "TimeoutException: timed out", 1, 2)
                                .withContext(context),
                        new ToolRequestEvent(
                                "model",
                                List.of(new ToolCallBlock("call-1", "lookup", Map.of("x", 1))),
                                context),
                        new ToolResponseEvent(
                                        requestId,
                                        Map.of("call-1", ToolResponse.text("ok")),
                                        Map.of("call-1", true),
                                        Map.of())
                                .withContext(context),
                        new ContextRetrievalRequestEvent("what is flink", "store", 2, context),
                        new ContextRetrievalResponseEvent(
                                        requestId,
                                        "what is flink",
                                        List.of(new Document("body", Map.of(), "d1")))
                                .withContext(context),
                        new DecisionRequestEvent("decision", decisionRequest, Map.of(), context),
                        DecisionResponseEvent.success(requestId, decisionResult, 0, 0)
                                .withContext(context),
                        DecisionResponseEvent.failed(requestId, "provider down", 2, 3)
                                .withContext(context));

        for (Event event : events) {
            Event restored = Event.fromJson(MAPPER.writeValueAsString(event));
            assertThat(restored).isInstanceOf(event.getClass());
            assertThat(restored.getAttr("context")).isEqualTo(event.getAttr("context"));
            assertThat(restored.getId()).isEqualTo(event.getId());
        }
    }

    @Test
    void foreignJsonContextSurvivesTheCrossLanguageBoundary() throws Exception {
        String requestJson =
                "{"
                        + "\"id\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"type\":\"_chat_request_event\","
                        + "\"attributes\":{"
                        + "\"model\":\"test-model\","
                        + "\"messages\":[{\"role\":\"user\",\"blocks\":[{\"type\":\"text\",\"text\":\"hi\"}],\"metadata\":{}}],"
                        + "\"prompt_args\":{},"
                        + "\"output_schema\":null,"
                        + "\"context\":{\"task\":\"summary\",\"item_id\":\"42\",\"n\":1,\"ok\":true,\"tags\":[\"a\"]}"
                        + "},"
                        + "\"attachments\":{\"trace\":\"keep\"}"
                        + "}";
        ChatRequestEvent request = ChatRequestEvent.fromEvent(Event.fromJson(requestJson));
        assertThat(request.getModel()).isEqualTo("test-model");
        assertThat(request.getMessages().get(0).getText()).isEqualTo("hi");
        assertThat(request.getContext()).isEqualTo(context());
        assertThat(request.getAttachment("trace")).isEqualTo("keep");

        String failedJson =
                "{"
                        + "\"id\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"type\":\"_chat_response_event\","
                        + "\"attributes\":{"
                        + "\"request_id\":\"00000000-0000-0000-0000-000000000002\","
                        + "\"status\":\"FAILED\","
                        + "\"response\":null,"
                        + "\"error\":\"TimeoutError: timed out\","
                        + "\"retry_count\":2,"
                        + "\"total_retry_wait_sec\":3,"
                        + "\"context\":{\"task\":\"summary\",\"item_id\":\"42\"}"
                        + "},"
                        + "\"attachments\":{}"
                        + "}";
        ChatResponseEvent failed = ChatResponseEvent.fromEvent(Event.fromJson(failedJson));
        assertThat(failed.isFailed()).isTrue();
        assertThat(failed.getError()).isEqualTo("TimeoutError: timed out");
        assertThat(failed.getContext()).isEqualTo(Map.of("task", "summary", "item_id", "42"));
    }

    @Test
    void unknownAttributeIsStillRejectedWhenContextIsPresent() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("model", "m");
        attributes.put("messages", List.of(ChatMessage.user("hi")));
        attributes.put("context", Map.of("task", "summary"));
        attributes.put("extra", 1);
        Event event = new Event(UUID.randomUUID(), ChatRequestEvent.EVENT_TYPE, attributes);

        assertThatThrownBy(() -> ChatRequestEvent.fromEvent(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extra");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reusedContextMapIsCopiedIncludingNestedMapsAndLists() {
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("task", "summary");
        List<String> tags = new ArrayList<>();
        tags.add("keep");
        shared.put("tags", tags);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("label", "keep");
        shared.put("meta", nested);

        ChatRequestEvent requestA =
                new ChatRequestEvent("model", List.of(ChatMessage.user("a"))).withContext(shared);
        shared.put("task", "risk");
        tags.add("changed");
        nested.put("label", "changed");
        ChatRequestEvent requestB =
                new ChatRequestEvent("model", List.of(ChatMessage.user("b"))).withContext(shared);

        assertThat(requestA.getContext())
                .isEqualTo(
                        Map.of(
                                "task",
                                "summary",
                                "tags",
                                List.of("keep"),
                                "meta",
                                Map.of("label", "keep")));
        assertThat(requestB.getContext())
                .isEqualTo(
                        Map.of(
                                "task",
                                "risk",
                                "tags",
                                List.of("keep", "changed"),
                                "meta",
                                Map.of("label", "changed")));
        Map<String, Object> storedA = (Map<String, Object>) requestA.getContext();
        assertThat(storedA).isNotSameAs(shared);
        assertThat(storedA.get("tags")).isNotSameAs(tags);
        assertThat(storedA.get("meta")).isNotSameAs(nested);
    }

    @Test
    void rowShapedContextStaysTheSuppliedJsonObject() throws Exception {
        String requestJson =
                "{"
                        + "\"id\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"type\":\"_chat_request_event\","
                        + "\"attributes\":{"
                        + "\"model\":\"model\","
                        + "\"messages\":[{\"role\":\"user\",\"blocks\":[{\"type\":\"text\",\"text\":\"hi\"}],\"metadata\":{}}],"
                        + "\"context\":{\"type\":\"Row\",\"values\":[1],\"label\":\"keep\"}"
                        + "}}";
        ChatRequestEvent request = ChatRequestEvent.fromEvent(Event.fromJson(requestJson));
        assertThat(request.getContext())
                .isEqualTo(Map.of("type", "Row", "values", List.of(1), "label", "keep"));

        String responseJson =
                "{"
                        + "\"id\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"type\":\"_chat_response_event\","
                        + "\"attributes\":{"
                        + "\"request_id\":\"00000000-0000-0000-0000-000000000002\","
                        + "\"status\":\"SUCCESS\","
                        + "\"response\":{\"message\":{\"role\":\"assistant\",\"blocks\":[{\"type\":\"text\",\"text\":\"ok\"}],\"metadata\":{}}},"
                        + "\"error\":null,"
                        + "\"retry_count\":0,"
                        + "\"total_retry_wait_sec\":0,"
                        + "\"context\":{\"type\":\"Row\",\"values\":[1],\"label\":\"keep\"}"
                        + "}}";
        ChatResponseEvent response = ChatResponseEvent.fromEvent(Event.fromJson(responseJson));
        assertThat(response.getContext())
                .isEqualTo(Map.of("type", "Row", "values", List.of(1), "label", "keep"));
    }
}
