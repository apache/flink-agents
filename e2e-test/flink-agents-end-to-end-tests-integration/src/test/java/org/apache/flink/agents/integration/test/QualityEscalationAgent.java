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
package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.DecisionModelConnection;
import org.apache.flink.agents.api.annotation.DecisionModelSetup;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.routing.Escalation;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.QualityContext;
import org.apache.flink.agents.api.chat.model.routing.QualitySignal;
import org.apache.flink.agents.api.chat.model.routing.QualitySignals;
import org.apache.flink.agents.api.chat.model.routing.QualityVerdict;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.decision.BaseDecisionModelConnection;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * An agent whose chat requests go through a {@code MODEL_ROUTER} with two escalation tiers and no
 * routing strategy: every request starts on {@code small}, and a verifier decision model, a custom
 * signal and the built-in structural checks decide whether {@code big} has to answer instead. Both
 * candidates, the verifier and the tool are Java resources registered on this agent, so a real job
 * exercises registration, the gate after each model round, the tool round, the continuation on the
 * stronger tier and the final response, with no credentials and no Python bridge.
 *
 * <p>The scripted {@code small} connection answers from the request text: {@code nothing} returns
 * an empty message (structural escalation), {@code lookup} first calls the {@code lookup} tool and
 * then answers {@code weak} (custom-signal escalation on the tool round), anything else echoes the
 * request. The verifier scores an answer that mentions {@code SQL} or {@code big down} at 0.3
 * (below the 0.7 minimum) and everything else at 0.95. The {@code big} connection echoes the
 * request and any tool result it was handed, and throws for {@code big down}.
 */
public class QualityEscalationAgent extends Agent {

    private static final AtomicInteger VERIFIER_CALLS = new AtomicInteger();
    private static final List<String> VERIFIER_INPUTS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger TOOL_CALLS = new AtomicInteger();

    public static int verifierCalls() {
        return VERIFIER_CALLS.get();
    }

    public static List<String> verifierInputs() {
        return List.copyOf(VERIFIER_INPUTS);
    }

    public static int toolCalls() {
        return TOOL_CALLS.get();
    }

    public static void reset() {
        VERIFIER_CALLS.set(0);
        VERIFIER_INPUTS.clear();
        TOOL_CALLS.set(0);
    }

    public QualityEscalationAgent() {
        addResource(
                "router",
                ResourceType.MODEL_ROUTER,
                ModelRouter.of("small", "big")
                        .escalation(
                                Escalation.tiers("small", "big")
                                        .when(QualitySignals.decision("verifier", 0.7))
                                        .when(QualitySignals.custom(WeakAnswerSignal.class)))
                        .fallback(true)
                        .build());
    }

    /** Escalates the literal answer {@code weak}; a stand-in for a domain check. */
    public static class WeakAnswerSignal implements QualitySignal {
        @Override
        public QualityVerdict evaluate(QualityContext context, RunnerContext runnerContext) {
            return "weak".equals(context.getResponse().getText())
                    ? QualityVerdict.escalate("answer flagged weak")
                    : QualityVerdict.accept();
        }
    }

    private static String lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MessageRole.USER) {
                return messages.get(i).getText();
            }
        }
        return "";
    }

    private static String toolResults(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m.getRole() == MessageRole.TOOL)
                .flatMap(m -> m.getBlocks().stream())
                .filter(b -> b instanceof ToolResultBlock)
                .map(b -> ((ToolResultBlock) b).getText())
                .collect(Collectors.joining(","));
    }

    /** The scripted chat connection (see the class comment). */
    public static class ScriptedChatConnection extends BaseChatModelConnection {
        private final String tag;

        public ScriptedChatConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
            this.tag = descriptor.getArgument("tag");
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            String request = lastUserText(messages);
            String toolResults = toolResults(messages);
            if ("small".equals(tag)) {
                if (request.contains("nothing")) {
                    return new ChatResult(ChatMessage.assistant(List.of()));
                }
                if (request.contains("lookup")) {
                    if (toolResults.isEmpty()) {
                        return new ChatResult(
                                ChatMessage.assistant(
                                        List.of(
                                                new ToolCallBlock(
                                                        "call-1",
                                                        "lookup",
                                                        Map.of("q", request)))));
                    }
                    return new ChatResult(ChatMessage.assistant("weak"));
                }
                return new ChatResult(ChatMessage.assistant("small:" + request));
            }
            if (request.contains("big down")) {
                throw new IllegalStateException("big is down");
            }
            String suffix = toolResults.isEmpty() ? "" : "+tool=" + toolResults;
            return new ChatResult(ChatMessage.assistant("big:" + request + suffix));
        }
    }

    /** Chat model setup bound to a {@link ScriptedChatConnection}. */
    public static class ScriptedChatModel extends BaseChatModelSetup {
        public ScriptedChatModel(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    @ChatModelConnection
    public static ResourceDescriptor smallConnection() {
        return ResourceDescriptor.Builder.newBuilder(ScriptedChatConnection.class.getName())
                .addInitialArgument("tag", "small")
                .build();
    }

    @ChatModelConnection
    public static ResourceDescriptor bigConnection() {
        return ResourceDescriptor.Builder.newBuilder(ScriptedChatConnection.class.getName())
                .addInitialArgument("tag", "big")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor small() {
        return ResourceDescriptor.Builder.newBuilder(ScriptedChatModel.class.getName())
                .addInitialArgument("connection", "smallConnection")
                .addInitialArgument("model", "scripted-small")
                .addInitialArgument("tools", List.of("lookup"))
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor big() {
        return ResourceDescriptor.Builder.newBuilder(ScriptedChatModel.class.getName())
                .addInitialArgument("connection", "bigConnection")
                .addInitialArgument("model", "scripted-big")
                .build();
    }

    @org.apache.flink.agents.api.annotation.Tool(description = "Looks a value up")
    public static String lookup(@ToolParam(name = "q", description = "the query") String q) {
        TOOL_CALLS.incrementAndGet();
        return "42";
    }

    /** The scripted verifier (see the class comment). */
    public static class ScriptedVerifierConnection extends BaseDecisionModelConnection {
        public ScriptedVerifierConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public DecisionResult decide(
                DecisionRequest request, String model, Map<String, Object> requestParams) {
            VERIFIER_CALLS.incrementAndGet();
            String input = String.valueOf(request.getInput());
            VERIFIER_INPUTS.add(input);
            double probability = input.contains("SQL") || input.contains("big down") ? 0.3 : 0.95;
            String question = request.getQuestions().keySet().iterator().next();
            return new DecisionResult(
                    Map.of(question, new DecisionResult.PredicateAnswer(probability)),
                    model,
                    new DecisionResult.TokenUsage(5L, 1L, 6L),
                    Map.of());
        }
    }

    @DecisionModelConnection
    public static ResourceDescriptor verifierConnection() {
        return ResourceDescriptor.Builder.newBuilder(ScriptedVerifierConnection.class.getName())
                .build();
    }

    @DecisionModelSetup
    public static ResourceDescriptor verifier() {
        return ResourceDescriptor.Builder.newBuilder(BaseDecisionModelSetup.class.getName())
                .addInitialArgument("connection", "verifierConnection")
                .addInitialArgument("model", "scripted-verifier")
                .build();
    }

    @Action(EventType.InputEvent)
    public static void processInput(InputEvent event, RunnerContext ctx) {
        ctx.sendEvent(
                new ChatRequestEvent(
                        "router", List.of(ChatMessage.user((String) event.getInput()))));
    }

    @Action(EventType.ChatResponseEvent)
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
        if (event.isFailed()) {
            ctx.sendEvent(new OutputEvent("FAILED: " + event.getError()));
            return;
        }
        Map<?, ?> routing = (Map<?, ?>) event.getAttr("model_routing");
        List<?> hops = (List<?>) routing.get("escalations");
        String rendered =
                hops.stream()
                        .map(h -> (Map<?, ?>) h)
                        .map(h -> h.get("from") + ">" + h.get("to") + ":" + h.get("signal"))
                        .collect(Collectors.joining(",", "[", "]"));
        ctx.sendEvent(
                new OutputEvent(
                        String.format(
                                "final_model=%s decision_source=%s hops=%s :: %s",
                                routing.get("final_model"),
                                routing.get("decision_source"),
                                rendered,
                                event.getResponse().getText())));
    }
}
