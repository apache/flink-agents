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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.routing.CustomRoutingExecutor;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryRef;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.memory.BaseLongTermMemory;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration tests for model routing inside {@link ChatModelAction}. */
public class ChatModelActionRoutingTest {

    /** An executor that returns a name that is not a candidate (to exercise the invalid path). */
    public static class SelectsUnknownStrategy implements CustomRoutingExecutor {
        public SelectsUnknownStrategy() {}

        @Override
        public RoutingDecision route(RoutingStrategy strategy, RoutingContext context) {
            return RoutingDecision.of("nonexistent");
        }
    }

    /**
     * A chat model returning scripted outcomes per call: a {@link ChatMessage} is returned, a
     * {@link RuntimeException} is thrown. When the script is exhausted, returns a default assistant
     * reply.
     */
    static class FakeChatModel extends BaseChatModelSetup {
        private final Deque<Object> outcomes = new ArrayDeque<>();

        FakeChatModel(Object... outcomes) {
            super(new ResourceDescriptor("fake", Map.of()), null);
            Collections.addAll(this.outcomes, outcomes);
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of();
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages,
                Map<String, Object> promptArgs,
                Map<String, Object> modelParams) {
            Object next = outcomes.isEmpty() ? null : outcomes.poll();
            if (next instanceof RuntimeException) {
                throw (RuntimeException) next;
            }
            if (next instanceof ChatMessage) {
                return (ChatMessage) next;
            }
            return new ChatMessage(MessageRole.ASSISTANT, "answer");
        }
    }

    static class FakeRunnerContext implements RunnerContext {
        final List<Event> sentEvents = new ArrayList<>();
        final List<String> resolvedChatModels = new ArrayList<>();
        final List<String> durableCallIds = new ArrayList<>();
        final Map<String, BaseChatModelSetup> models = new HashMap<>();
        final Set<String> unresolvable = new HashSet<>();
        private final ModelRouter router;
        private final MemoryObject sensoryMemory = new FakeMemoryObject(new HashMap<>());
        private final AgentConfiguration config = new AgentConfiguration(Map.of());

        FakeRunnerContext(ModelRouter router) {
            this.router = router;
        }

        FakeRunnerContext register(String name, BaseChatModelSetup model) {
            models.put(name, model);
            return this;
        }

        /** Marks a chat-model name whose resource lookup fails (e.g. a typo'd candidate). */
        FakeRunnerContext unresolvable(String name) {
            unresolvable.add(name);
            return this;
        }

        FakeRunnerContext withErrorHandling(Agent.ErrorHandlingStrategy strategy) {
            config.set(AgentExecutionOptions.ERROR_HANDLING_STRATEGY, strategy);
            return this;
        }

        FakeRunnerContext withRetryBudget(int maxRetries, int waitIntervalSec) {
            config.set(AgentExecutionOptions.MAX_RETRIES, maxRetries);
            config.set(AgentExecutionOptions.RETRY_WAIT_INTERVAL, waitIntervalSec);
            return this;
        }

        @Override
        public boolean hasResource(String name, ResourceType type) {
            return type == ResourceType.MODEL_ROUTER && "router".equals(name) && router != null;
        }

        @Override
        public Resource getResource(String name, ResourceType type) {
            if (type == ResourceType.MODEL_ROUTER) {
                return router;
            }
            if (type == ResourceType.CHAT_MODEL) {
                if (unresolvable.contains(name)) {
                    throw new IllegalArgumentException("resource not found: " + name);
                }
                resolvedChatModels.add(name);
                return models.getOrDefault(name, new FakeChatModel());
            }
            throw new IllegalArgumentException("unexpected resource " + name + " " + type);
        }

        @Override
        public void sendEvent(Event event) {
            sentEvents.add(event);
        }

        @Override
        public MemoryObject getSensoryMemory() {
            return sensoryMemory;
        }

        @Override
        public MemoryObject getShortTermMemory() {
            return null;
        }

        @Override
        public BaseLongTermMemory getLongTermMemory() {
            return null;
        }

        @Override
        public FlinkAgentsMetricGroup getAgentMetricGroup() {
            return null;
        }

        @Override
        public FlinkAgentsMetricGroup getActionMetricGroup() {
            return null;
        }

        @Override
        public ReadableConfiguration getConfig() {
            return config;
        }

        @Override
        public Map<String, Object> getActionConfig() {
            return Map.of();
        }

        @Override
        public Object getActionConfigValue(String key) {
            return null;
        }

        /** Seeded results by durable-call id: present means "stored from a previous run". */
        final Map<String, Object> durableStore = new HashMap<>();

        /** Seeds a stored durable result so the next lookup takes the replay path. */
        FakeRunnerContext seedDurable(String id, Object value) {
            durableStore.put(id, value);
            return this;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T durableExecute(DurableCallable<T> callable) throws Exception {
            durableCallIds.add(callable.getId());
            if (durableStore.containsKey(callable.getId())) {
                return (T) durableStore.get(callable.getId());
            }
            return callable.call();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T durableExecuteAsync(DurableCallable<T> callable) throws Exception {
            durableCallIds.add(callable.getId());
            if (durableStore.containsKey(callable.getId())) {
                return (T) durableStore.get(callable.getId());
            }
            return callable.call();
        }

        @Override
        public void close() {}

        ModelRoutingEvent routingEvent() {
            return sentEvents.stream()
                    .filter(e -> ModelRoutingEvent.EVENT_TYPE.equals(e.getType()))
                    .map(ModelRoutingEvent::fromEvent)
                    .findFirst()
                    .orElse(null);
        }

        long routingEventCount() {
            return sentEvents.stream()
                    .filter(e -> ModelRoutingEvent.EVENT_TYPE.equals(e.getType()))
                    .count();
        }

        ToolRequestEvent toolRequestEvent() {
            return sentEvents.stream()
                    .filter(e -> ToolRequestEvent.EVENT_TYPE.equals(e.getType()))
                    .map(ToolRequestEvent::fromEvent)
                    .findFirst()
                    .orElse(null);
        }

        ChatResponseEvent chatResponse() {
            return sentEvents.stream()
                    .filter(e -> ChatResponseEvent.EVENT_TYPE.equals(e.getType()))
                    .map(ChatResponseEvent::fromEvent)
                    .findFirst()
                    .orElse(null);
        }

        boolean hasChatResponse() {
            return chatResponse() != null;
        }
    }

    private static ModelRouter router() throws Exception {
        return new ModelRouter(
                ModelRouter.of("small", "big")
                        .strategy(Strategies.rules(Map.of("big", "\\b(code|sql)\\b")))
                        .defaultModel("small")
                        .build(),
                null);
    }

    @Test
    void routesMatchingRequestToBigAndRunsNormalChat() throws Exception {
        FakeRunnerContext ctx = new FakeRunnerContext(router());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "router", List.of(new ChatMessage(MessageRole.USER, "write some sql"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event).isNotNull();
        assertThat(event.getRouter()).isEqualTo("router");
        assertThat(event.getSelectedModel()).isEqualTo("big");
        assertThat(event.getDecisionSource()).isEqualTo(ModelRoutingEvent.SOURCE_STRATEGY);
        assertThat(event.getCandidates()).containsExactly("small", "big");
        // decision latency is stamped inside the durable route call
        assertThat(event.getDecisionMs()).isNotNull();
        assertThat(event.isFallbackEnabled()).isFalse();
        // the selected concrete model was invoked via the normal chat path
        assertThat(ctx.resolvedChatModels).containsExactly("big");
        assertThat(ctx.hasChatResponse()).isTrue();
    }

    @Test
    void abstainRoutesToDefaultModel() throws Exception {
        FakeRunnerContext ctx = new FakeRunnerContext(router());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "router", List.of(new ChatMessage(MessageRole.USER, "hello there"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event).isNotNull();
        assertThat(event.getSelectedModel()).isEqualTo("small");
        assertThat(event.getDecisionSource()).isEqualTo(ModelRoutingEvent.SOURCE_DEFAULT);
        assertThat(ctx.resolvedChatModels).containsExactly("small");
    }

    /**
     * Regression (review: the restructure dropped the only multi-turn rule test): rules match the
     * MOST RECENT user message, not the first — an early SQL turn must not pin later small talk to
     * the big model, and a late SQL turn must route big regardless of how the conversation began.
     */
    @Test
    void ruleMatchesLatestUserMessageNotFirst() throws Exception {
        FakeRunnerContext earlySqlLateChat = new FakeRunnerContext(router());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "router",
                        List.of(
                                new ChatMessage(MessageRole.USER, "please write some sql for me"),
                                new ChatMessage(MessageRole.ASSISTANT, "SELECT 1;"),
                                new ChatMessage(MessageRole.USER, "thanks, how is the weather?"))),
                earlySqlLateChat);
        assertThat(earlySqlLateChat.routingEvent().getSelectedModel()).isEqualTo("small");
        assertThat(earlySqlLateChat.routingEvent().getDecisionSource())
                .isEqualTo(ModelRoutingEvent.SOURCE_DEFAULT);
        assertThat(earlySqlLateChat.resolvedChatModels).containsExactly("small");

        FakeRunnerContext earlyChatLateSql = new FakeRunnerContext(router());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "router",
                        List.of(
                                new ChatMessage(MessageRole.USER, "hello there"),
                                new ChatMessage(MessageRole.ASSISTANT, "hi!"),
                                new ChatMessage(MessageRole.USER, "now write some sql"))),
                earlyChatLateSql);
        assertThat(earlyChatLateSql.routingEvent().getSelectedModel()).isEqualTo("big");
        assertThat(earlyChatLateSql.routingEvent().getDecisionSource())
                .isEqualTo(ModelRoutingEvent.SOURCE_STRATEGY);
    }

    @Test
    void invalidCandidateFailsClearly() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.custom(SelectsUnknownStrategy.class))
                                .defaultModel("small")
                                .build(),
                        null);
        FakeRunnerContext ctx = new FakeRunnerContext(router);
        assertThatThrownBy(
                        () ->
                                ChatModelAction.processChatRequestOrToolResponse(
                                        new ChatRequestEvent(
                                                "router",
                                                List.of(new ChatMessage(MessageRole.USER, "hi"))),
                                        ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-candidate");
    }

    @Test
    void abstainWithoutDefaultUsesFirstCandidate() throws Exception {
        // No default model configured; on abstain the router falls back to the first candidate.
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                                .build(),
                        null);
        FakeRunnerContext ctx = new FakeRunnerContext(router);
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "router", List.of(new ChatMessage(MessageRole.USER, "hello there"))),
                ctx);

        assertThat(ctx.routingEvent()).isNotNull();
        assertThat(ctx.routingEvent().getSelectedModel()).isEqualTo("small");
        assertThat(ctx.resolvedChatModels).containsExactly("small");
    }

    @Test
    void llmJudgeVerdictRoutesToNamedCandidate() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .describe("big", "code and sql")
                                .strategy(Strategies.llm("judge"))
                                .defaultModel("small")
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .register(
                                "judge",
                                new FakeChatModel(
                                        new ChatMessage(
                                                MessageRole.ASSISTANT, "{\"model\": \"big\"}")))
                        .register("big", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "router", List.of(new ChatMessage(MessageRole.USER, "write some sql"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event).isNotNull();
        assertThat(event.getSelectedModel()).isEqualTo("big");
        assertThat(event.getDecisionSource()).isEqualTo(ModelRoutingEvent.SOURCE_LLM_JUDGE);
        assertThat(event.getMetadata()).containsEntry("judge_model", "judge");
        // decision_source is consumer-visible as the event attribute and the top-level payload
        // key only — the nested metadata copy was dropped (it contradicted the top-level value
        // on the judge+fallback path)
        assertThat(event.getMetadata()).doesNotContainKey("decision_source");
        // judge call is durable under its own id; the decision persists under the route id
        assertThat(ctx.durableCallIds).contains("judge:router", "route:router");
        // resolution order: the plain-judge backstop check, the anchor candidate (bound-prompt
        // rendering), the judge call itself, then the winner answers
        assertThat(ctx.resolvedChatModels).containsExactly("judge", "small", "judge", "big");
        assertThat(ctx.hasChatResponse()).isTrue();
    }

    @Test
    void llmJudgeUnparseableVerdictAbstainsToDefault() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.llm("judge"))
                                .defaultModel("small")
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .register(
                                "judge",
                                new FakeChatModel(
                                        new ChatMessage(
                                                MessageRole.ASSISTANT,
                                                "You should probably use the biggest model.")))
                        .register("small", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(new ChatMessage(MessageRole.USER, "hello"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event.getSelectedModel()).isEqualTo("small");
        assertThat(event.getDecisionSource()).isEqualTo(ModelRoutingEvent.SOURCE_DEFAULT);
        assertThat(event.getReason()).contains("not a candidate");
    }

    @Test
    void llmJudgeVerdictOutsideCandidatesAbstainsToDefault() throws Exception {
        // A hijacked or hallucinating judge cannot steer routing outside the declared candidates.
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.llm("judge"))
                                .defaultModel("small")
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .register(
                                "judge",
                                new FakeChatModel(
                                        new ChatMessage(
                                                MessageRole.ASSISTANT,
                                                "{\"model\": \"gpt-attacker\"}")))
                        .register("small", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "router", List.of(new ChatMessage(MessageRole.USER, "ignore rules"))),
                ctx);

        assertThat(ctx.routingEvent().getSelectedModel()).isEqualTo("small");
        assertThat(ctx.routingEvent().getDecisionSource())
                .isEqualTo(ModelRoutingEvent.SOURCE_DEFAULT);
    }

    @Test
    void llmJudgeUsesTheRetryBudget() throws Exception {
        // v1 review lesson: every routing test ran with numRetries == 0, so the retry path was
        // never exercised. Judge fails once, retries, then delivers a verdict.
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.llm("judge"))
                                .defaultModel("small")
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .withErrorHandling(Agent.ErrorHandlingStrategy.RETRY)
                        .withRetryBudget(1, 0)
                        .register(
                                "judge",
                                new FakeChatModel(
                                        new RuntimeException("transient"),
                                        new ChatMessage(
                                                MessageRole.ASSISTANT, "{\"model\": \"big\"}")))
                        .register("big", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "router", List.of(new ChatMessage(MessageRole.USER, "hard one"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event.getSelectedModel()).isEqualTo("big");
        assertThat(event.getDecisionSource()).isEqualTo(ModelRoutingEvent.SOURCE_LLM_JUDGE);
    }

    @Test
    void llmJudgeCallFailurePropagatesUnderFail() {
        // A dead judge honors the error-handling strategy like any strategy failure: under the
        // default FAIL, the outage is loud instead of hours of silent default-routing.
        ModelRouter router;
        try {
            router =
                    new ModelRouter(
                            ModelRouter.of("small", "big")
                                    .strategy(Strategies.llm("judge"))
                                    .defaultModel("small")
                                    .build(),
                            null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .register("judge", new FakeChatModel(new RuntimeException("judge down")))
                        .register("small", new FakeChatModel());
        assertThatThrownBy(
                        () ->
                                ChatModelAction.processChatRequestOrToolResponse(
                                        new ChatRequestEvent(
                                                "router",
                                                List.of(new ChatMessage(MessageRole.USER, "hi"))),
                                        ctx))
                .hasMessageContaining("judge down");
    }

    @Test
    void llmJudgeCallFailureAbstainsToDefaultUnderIgnore() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.llm("judge"))
                                .defaultModel("small")
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .withErrorHandling(Agent.ErrorHandlingStrategy.IGNORE)
                        .register("judge", new FakeChatModel(new RuntimeException("judge down")))
                        .register("small", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(new ChatMessage(MessageRole.USER, "hello"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event.getSelectedModel()).isEqualTo("small");
        assertThat(event.getDecisionSource()).isEqualTo(ModelRoutingEvent.SOURCE_DEFAULT);
        assertThat(event.getReason()).contains("judge call failed");
        assertThat(ctx.hasChatResponse()).isTrue();
    }

    @Test
    void nonRouterModelPassesThroughUnchanged() throws Exception {
        FakeRunnerContext ctx = new FakeRunnerContext(null);
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "plainModel", List.of(new ChatMessage(MessageRole.USER, "write some sql"))),
                ctx);

        assertThat(ctx.routingEvent()).isNull();
        assertThat(ctx.resolvedChatModels).containsExactly("plainModel");
        assertThat(ctx.hasChatResponse()).isTrue();
    }

    @Test
    void routedRequestUsesRoutedDurableCallIds() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                                .defaultModel("small")
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .register("big", new FakeChatModel(ChatMessage.assistant("ok")));
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(ChatMessage.user("write sql"))), ctx);
        // the decision and the chat attempt are distinct durable calls with routed ids
        assertThat(ctx.durableCallIds).containsExactly("route:router", "chat:router:big");
    }

    @Test
    void retryBudgetRunsBeforeFallback() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                                .defaultModel("small")
                                .fallback(true)
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .withErrorHandling(Agent.ErrorHandlingStrategy.RETRY)
                        .withRetryBudget(1, 0)
                        .register(
                                "big",
                                new FakeChatModel(
                                        new RuntimeException("transient"),
                                        ChatMessage.assistant("recovered on retry")))
                        .register(
                                "small", new FakeChatModel(ChatMessage.assistant("small answer")));

        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(ChatMessage.user("write sql"))), ctx);

        // the selected model's retry budget is consumed BEFORE fallback: big's retry
        // succeeds and small is never resolved — the ordering the class javadoc guarantees
        assertThat(ctx.chatResponse().getResponse().getContent()).isEqualTo("recovered on retry");
        assertThat(ctx.resolvedChatModels).containsExactly("big");
        assertThat(ctx.routingEventCount()).isEqualTo(1L);
    }

    @Test
    void directModelKeepsLegacyDurableCallId() throws Exception {
        FakeRunnerContext ctx = new FakeRunnerContext(null);
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("plain", List.of(ChatMessage.user("hi"))), ctx);
        // a non-router request must keep the unchanged legacy durable chat-call id
        assertThat(ctx.durableCallIds).containsExactly("chat");
        assertThat(ctx.routingEvent()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToRemainingCandidateWhenSelectedModelFails() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                                .defaultModel("small")
                                .fallback(true)
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .register("big", new FakeChatModel(new RuntimeException("big is down")))
                        .register(
                                "small", new FakeChatModel(ChatMessage.assistant("ok from small")));

        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(ChatMessage.user("write sql"))), ctx);

        // routed to big; big failed; fell back to small in declaration order
        assertThat(ctx.resolvedChatModels).containsExactly("big", "small");
        // each stage has its own durable identity: the route decision, then one distinct
        // chat call per candidate (the format recovery depends on, changed once already)
        assertThat(ctx.durableCallIds)
                .containsExactly("route:router", "chat:router:big", "chat:router:small");
        ChatResponseEvent response = ctx.chatResponse();
        assertThat(response).isNotNull();
        assertThat(response.getResponse().getContent()).isEqualTo("ok from small");
        Map<String, Object> routing =
                (Map<String, Object>) response.getResponse().getExtraArgs().get("model_routing");
        assertThat(routing.get("final_model")).isEqualTo("small");
        assertThat(routing.get("decision_source")).isEqualTo(ModelRoutingEvent.SOURCE_FALLBACK);
        List<String> tried = (List<String>) routing.get("fallback_models_tried");
        assertThat(tried).containsExactly("small");

        // the fallback outcome is also in the event log: decision event + fallback event
        assertThat(ctx.routingEventCount()).isEqualTo(2L);
        ModelRoutingEvent fallbackEvent =
                ctx.sentEvents.stream()
                        .filter(e -> ModelRoutingEvent.EVENT_TYPE.equals(e.getType()))
                        .map(ModelRoutingEvent::fromEvent)
                        .filter(
                                e ->
                                        ModelRoutingEvent.SOURCE_FALLBACK.equals(
                                                e.getDecisionSource()))
                        .findFirst()
                        .orElse(null);
        assertThat(fallbackEvent).isNotNull();
        assertThat(fallbackEvent.getSelectedModel()).isEqualTo("small");
        assertThat(fallbackEvent.getReason()).contains("big");
        assertThat(fallbackEvent.isFallbackEnabled()).isTrue();
    }

    @Test
    void fallbackExhaustedRethrows() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                                .defaultModel("small")
                                .fallback(true)
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .register("big", new FakeChatModel(new RuntimeException("big-exploded")))
                        .register(
                                "small", new FakeChatModel(new RuntimeException("small-exploded")));

        // Distinct per-candidate markers: exhaustion must surface the LAST candidate's error
        // with the earlier candidate's error chained as suppressed, not discarded.
        assertThatThrownBy(
                        () ->
                                ChatModelAction.processChatRequestOrToolResponse(
                                        new ChatRequestEvent(
                                                "router", List.of(ChatMessage.user("write sql"))),
                                        ctx))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("small-exploded")
                .satisfies(
                        t ->
                                assertThat(t.getSuppressed())
                                        .anySatisfy(
                                                sup ->
                                                        assertThat(sup)
                                                                .hasMessageContaining(
                                                                        "big-exploded")));
        assertThat(ctx.resolvedChatModels).containsExactly("big", "small");
        assertThat(ctx.hasChatResponse()).isFalse();
    }

    @Test
    void unresolvableCandidateCountsAsFailedAttemptAndFallsBack() throws Exception {
        // "big" is selected by the rule but its resource lookup fails (typo'd candidate); the
        // failure must stay inside the fallback loop so "small" still answers.
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                                .defaultModel("small")
                                .fallback(true)
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .unresolvable("big")
                        .register("small", new FakeChatModel());

        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(ChatMessage.user("write sql"))), ctx);

        assertThat(ctx.hasChatResponse()).isTrue();
        assertThat(ctx.resolvedChatModels).containsExactly("small");
        // the fallback outcome is recorded as a second routing event
        long fallbackEvents =
                ctx.sentEvents.stream()
                        .filter(e -> e instanceof ModelRoutingEvent)
                        .map(e -> (ModelRoutingEvent) e)
                        .filter(
                                e ->
                                        ModelRoutingEvent.SOURCE_FALLBACK.equals(
                                                e.getDecisionSource()))
                        .count();
        assertThat(fallbackEvents).isEqualTo(1);
    }

    /** Executor that always throws; must be public for reflective construction. */
    public static class ExplodingStrategy implements CustomRoutingExecutor {
        private static final long serialVersionUID = 1L;

        public ExplodingStrategy(Map<String, Object> args) {}

        @Override
        public RoutingDecision route(RoutingStrategy strategy, RoutingContext context) {
            throw new IllegalStateException("strategy exploded");
        }
    }

    @Test
    void strategyFailureIsIgnoredUnderIgnorePolicy() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.custom(ExplodingStrategy.class))
                                .defaultModel("small")
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .withErrorHandling(Agent.ErrorHandlingStrategy.IGNORE)
                        .register("small", new FakeChatModel());

        // Under IGNORE a strategy failure drops the request instead of killing the job.
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(ChatMessage.user("hello"))), ctx);

        assertThat(ctx.hasChatResponse()).isFalse();
        assertThat(ctx.resolvedChatModels).isEmpty();
    }

    @Test
    void strategyFailurePropagatesUnderDefaultPolicy() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.custom(ExplodingStrategy.class))
                                .defaultModel("small")
                                .build(),
                        null);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router).register("small", new FakeChatModel());

        assertThatThrownBy(
                        () ->
                                ChatModelAction.processChatRequestOrToolResponse(
                                        new ChatRequestEvent(
                                                "router", List.of(ChatMessage.user("hello"))),
                                        ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strategy exploded");
    }

    @Test
    void routesOnceThenReusesSelectedModelAcrossToolRound() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                                .defaultModel("small")
                                .build(),
                        null);
        List<Map<String, Object>> toolCall =
                List.of(
                        Map.of(
                                "id",
                                "call-1",
                                "type",
                                "function",
                                "function",
                                Map.of("name", "lookup", "arguments", Map.of())));
        ChatMessage intermediate = ChatMessage.assistant("", toolCall);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .register(
                                "big",
                                new FakeChatModel(
                                        intermediate, ChatMessage.assistant("final answer")));

        // initial routed request -> big -> tool call
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(ChatMessage.user("write sql"))), ctx);
        assertThat(ctx.routingEventCount()).isEqualTo(1L);
        ToolRequestEvent toolRequest = ctx.toolRequestEvent();
        assertThat(toolRequest).isNotNull();

        // tool round: feed the tool response back
        ChatModelAction.processChatRequestOrToolResponse(
                new ToolResponseEvent(
                        toolRequest.getId(),
                        Map.of("call-1", ToolResponse.success("42")),
                        Map.of("call-1", true),
                        Map.of()),
                ctx);

        // routing ran exactly once; the concrete model "big" was reused with no re-routing
        assertThat(ctx.routingEventCount()).isEqualTo(1L);
        assertThat(ctx.resolvedChatModels).containsExactly("big", "big");
        assertThat(ctx.chatResponse()).isNotNull();
        assertThat(ctx.chatResponse().getResponse().getContent()).isEqualTo("final answer");

        // the routing metadata from the initial decision is carried onto the final response
        @SuppressWarnings("unchecked")
        Map<String, Object> routing =
                (Map<String, Object>)
                        ctx.chatResponse().getResponse().getExtraArgs().get("model_routing");
        assertThat(routing).isNotNull();
        assertThat(routing.get("router")).isEqualTo("router");
        assertThat(routing.get("final_model")).isEqualTo("big");
        assertThat(routing.get("decision_source")).isEqualTo(ModelRoutingEvent.SOURCE_STRATEGY);

        // the intermediate tool-call message (which lives in the conversation history for the
        // whole loop) is NOT stamped with observability metadata
        assertThat(intermediate.getExtraArgs()).doesNotContainKey("model_routing");

        // the parked metadata context was created for the loop and consumed by the final
        // response (no leak) — asserted strictly so this fails if the context is never used
        assertThat(ctx.getSensoryMemory().isExist("_ROUTING_METADATA_CONTEXT")).isTrue();
        Map<?, ?> parked =
                (Map<?, ?>) ctx.getSensoryMemory().get("_ROUTING_METADATA_CONTEXT").getValue();
        assertThat(parked).isEmpty();
    }

    @Test
    void routedLoopCleansParkedMetadataWhenToolRoundFailsUnderIgnore() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                                .defaultModel("small")
                                .build(),
                        null);
        List<Map<String, Object>> toolCall =
                List.of(
                        Map.of(
                                "id",
                                "call-1",
                                "type",
                                "function",
                                "function",
                                Map.of("name", "lookup", "arguments", Map.of())));
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .withErrorHandling(Agent.ErrorHandlingStrategy.IGNORE)
                        .register(
                                "big",
                                new FakeChatModel(
                                        ChatMessage.assistant("", toolCall),
                                        new RuntimeException("tool round exploded")));

        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(ChatMessage.user("write sql"))), ctx);
        ToolRequestEvent toolRequest = ctx.toolRequestEvent();
        assertThat(toolRequest).isNotNull();
        // the routed round parked its metadata for the loop
        Map<?, ?> parkedMidLoop =
                (Map<?, ?>) ctx.getSensoryMemory().get("_ROUTING_METADATA_CONTEXT").getValue();
        assertThat(parkedMidLoop).hasSize(1);

        // the tool round's chat call fails and IGNORE drops the request...
        ChatModelAction.processChatRequestOrToolResponse(
                new ToolResponseEvent(
                        toolRequest.getId(),
                        Map.of("call-1", ToolResponse.success("42")),
                        Map.of("call-1", true),
                        Map.of()),
                ctx);
        assertThat(ctx.chatResponse()).isNull();

        // ...and the abandoned loop's parked metadata was cleaned up, not leaked
        Map<?, ?> parkedAfter =
                (Map<?, ?>) ctx.getSensoryMemory().get("_ROUTING_METADATA_CONTEXT").getValue();
        assertThat(parkedAfter).isEmpty();
    }

    static class FakeMemoryObject implements MemoryObject {
        private final Map<String, Object> values;
        private final Object value;

        FakeMemoryObject(Map<String, Object> values) {
            this(values, null);
        }

        FakeMemoryObject(Map<String, Object> values, Object value) {
            this.values = values;
            this.value = value;
        }

        @Override
        public MemoryObject get(String path) {
            return new FakeMemoryObject(values, values.get(path));
        }

        @Override
        public MemoryObject get(MemoryRef ref) {
            return get(ref.getPath());
        }

        @Override
        public MemoryRef set(String path, Object value) {
            values.put(path, value);
            return null;
        }

        @Override
        public MemoryObject newObject(String path, boolean overwrite) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isExist(String path) {
            return values.containsKey(path);
        }

        @Override
        public List<String> getFieldNames() {
            return new ArrayList<>(values.keySet());
        }

        @Override
        public Map<String, Object> getFields() {
            return Collections.unmodifiableMap(values);
        }

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        public boolean isNestedObject() {
            return value == null;
        }
    }

    /** A chat model that records the messages it was invoked with. */
    static class RecordingChatModel extends FakeChatModel {
        List<ChatMessage> lastMessages;

        RecordingChatModel(Object... outcomes) {
            super(outcomes);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages,
                Map<String, Object> promptArgs,
                Map<String, Object> modelParams) {
            this.lastMessages = messages;
            return super.chat(messages, promptArgs, modelParams);
        }
    }

    /** A chat model whose setup binds a prompt template (visible via getPrompt()). */
    static class TemplateBoundChatModel extends FakeChatModel {
        private final org.apache.flink.agents.api.prompt.Prompt prompt;

        TemplateBoundChatModel(String template) {
            this.prompt = new org.apache.flink.agents.api.prompt.Prompt.LocalPrompt(template);
        }

        @Override
        public Object getPrompt() {
            return prompt;
        }
    }

    private static ModelRouter judgeRouter() throws Exception {
        return new ModelRouter(
                ModelRouter.of("small", "big")
                        .strategy(Strategies.llm("judge"))
                        .defaultModel("small")
                        .build(),
                null);
    }

    /**
     * Regression (review: Okio raises a bare InterruptedIOException("timeout") for ordinary HTTP
     * timeouts): a judge timeout under IGNORE must abstain to the default model — it is a normal
     * failure, not a cancellation — and must not leave the thread marked interrupted.
     */
    @Test
    void judgeInterruptedIOTimeoutAbstainsToDefaultUnderIgnore() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext(judgeRouter())
                        .withErrorHandling(Agent.ErrorHandlingStrategy.IGNORE)
                        .register(
                                "judge",
                                new FakeChatModel(
                                        new RuntimeException(
                                                new java.io.InterruptedIOException("timeout"))))
                        .register("small", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(new ChatMessage(MessageRole.USER, "hello"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event.getSelectedModel()).isEqualTo("small");
        assertThat(event.getDecisionSource()).isEqualTo(ModelRoutingEvent.SOURCE_DEFAULT);
        assertThat(event.getReason()).contains("judge call failed");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(ctx.hasChatResponse()).isTrue();
    }

    /**
     * Replay path (review: no test took it): a stored abstain resolves to the router's CURRENT
     * default — and the strategy is never re-invoked on replay.
     */
    @Test
    void storedAbstainReplaysToCurrentDefaultWithoutRerunningStrategy() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.custom(ExplodingStrategy.class))
                                .defaultModel("small")
                                .build(),
                        null);
        RoutingDecision storedAbstain =
                new RoutingDecision(null, true, "stored abstain", null, Map.of(), 12.5);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router).seedDurable("route:router", storedAbstain);

        // The seeded store short-circuits the strategy: the exploding executor never runs.
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(new ChatMessage(MessageRole.USER, "hi"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event.getSelectedModel()).isEqualTo("small");
        assertThat(event.getDecisionSource()).isEqualTo(ModelRoutingEvent.SOURCE_DEFAULT);
        // decision_ms survives replay: the original wall time is reported, not a recomputation
        assertThat(event.getDecisionMs()).isEqualTo(12.5);
        assertThat(ctx.resolvedChatModels).containsExactly("small");
    }

    /** Replay of a stored concrete decision routes identically with its original latency. */
    @Test
    void storedDecisionReplaysWithOriginalDecisionMs() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.custom(ExplodingStrategy.class))
                                .defaultModel("small")
                                .build(),
                        null);
        RoutingDecision stored =
                RoutingDecision.builder("big").reason("stored").build().withDecisionMs(42.0);
        FakeRunnerContext ctx = new FakeRunnerContext(router).seedDurable("route:router", stored);

        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(new ChatMessage(MessageRole.USER, "hi"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event.getSelectedModel()).isEqualTo("big");
        assertThat(event.getDecisionMs()).isEqualTo(42.0);
        assertThat(ctx.resolvedChatModels).containsExactly("big");
    }

    /**
     * Replay for a judge router (review: both replay tests seeded a CUSTOM strategy, which takes
     * the simple durable path). The judge path persists two records — the judge chat under {@code
     * judge:<router>} and the decision under {@code route:<router>} — and on recovery both replay:
     * the judge model is never re-invoked (it would throw here) and the original decision latency
     * is reported, not a recomputation.
     */
    @Test
    void judgeRouterReplayNeverReinvokesJudge() throws Exception {
        RoutingDecision stored =
                RoutingDecision.builder("big")
                        .reason("stored verdict")
                        .build()
                        .withDecisionMs(42.0);
        FakeRunnerContext ctx =
                new FakeRunnerContext(judgeRouter())
                        .register(
                                "judge",
                                new FakeChatModel(
                                        new RuntimeException("judge re-invoked on replay")))
                        .register("big", new FakeChatModel())
                        .seedDurable(
                                "judge:router",
                                new ChatMessage(MessageRole.ASSISTANT, "{\"model\": \"big\"}"))
                        .seedDurable("route:router", stored);
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(new ChatMessage(MessageRole.USER, "hard"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event.getSelectedModel()).isEqualTo("big");
        assertThat(event.getDecisionMs()).isEqualTo(42.0);
        assertThat(ctx.durableCallIds).contains("judge:router", "route:router");
        assertThat(ctx.hasChatResponse()).isTrue();
    }

    /**
     * Non-numeric token values in the judge reply's extraArgs stay out of the decision metadata —
     * same {@code instanceof Number} guard as the metrics reader of these keys (review).
     */
    @Test
    void judgeNonNumericTokenCountsAreDropped() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext(judgeRouter())
                        .register(
                                "judge",
                                new FakeChatModel(
                                        new ChatMessage(
                                                MessageRole.ASSISTANT,
                                                "{\"model\": \"big\"}",
                                                Map.of(
                                                        "promptTokens", "12",
                                                        "completionTokens", "3"))))
                        .register("big", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(new ChatMessage(MessageRole.USER, "hard"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event.getMetadata()).doesNotContainKey("judge_prompt_tokens");
        assertThat(event.getMetadata()).doesNotContainKey("judge_completion_tokens");
        assertThat(event.getSelectedModel()).isEqualTo("big");
    }

    /** Judge token accounting (review: both keys were never produced by any test). */
    @Test
    void judgeTokenCountsLandInDecisionMetadata() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext(judgeRouter())
                        .register(
                                "judge",
                                new FakeChatModel(
                                        new ChatMessage(
                                                MessageRole.ASSISTANT,
                                                "{\"model\": \"big\"}",
                                                Map.of("promptTokens", 12, "completionTokens", 3))))
                        .register("big", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(new ChatMessage(MessageRole.USER, "hard"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event.getMetadata()).containsEntry("judge_prompt_tokens", 12);
        assertThat(event.getMetadata()).containsEntry("judge_completion_tokens", 3);
    }

    /**
     * The judge routes on the complete message list (review: it previously saw only the last USER
     * message, while the selected model received the full conversation).
     */
    @Test
    void judgeSeesFullConversation() throws Exception {
        RecordingChatModel judge =
                new RecordingChatModel(
                        new ChatMessage(MessageRole.ASSISTANT, "{\"model\": \"big\"}"));
        FakeRunnerContext ctx =
                new FakeRunnerContext(judgeRouter())
                        .register("judge", judge)
                        .register("big", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "router",
                        List.of(
                                new ChatMessage(
                                        MessageRole.SYSTEM, "You review Java concurrency code"),
                                new ChatMessage(MessageRole.USER, "Focus on race conditions"),
                                new ChatMessage(MessageRole.USER, "synchronized void transfer()"))),
                ctx);

        assertThat(judge.lastMessages).hasSize(2);
        String judgeInput = judge.lastMessages.get(1).getContent();
        assertThat(judgeInput).contains("SYSTEM: You review Java concurrency code");
        assertThat(judgeInput).contains("USER: Focus on race conditions");
        assertThat(judgeInput).contains("USER: synchronized void transfer()");
    }

    /**
     * With a bound prompt on the anchor candidate, the judge sees the RENDERED request (review: raw
     * promptArgs lose the task, which lives in the template).
     */
    @Test
    void judgeSeesRenderedBoundPrompt() throws Exception {
        RecordingChatModel judge =
                new RecordingChatModel(
                        new ChatMessage(MessageRole.ASSISTANT, "{\"model\": \"big\"}"));
        FakeRunnerContext ctx =
                new FakeRunnerContext(judgeRouter())
                        .register(
                                "small",
                                new TemplateBoundChatModel(
                                        "Review this SQL for performance issues: {input}"))
                        .register("judge", judge)
                        .register("big", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "router",
                        List.of(new ChatMessage(MessageRole.USER, "")),
                        Map.of("input", "SELECT * FROM orders"),
                        null),
                ctx);

        String judgeInput = judge.lastMessages.get(1).getContent();
        assertThat(judgeInput)
                .contains("Review this SQL for performance issues: SELECT * FROM orders");
    }

    /**
     * The opt-in context cap keeps SYSTEM and the newest message, drops what does not fit, and
     * flags the truncation in the decision metadata.
     */
    @Test
    void maxContextCharsCapsJudgeInputAndFlagsTruncation() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .strategy(Strategies.llm("judge").withMaxContextChars(80))
                                .defaultModel("small")
                                .build(),
                        null);
        RecordingChatModel judge =
                new RecordingChatModel(
                        new ChatMessage(MessageRole.ASSISTANT, "{\"model\": \"big\"}"));
        String oldTurn = "x".repeat(500);
        FakeRunnerContext ctx =
                new FakeRunnerContext(router)
                        .register("judge", judge)
                        .register("big", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent(
                        "router",
                        List.of(
                                new ChatMessage(MessageRole.SYSTEM, "task framing"),
                                new ChatMessage(MessageRole.USER, oldTurn),
                                new ChatMessage(MessageRole.USER, "current question"))),
                ctx);

        String judgeInput = judge.lastMessages.get(1).getContent();
        assertThat(judgeInput).contains("SYSTEM: task framing");
        assertThat(judgeInput).contains("USER: current question");
        assertThat(judgeInput).doesNotContain(oldTurn);
        assertThat(ctx.routingEvent().getMetadata()).containsEntry("judge_context_truncated", true);
    }

    /**
     * Runtime backstop (review): plan-time validation only sees descriptor-carried bindings, so a
     * judge setup that binds a prompt at the INSTANCE level must still be caught at request time —
     * under IGNORE it degrades to the default model instead of silently corrupting every verdict.
     */
    @Test
    void llmJudgePromptBoundSetupAbstainsToDefaultUnderIgnore() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext(judgeRouter())
                        .withErrorHandling(Agent.ErrorHandlingStrategy.IGNORE)
                        .register("judge", new TemplateBoundChatModel("bound task prompt"))
                        .register("small", new FakeChatModel());
        ChatModelAction.processChatRequestOrToolResponse(
                new ChatRequestEvent("router", List.of(new ChatMessage(MessageRole.USER, "hi"))),
                ctx);

        ModelRoutingEvent event = ctx.routingEvent();
        assertThat(event.getSelectedModel()).isEqualTo("small");
        assertThat(event.getDecisionSource()).isEqualTo(ModelRoutingEvent.SOURCE_DEFAULT);
        assertThat(event.getReason()).contains("bound prompt");
        assertThat(ctx.hasChatResponse()).isTrue();
    }

    @Test
    void llmJudgeRejectsInstanceLevelPromptBoundJudgeSetupUnderFail() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext(judgeRouter())
                        .register("judge", new TemplateBoundChatModel("bound task prompt"))
                        .register("small", new FakeChatModel());
        assertThatThrownBy(
                        () ->
                                ChatModelAction.processChatRequestOrToolResponse(
                                        new ChatRequestEvent(
                                                "router",
                                                List.of(new ChatMessage(MessageRole.USER, "hi"))),
                                        ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bound prompt");
    }
}
