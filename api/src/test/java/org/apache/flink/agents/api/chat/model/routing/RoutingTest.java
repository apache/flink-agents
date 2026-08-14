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

package org.apache.flink.agents.api.chat.model.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the model-routing API layer (strategy, router, decision). */
class RoutingTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static RoutingContext ctx(String userMessage) {
        return new RoutingContext(
                REQUEST_ID,
                "router",
                List.of(new ChatMessage(MessageRole.USER, userMessage)),
                Map.of(),
                List.of(new RoutingCandidate("small"), new RoutingCandidate("big")));
    }

    @Test
    void ruleMatchSelectsCandidate() throws Exception {
        RuleBasedRoutingStrategy strategy =
                new RuleBasedRoutingStrategy(Map.of("rules", Map.of("big", "\\b(code|sql)\\b")));
        RoutingDecision decision = strategy.route(ctx("please write some SQL for me"));
        assertFalse(decision.isAbstain());
        assertEquals("big", decision.getSelectedModel());
    }

    @Test
    void ruleMatchesLatestUserMessageNotFirst() throws Exception {
        // Multi-turn: turn 1 asked for SQL, but the current question is small talk. The rule
        // strategy must route on the most recent user message, not the oldest.
        RuleBasedRoutingStrategy strategy =
                new RuleBasedRoutingStrategy(Map.of("rules", Map.of("big", "\\b(code|sql)\\b")));
        RoutingContext multiTurn =
                new RoutingContext(
                        REQUEST_ID,
                        "router",
                        List.of(
                                new ChatMessage(MessageRole.USER, "please write some SQL for me"),
                                new ChatMessage(MessageRole.ASSISTANT, "SELECT 1;"),
                                new ChatMessage(MessageRole.USER, "thanks, how is the weather?")),
                        Map.of(),
                        List.of(new RoutingCandidate("small"), new RoutingCandidate("big")));
        assertTrue(strategy.route(multiTurn).isAbstain());
        assertEquals("thanks, how is the weather?", multiTurn.lastUserMessage());
        assertEquals("please write some SQL for me", multiTurn.firstUserMessage());
    }

    @Test
    void ruleNoMatchAbstains() throws Exception {
        RuleBasedRoutingStrategy strategy =
                new RuleBasedRoutingStrategy(Map.of("rules", Map.of("big", "\\b(code|sql)\\b")));
        RoutingDecision decision = strategy.route(ctx("hello, how are you?"));
        assertTrue(decision.isAbstain());
        assertNull(decision.getSelectedModel());
    }

    @Test
    void modelRouterBuildsAndRoutes() throws Exception {
        ResourceDescriptor descriptor =
                ModelRouter.of("small", "big")
                        .strategy(Strategies.rules(Map.of("big", "\\b(code|sql)\\b")))
                        .defaultModel("small")
                        .fallback(true)
                        .build();

        ModelRouter router = new ModelRouter(descriptor, null);

        assertEquals(List.of("small", "big"), router.getCandidateNames());
        assertEquals("small", router.getDefaultModel().orElse(null));
        assertTrue(router.isFallbackEnabled());
        assertTrue(router.isCandidate("big"));
        assertFalse(router.isCandidate("unknown"));

        RoutingContext context =
                new RoutingContext(
                        REQUEST_ID,
                        "router",
                        List.of(new ChatMessage(MessageRole.USER, "write code")),
                        Map.of(),
                        router.getCandidates());
        assertEquals("big", router.route(context).getSelectedModel());
        assertEquals(REQUEST_ID, context.getRequestId());
    }

    @Test
    void candidateDescriptionsReachStrategies() throws Exception {
        // Descriptions declared on the router flow into the RoutingContext candidates, which is
        // how semantic strategies (and future framework-managed LLM routing) learn what each
        // candidate is for.
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .describe("small", "fast and cheap; chit-chat")
                                .describe("big", "strong; code and SQL")
                                .strategy(Strategies.rules(Map.of()))
                                .defaultModel("small")
                                .build(),
                        null);
        assertEquals("fast and cheap; chit-chat", router.getCandidates().get(0).getDescription());
        assertEquals("strong; code and SQL", router.getCandidates().get(1).getDescription());
    }

    @Test
    void describeRejectsUnknownCandidate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRouter.of("small", "big").describe("huge", "does not exist"));
    }

    @Test
    void defaultModelMustBeCandidate() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ModelRouter(
                                ModelRouter.of("small", "big")
                                        .strategy(Strategies.rules(Map.of()))
                                        .defaultModel("huge")
                                        .build(),
                                null));
    }

    @Test
    void modelRoutingEventRequestIdSurvivesStringForm() {
        // Simulate an EventLog JSON round-trip where request_id came back as a String.
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
        HashMap<String, Object> attrs = new HashMap<>();
        attrs.put("request_id", id.toString());
        attrs.put("router", "router");
        attrs.put("candidates", List.of("small", "big"));
        attrs.put("selected_model", "big");
        attrs.put("decision_source", "strategy");
        ModelRoutingEvent event = new ModelRoutingEvent(id, attrs);
        assertEquals(id, event.getRequestId());
        assertEquals("big", event.getSelectedModel());
    }

    @Test
    @SuppressWarnings("unchecked")
    void modelRoutingEventCarriesMetadata() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000cd");
        ModelRoutingEvent event =
                new ModelRoutingEvent(
                        id,
                        "router",
                        List.of("small", "big"),
                        "big",
                        "strategy",
                        true,
                        "matched sql",
                        0.9,
                        Map.of("signals", List.of("sql")),
                        1.5);
        assertTrue(event.isFallbackEnabled());
        // metadata survives a JSON-shaped round-trip and is deeply mutable (serialization-safe).
        ModelRoutingEvent restored =
                new ModelRoutingEvent(id, new HashMap<>(event.getAttributes()));
        assertEquals(List.of("sql"), restored.getMetadata().get("signals"));
        restored.getMetadata().put("copy_check", true);
        ((List<Object>) restored.getMetadata().get("signals")).add("probe");
        assertEquals(2, ((List<Object>) restored.getMetadata().get("signals")).size());
    }

    @Test
    void builderRequiresStrategy() {
        assertThrows(
                IllegalStateException.class,
                () -> ModelRouter.of("small", "big").defaultModel("small").build());
    }

    @Test
    void candidateRejectsEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> new RoutingCandidate(""));
    }

    @Test
    void decisionRejectsEmptyModel() {
        assertThrows(IllegalArgumentException.class, () -> RoutingDecision.of(""));
    }

    @Test
    void decisionJsonRoundTrips() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RoutingDecision original =
                RoutingDecision.builder("big").reason("matched code").score(0.82).build();
        String json = mapper.writeValueAsString(original);
        // wire shape is snake_case, matching the ModelRoutingEvent attributes
        assertTrue(json.contains("\"selected_model\""), json);
        RoutingDecision restored = mapper.readValue(json, RoutingDecision.class);
        assertEquals("big", restored.getSelectedModel());
        assertFalse(restored.isAbstain());
        assertEquals("matched code", restored.getReason());
        assertEquals(0.82, restored.getScore());
    }

    @Test
    void decisionRejectsInvalidStates() {
        // abstain must not carry a model; non-abstain must carry one — on every construction
        // path, including the JSON one used by durable replay.
        assertThrows(
                IllegalArgumentException.class,
                () -> new RoutingDecision("big", true, null, null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RoutingDecision(null, false, null, null, null, null));
    }

    @Test
    void decisionMsSurvivesJsonReplay() throws Exception {
        // decision_ms is stamped inside the durable call; a replayed (deserialized) decision
        // must report the original latency.
        ObjectMapper mapper = new ObjectMapper();
        RoutingDecision timed = RoutingDecision.of("big").withDecisionMs(12.5);
        RoutingDecision replayed =
                mapper.readValue(mapper.writeValueAsString(timed), RoutingDecision.class);
        assertEquals(12.5, replayed.getDecisionMs());
    }

    @Test
    void abstainJsonRoundTrips() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RoutingDecision restored =
                mapper.readValue(
                        mapper.writeValueAsString(RoutingDecision.abstain()),
                        RoutingDecision.class);
        assertTrue(restored.isAbstain());
        assertNull(restored.getSelectedModel());
    }

    @Test
    void builderRejectsRuleKeyThatIsNotACandidate() {
        // A typo'd rule key must fail at the registration call site, not per record at runtime.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.rules(Map.of("bg", "\\bsql\\b")))
                                .defaultModel("small")
                                .build());
    }

    @Test
    void ruleStrategyRejectsNullRuleValue() {
        Map<String, Object> rules = new HashMap<>();
        rules.put("big", null);
        // String.valueOf(null) would otherwise compile the literal pattern "null".
        assertThrows(
                IllegalArgumentException.class,
                () -> new RuleBasedRoutingStrategy(Map.of("rules", rules)));
    }

    @Test
    void ruleStrategyRejectsNonStringRuleValue() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RuleBasedRoutingStrategy(Map.of("rules", Map.of("big", 42))));
    }

    @Test
    void routingContextMessagesAreDeepCopied() {
        ChatMessage original = new ChatMessage(MessageRole.USER, "original prompt");
        RoutingContext ctx =
                new RoutingContext(
                        UUID.randomUUID(), "router", List.of(original), Map.of(), List.of());
        // A strategy mutating what it sees must not rewrite the message actually sent.
        ctx.getMessages().get(0).setContent("REWRITTEN BY STRATEGY");
        assertEquals("original prompt", original.getContent());
    }

    @Test
    void routingContextToolCallsAreDeepCopiedToo() {
        // ChatMessage's constructor stores toolCalls by reference; the context must copy them.
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        Map<String, Object> call = new HashMap<>();
        call.put("name", "originalTool");
        toolCalls.add(call);
        ChatMessage original = new ChatMessage(MessageRole.ASSISTANT, "", toolCalls);
        RoutingContext ctx =
                new RoutingContext(
                        UUID.randomUUID(), "router", List.of(original), Map.of(), List.of());
        ctx.getMessages().get(0).getToolCalls().get(0).put("name", "HIJACKED");
        ctx.getMessages().get(0).getToolCalls().clear();
        assertEquals(1, original.getToolCalls().size());
        assertEquals("originalTool", original.getToolCalls().get(0).get("name"));
    }
}
