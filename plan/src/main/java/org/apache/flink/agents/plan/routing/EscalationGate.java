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
package org.apache.flink.agents.plan.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.routing.Escalation;
import org.apache.flink.agents.api.chat.model.routing.QualityContext;
import org.apache.flink.agents.api.chat.model.routing.QualitySignal;
import org.apache.flink.agents.api.chat.model.routing.QualityVerdict;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.plan.actions.ChatModelAction;
import org.apache.flink.agents.plan.actions.ChatModelInvoker;
import org.apache.flink.agents.plan.actions.DecisionModelInvoker;
import org.apache.flink.agents.plan.resource.python.PythonChatModelSetup;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs an {@link Escalation} policy after one model round: the structural checks first, then the
 * declared signals in order. The first escalating verdict names the next tier; the caller discards
 * the round's answer before any of its tool calls is dispatched and re-sends the conversation to
 * that tier.
 *
 * <p>Structural checks are pure functions of the response and recomputed on replay. A
 * decision-model signal runs through {@link DecisionModelInvoker} under {@code
 * escalate:<router>:<candidate>}; a custom signal's verdict is persisted under {@code
 * escalate:<router>:<candidate>:<n>}. Both are reused on replay, so a recovered request reaches the
 * same tier without re-judging.
 */
public final class EscalationGate {

    private static final Logger LOG = LoggerFactory.getLogger(EscalationGate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SchemaRegistry SCHEMAS =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** Signal name recorded for the built-in checks. */
    public static final String STRUCTURAL = "structural";

    /** Question key used for the decision-model signal. */
    static final String QUESTION_KEY = "quality";

    static final String DEFAULT_INSTRUCTIONS =
            "Does the ANSWER fully and correctly satisfy the REQUEST, with no missing steps,"
                    + " refusals, or placeholders? Judge the answer, not the question.";

    private EscalationGate() {}

    /** The decision to escalate: where to, which signal said so, and why. */
    public static final class Outcome {
        public final String fromTier;
        public final String toTier;
        public final String signal;
        public final String reason;
        @Nullable public final Double score;

        Outcome(
                String fromTier,
                String toTier,
                String signal,
                String reason,
                @Nullable Double score) {
            this.fromTier = fromTier;
            this.toTier = toTier;
            this.signal = signal;
            this.reason = reason;
            this.score = score;
        }
    }

    /**
     * Decide whether the round produced by {@code candidate} should escalate.
     *
     * @return the escalation to perform, or empty to accept the round
     * @throws Exception a failing signal (resource lookup, provider error after retries, a custom
     *     signal throwing) fails the request; cancellation propagates
     */
    public static Optional<Outcome> check(
            Escalation policy,
            UUID requestId,
            String router,
            String candidate,
            int escalationsSoFar,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            ChatModelInvoker.ChatAttemptResult result,
            RunnerContext ctx)
            throws Exception {
        int tierIndex = policy.tierIndex(candidate);
        if (tierIndex < 0) {
            // A fallback candidate outside the tiers, or a model the policy does not know.
            return Optional.empty();
        }
        Optional<String> next = policy.nextTier(candidate);
        if (next.isEmpty() || escalationsSoFar >= policy.getMaxEscalations()) {
            // The last tier always accepts on quality; so does a request out of hops.
            return Optional.empty();
        }
        ChatResult response = Objects.requireNonNull(result.response, "response");
        // A Python-declared candidate binds its tools on the Python side; the Java setup never
        // opens them, so the tool checks cannot be applied to it.
        boolean toolsKnown =
                result.chatModel != null && !(result.chatModel instanceof PythonChatModelSetup);
        List<Tool> tools = toolsKnown ? safeTools(result.chatModel) : List.of();
        QualityContext context =
                new QualityContext(
                        requestId,
                        router,
                        candidate,
                        tierIndex,
                        policy.getTiers(),
                        messages,
                        promptArgs,
                        response,
                        tools);

        QualityVerdict structural = structural(context, toolsKnown);
        if (structural.isEscalate()) {
            return Optional.of(outcome(candidate, next.get(), STRUCTURAL, structural));
        }
        int index = 0;
        for (Escalation.Signal signal : policy.getSignals()) {
            QualityVerdict verdict;
            String name;
            if (Escalation.Signal.TYPE_DECISION_MODEL.equals(signal.getType())) {
                name = Escalation.Signal.TYPE_DECISION_MODEL + ":" + signal.decisionModel();
                verdict = decisionSignal(signal, context, router, candidate, ctx);
            } else {
                name = Escalation.Signal.TYPE_CUSTOM + ":" + signal.signalClass();
                verdict = customSignal(signal, index, context, router, candidate, ctx);
            }
            index++;
            if (verdict.isEscalate()) {
                return Optional.of(outcome(candidate, next.get(), name, verdict));
            }
        }
        return Optional.empty();
    }

    private static Outcome outcome(String from, String to, String signal, QualityVerdict verdict) {
        return new Outcome(from, to, signal, verdict.getReason(), verdict.getScore());
    }

    // ---------------------------------------------------------------- structural checks

    /**
     * The built-in checks, in order: an empty answer with no tool call; a call to a tool the
     * candidate does not have; arguments that fail the tool's input schema; the same tool call,
     * with identical arguments, as the previous assistant round. Pure functions of the response and
     * the conversation, so replay recomputes them.
     */
    static QualityVerdict structural(QualityContext context) {
        return structural(context, true);
    }

    /**
     * As {@link #structural(QualityContext)}; with {@code toolsKnown} false the two tool checks
     * (membership and schema) are skipped because the candidate's bound tools are not visible from
     * Java, which is the case for a Python-declared chat model.
     */
    static QualityVerdict structural(QualityContext context, boolean toolsKnown) {
        ChatResult response = context.getResponse();
        List<ToolCallBlock> calls = response.getToolCalls();
        String text = response.getText();
        if ((text == null || text.isBlank()) && calls.isEmpty()) {
            return QualityVerdict.escalate("empty response: no content and no tool call");
        }
        if (toolsKnown) {
            Map<String, Tool> byName = new LinkedHashMap<>();
            for (Tool tool : context.getTools()) {
                byName.put(tool.getName(), tool);
            }
            for (ToolCallBlock call : calls) {
                Tool tool = byName.get(call.getName());
                if (tool == null) {
                    return QualityVerdict.escalate(
                            "call to tool '"
                                    + call.getName()
                                    + "' that the candidate does not have");
                }
                String error = schemaError(tool, call);
                if (error != null) {
                    return QualityVerdict.escalate(
                            "arguments for tool '"
                                    + call.getName()
                                    + "' fail its schema: "
                                    + error);
                }
            }
        }
        if (!calls.isEmpty() && repeatsPreviousRound(context.getMessages(), calls)) {
            return QualityVerdict.escalate(
                    "tool call repeated with identical arguments in consecutive rounds");
        }
        return QualityVerdict.accept();
    }

    /**
     * The first validation error of {@code call}'s arguments against the tool's schema, or null.
     */
    @Nullable
    static String schemaError(Tool tool, ToolCallBlock call) {
        String schemaText = tool.getMetadata() == null ? null : tool.getMetadata().getInputSchema();
        if (schemaText == null || schemaText.isBlank()) {
            return null;
        }
        try {
            JsonNode schemaNode = MAPPER.readTree(schemaText);
            Schema schema = SCHEMAS.getSchema(schemaNode);
            JsonNode input =
                    MAPPER.valueToTree(call.getInput() == null ? Map.of() : call.getInput());
            List<com.networknt.schema.Error> errors = schema.validate(input);
            if (errors.isEmpty()) {
                return null;
            }
            return errors.stream().map(Object::toString).sorted().findFirst().orElse("invalid");
        } catch (Exception e) {
            // A schema the validator cannot read is not the candidate's fault.
            LOG.debug("Skipping argument validation for tool {}", call.getName(), e);
            return null;
        }
    }

    /** True when the previous assistant message made exactly these calls (name and arguments). */
    static boolean repeatsPreviousRound(List<ChatMessage> messages, List<ToolCallBlock> calls) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getRole() != MessageRole.ASSISTANT) {
                continue;
            }
            List<ToolCallBlock> previous = message.getToolCalls();
            if (previous == null || previous.isEmpty()) {
                return false;
            }
            return callSet(previous).equals(callSet(calls));
        }
        return false;
    }

    private static Set<String> callSet(List<ToolCallBlock> calls) {
        Set<String> set = new LinkedHashSet<>();
        for (ToolCallBlock call : calls) {
            try {
                set.add(call.getName() + "(" + MAPPER.writeValueAsString(call.getInput()) + ")");
            } catch (Exception e) {
                set.add(call.getName() + "(" + call.getInput() + ")");
            }
        }
        return set;
    }

    private static List<Tool> safeTools(BaseChatModelSetup setup) {
        try {
            List<Tool> tools = setup.getTools();
            return tools == null ? List.of() : tools;
        } catch (Exception e) {
            LOG.debug("Could not read the candidate's tools for structural checks", e);
            return List.of();
        }
    }

    // ---------------------------------------------------------------- decision-model signal

    private static QualityVerdict decisionSignal(
            Escalation.Signal signal,
            QualityContext context,
            String router,
            String candidate,
            RunnerContext ctx)
            throws Exception {
        String decisionModel = signal.decisionModel();
        BaseDecisionModelSetup setup;
        try {
            setup =
                    (BaseDecisionModelSetup)
                            ctx.getResource(decisionModel, ResourceType.DECISION_MODEL);
        } catch (Exception e) {
            CancellationUtils.rethrowIfCancellation(e);
            throw new ModelRoutingResolver.RoutingFailure(
                    "Escalation signal decision model '"
                            + decisionModel
                            + "' could not be resolved: "
                            + ChatModelInvoker.errorText(e));
        }
        String instructions =
                signal.instructions() == null ? DEFAULT_INSTRUCTIONS : signal.instructions();
        DecisionRequest request =
                new DecisionRequest(
                        renderForVerifier(context),
                        Map.of(QUESTION_KEY, DecisionRequest.Question.predicate(instructions)));
        DecisionModelInvoker.DecisionAttemptResult result =
                DecisionModelInvoker.decideWithRetries(
                        ctx,
                        decisionModel,
                        setup,
                        request,
                        Map.of(),
                        "escalate:" + router + ":" + candidate,
                        "escalation signal for router '" + router + "'",
                        false);
        // Retries count against the routed request, as the decision strategy's do: response-level
        // totals and the model_resource counters both come from this one call.
        ChatModelAction.recordAttemptRetryStats(
                ctx, context.getRequestId(), decisionModel, result.retryCount, result.totalWaitSec);
        if (!result.isSuccess()) {
            throw new ModelRoutingResolver.RoutingFailure(
                    "Escalation signal decision model '"
                            + decisionModel
                            + "' failed: "
                            + result.error);
        }
        DecisionResult.Answer answer =
                Objects.requireNonNull(result.response).getAnswers().get(QUESTION_KEY);
        if (!(answer instanceof DecisionResult.PredicateAnswer)) {
            // A refusal or an unexpected answer type cannot judge the round; accept it.
            LOG.debug(
                    "Decision model {} gave no predicate answer; accepting the round",
                    decisionModel);
            return QualityVerdict.accept();
        }
        double probability = ((DecisionResult.PredicateAnswer) answer).getProbabilityTrue();
        if (probability < signal.minProbability()) {
            return QualityVerdict.escalate(
                    String.format(
                            "decision model '%s' rated the answer %.3f, below min_probability %s",
                            decisionModel, probability, signal.minProbability()),
                    probability);
        }
        return new QualityVerdict(false, null, probability);
    }

    /** The verifier's input: the conversation as text, then the answer under judgment. */
    static String renderForVerifier(QualityContext context) {
        StringBuilder text = new StringBuilder("REQUEST:\n");
        for (DataContentBlock block : RoutingInput.renderConversation(context.getMessages())) {
            if (block instanceof TextBlock) {
                text.append(((TextBlock) block).getText());
            }
        }
        text.append("\n\nANSWER:\n");
        ChatResult response = context.getResponse();
        if (response.getText() != null && !response.getText().isBlank()) {
            text.append(response.getText());
        }
        for (ToolCallBlock call : response.getToolCalls()) {
            text.append("\nTool call ").append(callSet(List.of(call)).iterator().next());
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- custom signal

    private static QualityVerdict customSignal(
            Escalation.Signal signal,
            int index,
            QualityContext context,
            String router,
            String candidate,
            RunnerContext ctx)
            throws Exception {
        String id = "escalate:" + router + ":" + candidate + ":" + index;
        try {
            QualitySignal instance = instantiate(signal);
            return ctx.durableExecute(
                    new DurableCallable<QualityVerdict>() {
                        @Override
                        public String getId() {
                            return id;
                        }

                        @Override
                        public Class<QualityVerdict> getResultClass() {
                            return QualityVerdict.class;
                        }

                        @Override
                        public QualityVerdict call() throws Exception {
                            return Objects.requireNonNull(
                                    instance.evaluate(context, ctx),
                                    "QualitySignal returned a null verdict");
                        }
                    });
        } catch (Exception e) {
            CancellationUtils.rethrowIfCancellation(e);
            throw new ModelRoutingResolver.RoutingFailure(
                    "Escalation signal "
                            + signal.signalClass()
                            + " failed: "
                            + ChatModelInvoker.errorText(e));
        }
    }

    /**
     * Instantiates the declared class: a {@code Map} constructor with the args if present, else
     * no-arg.
     */
    static QualitySignal instantiate(Escalation.Signal signal) throws Exception {
        String className = signal.signalClass();
        Class<?> clazz;
        try {
            clazz = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            clazz = Class.forName(className);
        }
        if (!QualitySignal.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(
                    "Escalation signal class " + className + " does not implement QualitySignal.");
        }
        Map<String, Object> args = signal.signalArgs();
        try {
            return (QualitySignal) clazz.getConstructor(Map.class).newInstance(new HashMap<>(args));
        } catch (NoSuchMethodException noMapConstructor) {
            return (QualitySignal) clazz.getConstructor().newInstance();
        }
    }
}
