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

/** Optional application-defined user context on built-in request and response events. */
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
        assertThat(request.getUserContext()).isNull();
        assertThat(request.getAttributes()).doesNotContainKey("user_context");
        assertThat(MAPPER.writeValueAsString(request)).doesNotContain("\"user_context\"");

        ChatResponseEvent response =
                ChatResponseEvent.success(
                        UUID.randomUUID(), new ChatResult(ChatMessage.assistant("ok")));
        assertThat(response.getUserContext()).isNull();
        assertThat(MAPPER.writeValueAsString(response)).doesNotContain("\"user_context\"");
    }

    @Test
    void emptyUserContextIsKeptAndNullClearsAResponse() {
        Map<String, Object> empty = new HashMap<>();
        ChatRequestEvent request =
                new ChatRequestEvent("model", List.of(ChatMessage.user("hi")), null, null, empty);
        assertThat(request.getAttributes()).containsKey("user_context");
        assertThat(request.getUserContext()).isEqualTo(empty);

        ChatResponseEvent response =
                ChatResponseEvent.success(
                                UUID.randomUUID(), new ChatResult(ChatMessage.assistant("ok")))
                        .withUserContext(empty);
        assertThat(response.getUserContext()).isEqualTo(empty);
        response.withUserContext(null);
        assertThat(response.getAttributes()).doesNotContainKey("user_context");
        assertThat(response.getUserContext()).isNull();
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
                                context),
                        new ChatRequestEvent(
                                "model",
                                List.of(ChatMessage.user("hi")),
                                null,
                                null,
                                Map.of("label", "summary-42")),
                        ChatResponseEvent.success(
                                        requestId, new ChatResult(ChatMessage.assistant("ok")))
                                .withUserContext(context),
                        ChatResponseEvent.failed(requestId, "TimeoutException: timed out", 1, 2)
                                .withUserContext(context),
                        new ToolRequestEvent(
                                "model",
                                List.of(new ToolCallBlock("call-1", "lookup", Map.of("x", 1))),
                                context),
                        new ToolResponseEvent(
                                        requestId,
                                        Map.of("call-1", ToolResponse.text("ok")),
                                        Map.of("call-1", true),
                                        Map.of())
                                .withUserContext(context),
                        new ContextRetrievalRequestEvent("what is flink", "store", 2, context),
                        new ContextRetrievalResponseEvent(
                                        requestId,
                                        "what is flink",
                                        List.of(new Document("body", Map.of(), "d1")))
                                .withUserContext(context),
                        new DecisionRequestEvent("decision", decisionRequest, Map.of(), context),
                        DecisionResponseEvent.success(requestId, decisionResult, 0, 0)
                                .withUserContext(context),
                        DecisionResponseEvent.failed(requestId, "provider down", 2, 3)
                                .withUserContext(context));

        for (Event event : events) {
            Event restored = Event.fromJson(MAPPER.writeValueAsString(event));
            assertThat(restored).isInstanceOf(event.getClass());
            assertThat(restored.getAttr("user_context")).isEqualTo(event.getAttr("user_context"));
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
                        + "\"user_context\":{\"task\":\"summary\",\"item_id\":\"42\",\"n\":1,\"ok\":true,\"tags\":[\"a\"]}"
                        + "},"
                        + "\"attachments\":{\"trace\":\"keep\"}"
                        + "}";
        ChatRequestEvent request = ChatRequestEvent.fromEvent(Event.fromJson(requestJson));
        assertThat(request.getModel()).isEqualTo("test-model");
        assertThat(request.getMessages().get(0).getText()).isEqualTo("hi");
        assertThat(request.getUserContext()).isEqualTo(context());
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
                        + "\"user_context\":{\"task\":\"summary\",\"item_id\":\"42\"}"
                        + "},"
                        + "\"attachments\":{}"
                        + "}";
        ChatResponseEvent failed = ChatResponseEvent.fromEvent(Event.fromJson(failedJson));
        assertThat(failed.isFailed()).isTrue();
        assertThat(failed.getError()).isEqualTo("TimeoutError: timed out");
        assertThat(failed.getUserContext()).isEqualTo(Map.of("task", "summary", "item_id", "42"));
    }

    @Test
    void unknownAttributeIsStillRejectedWhenContextIsPresent() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("model", "m");
        attributes.put("messages", List.of(ChatMessage.user("hi")));
        attributes.put("user_context", Map.of("task", "summary"));
        attributes.put("extra", 1);
        Event event = new Event(UUID.randomUUID(), ChatRequestEvent.EVENT_TYPE, attributes);

        assertThatThrownBy(() -> ChatRequestEvent.fromEvent(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extra");
    }

    @Test
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
                new ChatRequestEvent("model", List.of(ChatMessage.user("a")), null, null, shared);
        shared.put("task", "risk");
        tags.add("changed");
        nested.put("label", "changed");
        ChatRequestEvent requestB =
                new ChatRequestEvent("model", List.of(ChatMessage.user("b")), null, null, shared);

        assertThat(requestA.getUserContext())
                .isEqualTo(
                        Map.of(
                                "task",
                                "summary",
                                "tags",
                                List.of("keep"),
                                "meta",
                                Map.of("label", "keep")));
        assertThat(requestB.getUserContext())
                .isEqualTo(
                        Map.of(
                                "task",
                                "risk",
                                "tags",
                                List.of("keep", "changed"),
                                "meta",
                                Map.of("label", "changed")));
        Map<String, Object> storedA = requestA.getUserContext();
        assertThat(storedA).isNotSameAs(shared);
        assertThat(storedA.get("tags")).isNotSameAs(tags);
        assertThat(storedA.get("meta")).isNotSameAs(nested);
    }

    @Test
    void userContextJsonObjectRoundTripsAsAMap() throws Exception {
        String requestJson =
                "{"
                        + "\"id\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"type\":\"_chat_request_event\","
                        + "\"attributes\":{"
                        + "\"model\":\"model\","
                        + "\"messages\":[{\"role\":\"user\",\"blocks\":[{\"type\":\"text\",\"text\":\"hi\"}],\"metadata\":{}}],"
                        + "\"user_context\":{\"type\":\"Row\",\"values\":[1],\"label\":\"keep\"}"
                        + "}}";
        ChatRequestEvent request = ChatRequestEvent.fromEvent(Event.fromJson(requestJson));
        assertThat(request.getUserContext())
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
                        + "\"user_context\":{\"type\":\"Row\",\"values\":[1],\"label\":\"keep\"}"
                        + "}}";
        ChatResponseEvent response = ChatResponseEvent.fromEvent(Event.fromJson(responseJson));
        assertThat(response.getUserContext())
                .isEqualTo(Map.of("type", "Row", "values", List.of(1), "label", "keep"));
    }

    @Test
    void nonMapUserContextIsRejected() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("model", "m");
        attributes.put("messages", List.of(ChatMessage.user("hi")));
        attributes.put("user_context", "summary-42");
        Event event = new Event(UUID.randomUUID(), ChatRequestEvent.EVENT_TYPE, attributes);

        assertThatThrownBy(() -> ChatRequestEvent.fromEvent(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user_context")
                .hasMessageContaining("Map");
    }

    @Test
    void nestedArrayIsCopiedSoLaterMutationDoesNotChangeAnEarlierRequest() {
        String[] labels = new String[] {"summary"};
        int[] counts = new int[] {1};
        List<Object> nested = new ArrayList<>();
        String[] inner = new String[] {"keep"};
        nested.add(inner);
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("labels", labels);
        shared.put("counts", counts);
        shared.put("nested", nested);

        ChatRequestEvent requestA =
                new ChatRequestEvent("model", List.of(ChatMessage.user("a")), null, null, shared);
        labels[0] = "risk";
        counts[0] = 9;
        inner[0] = "changed";
        ChatRequestEvent requestB =
                new ChatRequestEvent("model", List.of(ChatMessage.user("b")), null, null, shared);

        Map<String, Object> storedA = requestA.getUserContext();
        assertThat((String[]) storedA.get("labels")).containsExactly("summary");
        assertThat((int[]) storedA.get("counts")).containsExactly(1);
        assertThat((String[]) ((List<?>) storedA.get("nested")).get(0)).containsExactly("keep");
        assertThat(storedA.get("labels")).isNotSameAs(labels);
        assertThat(storedA.get("counts")).isNotSameAs(counts);

        Map<String, Object> storedB = requestB.getUserContext();
        assertThat((String[]) storedB.get("labels")).containsExactly("risk");
        assertThat((int[]) storedB.get("counts")).containsExactly(9);
        assertThat((String[]) ((List<?>) storedB.get("nested")).get(0)).containsExactly("changed");
    }
}
