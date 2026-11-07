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
package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.model.routing.Escalation;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.QualityContext;
import org.apache.flink.agents.api.chat.model.routing.QualitySignal;
import org.apache.flink.agents.api.chat.model.routing.QualitySignals;
import org.apache.flink.agents.api.chat.model.routing.QualityVerdict;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.plan.ChatFixtures;
import org.apache.flink.agents.plan.actions.ChatModelActionRoutingTest.FakeChatModel;
import org.apache.flink.agents.plan.actions.ChatModelActionRoutingTest.FakeDecisionModel;
import org.apache.flink.agents.plan.actions.ChatModelActionRoutingTest.FakeRunnerContext;
import org.apache.flink.agents.plan.routing.EscalationGate;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Histogram;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Quality-triggered escalation through {@link ChatModelAction}: the gate runs after each model
 * round of a tiered request, a rejected round never dispatches its tools, the request sticks to the
 * stronger tier, and the hops are recorded on the response and as routing events.
 */
class ChatModelActionEscalationTest {

    /** A tool with a schema requiring {@code q}. */
    static final class LookupTool extends Tool {
        LookupTool() {
            super(
                    new ToolMetadata(
                            "lookup",
                            "Look something up.",
                            "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},"
                                    + "\"required\":[\"q\"]}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return ToolResponse.text("unused");
        }
    }

    /** A chat model stub that has the lookup tool. */
    static final class ToolingChatModel extends FakeChatModel {
        ToolingChatModel(Object... outcomes) {
            super(outcomes);
        }

        @Override
        public List<Tool> getTools() {
            return List.of(new LookupTool());
        }
    }

    /** Escalates an answer whose text is exactly "weak". */
    public static final class WeakAnswerSignal implements QualitySignal {
        @Override
        public QualityVerdict evaluate(QualityContext context) {
            return "weak".equals(context.getResponse().getText())
                    ? QualityVerdict.escalate("answer flagged weak")
                    : QualityVerdict.accept();
        }
    }

    private static final List<Map<String, Object>> LOOKUP_CALL =
            List.of(
                    Map.of(
                            "id",
                            "call-1",
                            "type",
                            "function",
                            "function",
                            Map.of("name", "lookup", "arguments", Map.of("q", "x"))));

    private static DecisionResult verdict(double probabilityTrue) {
        return new DecisionResult(
                Map.of("quality", new DecisionResult.PredicateAnswer(probabilityTrue)),
                "fake-verifier",
                new DecisionResult.TokenUsage(3L, 1L, 4L),
                Map.of());
    }

    private static ModelRouter tiered(Escalation escalation, boolean fallback) throws Exception {
        return new ModelRouter(
                ModelRouter.of("small", "big").escalation(escalation).fallback(fallback).build(),
                null);
    }

    private static ModelRouter decisionTiers() throws Exception {
        return tiered(
                Escalation.tiers("small", "big").when(QualitySignals.decision("dm", 0.7)), false);
    }

    private static void send(FakeRunnerContext ctx, String text) throws Exception {
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(ChatMessage.user(text))), ctx);
    }

    private static List<ModelRoutingEvent> routingEvents(FakeRunnerContext ctx) {
        return ctx.sentEvents.stream()
                .filter(e -> ModelRoutingEvent.EVENT_TYPE.equals(e.getType()))
                .map(ModelRoutingEvent::fromEvent)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> routing(ChatResponseEvent response) {
        return (Map<String, Object>) response.getAttr("model_routing");
    }

    @Test
    void lowVerifierProbabilityEscalatesToTheNextTierAndRecordsTheHop() throws Exception {
        FakeDecisionModel dm = new FakeDecisionModel(verdict(0.3));
        FakeRunnerContext ctx =
                new FakeRunnerContext(decisionTiers())
                        .registerDecision("dm", dm)
                        .register("small", new FakeChatModel(ChatFixtures.response("draft")))
                        .register("big", new FakeChatModel(ChatFixtures.response("final")));
        send(ctx, "write sql");

        ChatResponseEvent response = ctx.chatResponse();
        assertThat(response.isFailed()).isFalse();
        assertThat(response.getResponse().getText()).isEqualTo("final");
        Map<String, Object> routing = routing(response);
        assertThat(routing)
                .containsEntry("selected_model", "small")
                .containsEntry("final_model", "big")
                .containsEntry(
                        ModelRoutingEvent.DECISION_SOURCE_KEY, ModelRoutingEvent.SOURCE_ESCALATION)
                .containsEntry("escalation_attempted", true)
                .containsEntry("fallback_attempted", false);
        List<Map<String, Object>> hops = (List<Map<String, Object>>) routing.get("escalations");
        assertThat(hops).hasSize(1);
        assertThat(hops.get(0))
                .containsEntry("from", "small")
                .containsEntry("to", "big")
                .containsEntry("signal", "decision_model:dm")
                .containsEntry("score", 0.3);
        assertThat((String) hops.get(0).get("reason")).contains("below min_probability 0.7");
        // One verifier call, one escalation event after the initial decision event.
        assertThat(dm.callCount).isEqualTo(1);
        List<ModelRoutingEvent> events = routingEvents(ctx);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).getDecisionSource())
                .isEqualTo(ModelRoutingEvent.SOURCE_ESCALATION);
        assertThat(events.get(1).getSelectedModel()).isEqualTo("big");
        assertThat(events.get(1).getScore()).isEqualTo(0.3);
        assertThat(events.get(1).getMetadata()).containsEntry("escalated_from", "small");
        assertThat(ctx.durableCallIds)
                .containsExactly(
                        "route:router",
                        "chat:router:small",
                        "escalate:router:small",
                        "chat:router:big");
    }

    @Test
    void anAcceptedAnswerStaysOnItsTier() throws Exception {
        FakeDecisionModel dm = new FakeDecisionModel(verdict(0.95));
        FakeRunnerContext ctx =
                new FakeRunnerContext(decisionTiers())
                        .registerDecision("dm", dm)
                        .register("small", new FakeChatModel(ChatFixtures.response("fine")))
                        .register("big", new FakeChatModel(ChatFixtures.response("unused")));
        send(ctx, "hello");

        ChatResponseEvent response = ctx.chatResponse();
        assertThat(response.getResponse().getText()).isEqualTo("fine");
        assertThat(routing(response))
                .containsEntry("final_model", "small")
                .containsEntry("escalation_attempted", false)
                .containsEntry("escalated_tokens", 0L)
                .containsEntry(
                        ModelRoutingEvent.DECISION_SOURCE_KEY, ModelRoutingEvent.SOURCE_DEFAULT);
        assertThat(ctx.resolvedChatModels).doesNotContain("big");
        assertThat(routingEvents(ctx)).hasSize(1);
    }

    @Test
    void anEmptyAnswerEscalatesStructurallyWithoutCallingTheVerifier() throws Exception {
        FakeDecisionModel dm = new FakeDecisionModel(verdict(0.99));
        FakeRunnerContext ctx =
                new FakeRunnerContext(decisionTiers())
                        .registerDecision("dm", dm)
                        .register("small", new FakeChatModel(ChatFixtures.response("")))
                        .register("big", new FakeChatModel(ChatFixtures.response("final")));
        send(ctx, "hello");

        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        assertThat(dm.callCount).isZero();
        List<Map<String, Object>> hops =
                (List<Map<String, Object>>) routing(ctx.chatResponse()).get("escalations");
        assertThat(hops.get(0)).containsEntry("signal", "structural");
        assertThat((String) hops.get(0).get("reason")).contains("empty response");
    }

    @Test
    void aCallToAnUnknownToolEscalatesBeforeAnyToolIsDispatched() throws Exception {
        // small has no tools, so its call to "lookup" is structurally invalid.
        FakeRunnerContext ctx =
                new FakeRunnerContext(
                                tiered(
                                        Escalation.tiers("small", "big")
                                                .when(
                                                        QualitySignals.custom(
                                                                WeakAnswerSignal.class)),
                                        false))
                        .register(
                                "small", new FakeChatModel(ChatFixtures.response("", LOOKUP_CALL)))
                        .register("big", new FakeChatModel(ChatFixtures.response("final")));
        send(ctx, "find it");

        assertThat(ctx.toolRequestEvent()).isNull();
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        assertThat(
                        (String)
                                ((List<Map<String, Object>>)
                                                routing(ctx.chatResponse()).get("escalations"))
                                        .get(0)
                                        .get("reason"))
                .contains("'lookup'")
                .contains("does not have");
    }

    @Test
    void invalidToolArgumentsEscalateBeforeDispatch() throws Exception {
        List<Map<String, Object>> badCall =
                List.of(
                        Map.of(
                                "id",
                                "call-1",
                                "type",
                                "function",
                                "function",
                                Map.of("name", "lookup", "arguments", Map.of("query", "x"))));
        FakeRunnerContext ctx =
                new FakeRunnerContext(
                                tiered(
                                        Escalation.tiers("small", "big")
                                                .when(
                                                        QualitySignals.custom(
                                                                WeakAnswerSignal.class)),
                                        false))
                        .register("small", new ToolingChatModel(ChatFixtures.response("", badCall)))
                        .register("big", new FakeChatModel(ChatFixtures.response("final")));
        send(ctx, "find it");

        assertThat(ctx.toolRequestEvent()).isNull();
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        assertThat(
                        (String)
                                ((List<Map<String, Object>>)
                                                routing(ctx.chatResponse()).get("escalations"))
                                        .get(0)
                                        .get("reason"))
                .contains("fail its schema");
    }

    @Test
    void aToolRoundThatFailsASignalEscalatesAndTheRequestSticksToTheStrongerTier()
            throws Exception {
        // Round 1: small makes a valid tool call (structural passes, custom signal accepts), the
        // tool runs. Round 2: small answers "weak", the custom signal escalates, and big answers
        // the conversation so far, tool result included.
        ToolingChatModel small =
                new ToolingChatModel(
                        ChatFixtures.response("", LOOKUP_CALL), ChatFixtures.response("weak"));
        FakeChatModel big = new FakeChatModel(ChatFixtures.response("strong"));
        FakeRunnerContext ctx =
                new FakeRunnerContext(
                                tiered(
                                        Escalation.tiers("small", "big")
                                                .when(
                                                        QualitySignals.custom(
                                                                WeakAnswerSignal.class)),
                                        false))
                        .register("small", small)
                        .register("big", big);
        send(ctx, "find it");
        ToolRequestEvent toolRequest = ctx.toolRequestEvent();
        assertThat(toolRequest).isNotNull();
        assertThat(toolRequest.getModel()).isEqualTo("small");
        assertThat(ctx.hasChatResponse()).isFalse();

        ChatModelAction.processChatRequestOrToolResponse(
                new ToolResponseEvent(
                        toolRequest.getId(),
                        Map.of("call-1", ToolResponse.text("42")),
                        Map.of("call-1", true),
                        Map.of()),
                ctx);

        ChatResponseEvent response = ctx.chatResponse();
        assertThat(response.isFailed()).isFalse();
        assertThat(response.getResponse().getText()).isEqualTo("strong");
        Map<String, Object> routing = routing(response);
        assertThat(routing)
                .containsEntry("router", "router")
                .containsEntry("final_model", "big")
                .containsEntry("escalation_attempted", true)
                .containsEntry(
                        ModelRoutingEvent.DECISION_SOURCE_KEY, ModelRoutingEvent.SOURCE_ESCALATION);
        List<Map<String, Object>> hops = (List<Map<String, Object>>) routing.get("escalations");
        assertThat(hops).hasSize(1);
        assertThat(hops.get(0))
                .containsEntry("from", "small")
                .containsEntry("to", "big")
                .containsEntry("signal", "custom:" + WeakAnswerSignal.class.getName());
        // The tool round's own attempt keeps the plain "chat" id; the escalated attempt and the
        // custom verdict get their own ids.
        assertThat(ctx.durableCallIds)
                .containsExactly(
                        "route:router",
                        "chat:router:small",
                        "escalate:router:small:0",
                        "chat",
                        "escalate:router:small:0",
                        "chat:router:big");
        assertThat(routingEvents(ctx)).hasSize(2);
        assertThat(routingEvents(ctx).get(1).getDecisionSource())
                .isEqualTo(ModelRoutingEvent.SOURCE_ESCALATION);
    }

    @Test
    void theHopLimitAndTheLastTierStopEscalating() throws Exception {
        // Three tiers, one hop allowed: medium's weak answer is final.
        FakeDecisionModel dm = new FakeDecisionModel(verdict(0.1), verdict(0.1));
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "medium", "big")
                                .escalation(
                                        Escalation.tiers("small", "medium", "big")
                                                .when(QualitySignals.decision("dm", 0.7))
                                                .maxEscalations(1))
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .registerDecision("dm", dm)
                        .register("small", new FakeChatModel(ChatFixtures.response("s")))
                        .register("medium", new FakeChatModel(ChatFixtures.response("m")))
                        .register("big", new FakeChatModel(ChatFixtures.response("b")));
        send(ctx, "hello");

        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("m");
        assertThat(dm.callCount).isEqualTo(1);
        assertThat(ctx.resolvedChatModels).doesNotContain("big");

        // Without the cap, medium escalates too, and big (the last tier) is not judged.
        FakeDecisionModel dm2 = new FakeDecisionModel(verdict(0.1), verdict(0.1), verdict(0.1));
        ModelRouter uncapped =
                new ModelRouter(
                        ModelRouter.of("small", "medium", "big")
                                .escalation(
                                        Escalation.tiers("small", "medium", "big")
                                                .when(QualitySignals.decision("dm", 0.7)))
                                .build(),
                        null);
        FakeRunnerContext ctx2 =
                new FakeRunnerContext(uncapped)
                        .registerDecision("dm", dm2)
                        .register("small", new FakeChatModel(ChatFixtures.response("s")))
                        .register("medium", new FakeChatModel(ChatFixtures.response("m")))
                        .register("big", new FakeChatModel(ChatFixtures.response("b")));
        send(ctx2, "hello");
        assertThat(ctx2.chatResponse().getResponse().getText()).isEqualTo("b");
        assertThat(dm2.callCount).isEqualTo(2);
        assertThat((List<Map<String, Object>>) routing(ctx2.chatResponse()).get("escalations"))
                .hasSize(2);
    }

    @Test
    void whenTheLastTierFailsFallbackSkipsTheRejectedTierAndTheRequestFails() throws Exception {
        FakeDecisionModel dm = new FakeDecisionModel(verdict(0.2));
        FakeRunnerContext ctx =
                new FakeRunnerContext(
                                tiered(
                                        Escalation.tiers("small", "big")
                                                .when(QualitySignals.decision("dm", 0.7)),
                                        true))
                        .withRetryBudget(0, 0)
                        .registerDecision("dm", dm)
                        .register("small", new FakeChatModel(ChatFixtures.response("draft")))
                        .register("big", new FakeChatModel(new RuntimeException("big is down")));
        send(ctx, "write sql");

        ChatResponseEvent response = ctx.chatResponse();
        assertThat(response.isFailed()).isTrue();
        assertThat(response.getError()).contains("big is down");
        // small answered and was rejected, so fallback did not call it again.
        assertThat(ctx.resolvedChatModels).containsExactly("small", "big");
    }

    @Test
    void verifierRetriesCountAgainstTheRequest() throws Exception {
        FakeDecisionModel dm =
                new FakeDecisionModel(new RuntimeException("transient"), verdict(0.95));
        FakeRunnerContext ctx =
                new FakeRunnerContext(decisionTiers())
                        .withRetryBudget(1, 0)
                        .registerDecision("dm", dm)
                        .register("small", new FakeChatModel(ChatFixtures.response("fine")));
        send(ctx, "hello");

        ChatResponseEvent response = ctx.chatResponse();
        assertThat(response.isFailed()).isFalse();
        assertThat(response.getResponse().getText()).isEqualTo("fine");
        assertThat(response.getRetryCount()).isEqualTo(1);
        assertThat(dm.callCount).isEqualTo(2);
    }

    @Test
    void aVerifierOutOfRetriesFailsTheRequestAsAFailedResponse() throws Exception {
        FakeDecisionModel dm =
                new FakeDecisionModel(
                        new RuntimeException("verifier down"),
                        new RuntimeException("verifier down"));
        FakeRunnerContext ctx =
                new FakeRunnerContext(decisionTiers())
                        .withRetryBudget(1, 0)
                        .registerDecision("dm", dm)
                        .register("small", new FakeChatModel(ChatFixtures.response("draft")))
                        .register("big", new FakeChatModel(ChatFixtures.response("unused")));
        send(ctx, "write sql");

        ChatResponseEvent response = ctx.chatResponse();
        assertThat(response.isFailed()).isTrue();
        assertThat(response.getError())
                .contains("Escalation signal decision model 'dm' failed")
                .contains("verifier down");
        assertThat(dm.callCount).isEqualTo(2);
        assertThat(ctx.resolvedChatModels).containsExactly("small");
    }

    @Test
    void aThrowingCustomSignalFailsTheRequestAndClearsTheParkedMetadata() throws Exception {
        // The first round (a tool call) passes; the signal throws on the follow-up round, after
        // the routing block was parked for the tool loop.
        ToolingChatModel small =
                new ToolingChatModel(
                        ChatFixtures.response("", LOOKUP_CALL), ChatFixtures.response("boom"));
        FakeRunnerContext ctx =
                new FakeRunnerContext(
                                tiered(
                                        Escalation.tiers("small", "big")
                                                .when(QualitySignals.custom(ThrowOnText.class)),
                                        false))
                        .register("small", small)
                        .register("big", new FakeChatModel(ChatFixtures.response("unused")));
        send(ctx, "find it");
        ToolRequestEvent toolRequest = ctx.toolRequestEvent();
        assertThat(toolRequest).isNotNull();
        ChatModelAction.processChatRequestOrToolResponse(
                new ToolResponseEvent(
                        toolRequest.getId(),
                        Map.of("call-1", ToolResponse.text("42")),
                        Map.of("call-1", true),
                        Map.of()),
                ctx);

        ChatResponseEvent response = ctx.chatResponse();
        assertThat(response.isFailed()).isTrue();
        assertThat(response.getError())
                .contains("Escalation signal " + ThrowOnText.class.getName() + " failed")
                .contains("signal exploded");
        assertThat(ctx.resolvedChatModels).doesNotContain("big");
        // The block parked for the tool loop was consumed with the failure.
        Map<?, ?> parked =
                (Map<?, ?>) ctx.getSensoryMemory().get("_ROUTING_METADATA_CONTEXT").getValue();
        assertThat(parked).isEmpty();
    }

    /** Throws only when the answer has text, so a tool-call round passes. */
    public static final class ThrowOnText implements QualitySignal {
        @Override
        public QualityVerdict evaluate(QualityContext context) {
            if (!context.getResponse().getText().isEmpty()) {
                throw new IllegalStateException("signal exploded");
            }
            return QualityVerdict.accept();
        }
    }

    @Test
    void fallbackAfterAFailedEscalationTargetReportsFallbackAsTheSource() throws Exception {
        // small is rejected, medium (the target) is down, big answers through fallback.
        FakeDecisionModel dm = new FakeDecisionModel(verdict(0.1));
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "medium", "big")
                                .escalation(
                                        Escalation.tiers("small", "medium", "big")
                                                .when(QualitySignals.decision("dm", 0.7)))
                                .fallback(true)
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .withRetryBudget(0, 0)
                        .registerDecision("dm", dm)
                        .register("small", new FakeChatModel(ChatFixtures.response("s")))
                        .register("medium", new FakeChatModel(new RuntimeException("medium down")))
                        .register("big", new FakeChatModel(ChatFixtures.response("b")));
        send(ctx, "hello");

        ChatResponseEvent response = ctx.chatResponse();
        assertThat(response.isFailed()).isFalse();
        assertThat(response.getResponse().getText()).isEqualTo("b");
        Map<String, Object> routing = routing(response);
        assertThat(routing)
                .containsEntry("final_model", "big")
                .containsEntry(
                        ModelRoutingEvent.DECISION_SOURCE_KEY, ModelRoutingEvent.SOURCE_FALLBACK)
                .containsEntry("fallback_attempted", true)
                .containsEntry("escalation_attempted", true)
                .containsEntry("fallback_models_tried", List.of("big"));
        assertThat((List<Map<String, Object>>) routing.get("escalations")).hasSize(1);
        assertThat(ctx.resolvedChatModels).containsExactly("small", "medium", "big");
        List<ModelRoutingEvent> events = routingEvents(ctx);
        assertThat(events)
                .extracting(ModelRoutingEvent::getDecisionSource)
                .containsExactly(
                        ModelRoutingEvent.SOURCE_DEFAULT,
                        ModelRoutingEvent.SOURCE_ESCALATION,
                        ModelRoutingEvent.SOURCE_FALLBACK);
    }

    @Test
    void aStoredVerdictIsReplayedWithoutCallingTheVerifier() throws Exception {
        FakeDecisionModel dm = new FakeDecisionModel(verdict(0.99));
        FakeRunnerContext ctx =
                new FakeRunnerContext(decisionTiers())
                        .registerDecision("dm", dm)
                        .register("small", new FakeChatModel(ChatFixtures.response("draft")))
                        .register("big", new FakeChatModel(ChatFixtures.response("final")))
                        .seedDurable("escalate:router:small", verdict(0.2));
        send(ctx, "write sql");

        // The recorded low verdict wins: the provider is not called and the request escalates.
        assertThat(dm.callCount).isZero();
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");

        FakeRunnerContext custom =
                new FakeRunnerContext(
                                tiered(
                                        Escalation.tiers("small", "big")
                                                .when(
                                                        QualitySignals.custom(
                                                                WeakAnswerSignal.class)),
                                        false))
                        .register("small", new FakeChatModel(ChatFixtures.response("fine")))
                        .register("big", new FakeChatModel(ChatFixtures.response("final")))
                        .seedDurable(
                                "escalate:router:small:0",
                                new EscalationGate.SignalOutcome(
                                        QualityVerdict.escalate("stored verdict"), null));
        send(custom, "hello");
        assertThat(custom.chatResponse().getResponse().getText()).isEqualTo("final");
        assertThat(
                        (String)
                                ((List<Map<String, Object>>)
                                                routing(custom.chatResponse()).get("escalations"))
                                        .get(0)
                                        .get("reason"))
                .isEqualTo("stored verdict");
    }

    @Test
    void aRouterWithoutTiersIsUnchanged() throws Exception {
        ModelRouter plain =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(
                                        org.apache.flink.agents.api.chat.model.routing.Strategies
                                                .rules(Map.of("big", "\\bsql\\b")))
                                .defaultModel("small")
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(plain)
                        .register("small", new FakeChatModel(ChatFixtures.response("")));
        send(ctx, "hello");
        // An empty answer is not escalated when no policy is declared.
        ChatResponseEvent response = ctx.chatResponse();
        assertThat(response.isFailed()).isFalse();
        Map<String, Object> routing = routing(response);
        assertThat(routing).containsEntry("escalation_attempted", false);
        assertThat((List<?>) routing.get("escalations")).isEmpty();
        assertThat(ctx.durableCallIds).containsExactly("route:router", "chat:router:small");
    }

    @Test
    void escalationCountsTowardTheMetrics() throws Exception {
        FlinkAgentsMetricGroup actionMetricGroup = mock(FlinkAgentsMetricGroup.class);
        Counter escalationCount = mock(Counter.class);
        Counter escalatedTokens = mock(Counter.class);
        when(actionMetricGroup.getHistogram("routingDecisionLatencyMs"))
                .thenReturn(mock(Histogram.class));
        when(actionMetricGroup.getCounter("routingEscalationCount")).thenReturn(escalationCount);
        when(actionMetricGroup.getCounter("routingEscalatedTokens")).thenReturn(escalatedTokens);
        FakeDecisionModel dm = new FakeDecisionModel(verdict(0.1));
        FakeRunnerContext ctx =
                new FakeRunnerContext(decisionTiers())
                        .withActionMetricGroup(actionMetricGroup)
                        .registerDecision("dm", dm)
                        .register(
                                "small",
                                new FakeChatModel(
                                        ChatFixtures.response(
                                                "draft", Map.of("completionTokens", 7L))))
                        .register("big", new FakeChatModel(ChatFixtures.response("final")));
        send(ctx, "write sql");
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        verify(escalationCount).inc();
        verify(escalatedTokens).inc(7L);
        assertThat(routing(ctx.chatResponse())).containsEntry("escalated_tokens", 7L);
    }

    @Test
    void aDurableStorageFailureInACustomSignalEscapesTheChatAction() throws Exception {
        IOException storageError = new IOException("action-state backend unavailable");
        FakeRunnerContext ctx =
                new FakeRunnerContext(
                        tiered(
                                Escalation.tiers("small", "big")
                                        .when(QualitySignals.custom(WeakAnswerSignal.class)),
                                false)) {
                    @Override
                    public <T> T durableExecute(DurableCallable<T> callable) throws Exception {
                        if (callable.getId().startsWith("escalate:")) {
                            throw storageError;
                        }
                        return super.durableExecute(callable);
                    }
                };
        ctx.register("small", new FakeChatModel(ChatFixtures.response("fine")));
        // Not a failed response: the runtime must see the storage fault and recover the job.
        assertThatThrownBy(() -> send(ctx, "find it")).isSameAs(storageError);
        assertThat(ctx.hasChatResponse()).isFalse();
    }

    @Test
    void theVerifierSeesTheRenderedPromptTheCandidateReceived() throws Exception {
        FakeDecisionModel dm = new FakeDecisionModel(verdict(0.99));
        List<List<ChatMessage>> received = new ArrayList<>();
        FakeChatModel small =
                new FakeChatModel() {
                    @Override
                    public Object getPrompt() {
                        return new Prompt.LocalPrompt("Translate this into French: {input}");
                    }

                    @Override
                    public ChatResult chat(
                            List<ChatMessage> messages,
                            Map<String, Object> promptArgs,
                            Map<String, Object> modelParams) {
                        received.add(prepareRequestMessages(messages, promptArgs));
                        return ChatFixtures.response("Bonjour");
                    }
                };
        FakeRunnerContext ctx =
                new FakeRunnerContext(decisionTiers())
                        .registerDecision("dm", dm)
                        .register("small", small)
                        .register("big", new FakeChatModel(ChatFixtures.response("unused")));
        // The task arrives as prompt arguments only; the candidate's bound prompt renders it.
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(), Map.of("input", "Hello"), null), ctx);
        assertThat(received.get(0).get(0).getText()).isEqualTo("Translate this into French: Hello");
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("Bonjour");
        assertThat(String.valueOf(dm.requests.get(0).getInput()))
                .contains("REQUEST:\nUSER: Translate this into French: Hello")
                .contains("ANSWER:\nBonjour");
    }

    @Test
    void aToolCallRepeatedWithReorderedArgumentsEscalatesInTheToolRound() throws Exception {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("q", "x");
        first.put("limit", 5);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("limit", 5);
        reordered.put("q", "x");
        FakeRunnerContext ctx =
                new FakeRunnerContext(tiered(Escalation.tiers("small", "big"), false))
                        .register(
                                "small",
                                new ToolingChatModel(
                                        new ChatResult(
                                                ChatMessage.assistant(
                                                        List.of(
                                                                new ToolCallBlock(
                                                                        "call-1", "lookup",
                                                                        first)))),
                                        new ChatResult(
                                                ChatMessage.assistant(
                                                        List.of(
                                                                new ToolCallBlock(
                                                                        "call-2", "lookup",
                                                                        reordered))))))
                        .register("big", new FakeChatModel(ChatFixtures.response("final")));
        send(ctx, "find it");
        ToolRequestEvent tool = ctx.toolRequestEvent();
        ChatModelAction.processChatRequestOrToolResponse(
                new ToolResponseEvent(
                        tool.getId(),
                        Map.of("call-1", ToolResponse.text("42")),
                        Map.of("call-1", true),
                        Map.of()),
                ctx);
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops =
                (List<Map<String, Object>>) routing(ctx.chatResponse()).get("escalations");
        assertThat(hops).hasSize(1);
        assertThat(hops.get(0)).containsEntry("signal", "structural");
        assertThat((String) hops.get(0).get("reason"))
                .contains("repeated with identical arguments");
    }
}
