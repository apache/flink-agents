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
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
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

/**
 * An agent whose chat requests go through a {@code MODEL_ROUTER} driven by a deterministic decision
 * model, with two mock candidate chat models that echo their own name. No credentials and no Python
 * bridge: everything the routed request touches is a Java resource registered on this agent, so the
 * test exercises registration, initialization, routing, candidate invocation and the final response
 * of a real job.
 *
 * <p>The decision connection scripts its answer from the request text: {@code SQL} picks {@code
 * big} at 0.9, {@code unsure} picks {@code big} at 0.55 (below the router's 0.6 minimum, so the
 * request abstains to the default model), {@code crash} throws (the retry budget is exhausted and
 * the request fails), and anything else picks {@code small} at 0.95.
 */
public class DecisionModelRoutingAgent extends Agent {

    // Shared with the test through accessors; the job runs in this JVM. The test resets them
    // before and after each run so no state leaks across tests in the same surefire fork.
    private static final AtomicInteger DECISION_CALLS = new AtomicInteger();
    private static final List<String> DECISION_INPUTS = new CopyOnWriteArrayList<>();

    /** Provider calls across the job, including retried attempts. */
    public static int decisionCalls() {
        return DECISION_CALLS.get();
    }

    /** The input text of every decision request, in call order. */
    public static List<String> decisionInputs() {
        return List.copyOf(DECISION_INPUTS);
    }

    public static void reset() {
        DECISION_CALLS.set(0);
        DECISION_INPUTS.clear();
    }

    public DecisionModelRoutingAgent() {
        addResource(
                "router",
                ResourceType.MODEL_ROUTER,
                ModelRouter.of("small", "big")
                        .describe("small", "greetings and short factual questions")
                        .describe("big", "code, SQL and multi-step reasoning")
                        .strategy(Strategies.decision("dm").withMinProbability(0.6))
                        .defaultModel("small")
                        .build());
    }

    /**
     * A chat connection that answers with its tag and the last message, so the test sees who ran.
     */
    public static class EchoChatConnection extends BaseChatModelConnection {
        private final String tag;

        public EchoChatConnection(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
            this.tag = descriptor.getArgument("tag");
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            ChatMessage last = messages.get(messages.size() - 1);
            return new ChatResult(ChatMessage.assistant(tag + ":" + last.getText()));
        }
    }

    /** Chat model setup bound to an {@link EchoChatConnection}. */
    public static class EchoChatModel extends BaseChatModelSetup {
        public EchoChatModel(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    @ChatModelConnection
    public static ResourceDescriptor smallConnection() {
        return ResourceDescriptor.Builder.newBuilder(EchoChatConnection.class.getName())
                .addInitialArgument("tag", "small")
                .build();
    }

    @ChatModelConnection
    public static ResourceDescriptor bigConnection() {
        return ResourceDescriptor.Builder.newBuilder(EchoChatConnection.class.getName())
                .addInitialArgument("tag", "big")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor small() {
        return ResourceDescriptor.Builder.newBuilder(EchoChatModel.class.getName())
                .addInitialArgument("connection", "smallConnection")
                .addInitialArgument("model", "echo-small")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor big() {
        return ResourceDescriptor.Builder.newBuilder(EchoChatModel.class.getName())
                .addInitialArgument("connection", "bigConnection")
                .addInitialArgument("model", "echo-big")
                .build();
    }

    /** The scripted decision provider (see the class comment). */
    public static class ScriptedDecisionConnection extends BaseDecisionModelConnection {
        public ScriptedDecisionConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public DecisionResult decide(
                DecisionRequest request, String model, Map<String, Object> requestParams) {
            DECISION_CALLS.incrementAndGet();
            String input = String.valueOf(request.getInput());
            DECISION_INPUTS.add(input);
            if (input.contains("crash")) {
                throw new IllegalStateException("decision provider unavailable");
            }
            String choice;
            Map<String, Double> probabilities;
            if (input.contains("SQL")) {
                choice = "big";
                probabilities = Map.of("small", 0.1, "big", 0.9);
            } else if (input.contains("unsure")) {
                choice = "big";
                probabilities = Map.of("small", 0.45, "big", 0.55);
            } else {
                choice = "small";
                probabilities = Map.of("small", 0.95, "big", 0.05);
            }
            return new DecisionResult(
                    Map.of("model", new DecisionResult.ChoiceAnswer(choice, probabilities, null)),
                    model,
                    new DecisionResult.TokenUsage(5L, 1L, 6L),
                    Map.of());
        }
    }

    @DecisionModelConnection
    public static ResourceDescriptor decisionConnection() {
        return ResourceDescriptor.Builder.newBuilder(ScriptedDecisionConnection.class.getName())
                .build();
    }

    @DecisionModelSetup
    public static ResourceDescriptor dm() {
        return ResourceDescriptor.Builder.newBuilder(BaseDecisionModelSetup.class.getName())
                .addInitialArgument("connection", "decisionConnection")
                .addInitialArgument("model", "scripted")
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
        Map<?, ?> metadata = (Map<?, ?>) routing.get("metadata");
        // decision_choice is recorded only when the strategy abstained on a choice.
        Object choice = metadata == null ? null : metadata.get("decision_choice");
        ctx.sendEvent(
                new OutputEvent(
                        String.format(
                                "final_model=%s decision_source=%s score=%s%s :: %s",
                                routing.get("final_model"),
                                routing.get("decision_source"),
                                routing.get("score"),
                                choice == null ? "" : " decision_choice=" + choice,
                                event.getResponse().getText())));
    }
}
