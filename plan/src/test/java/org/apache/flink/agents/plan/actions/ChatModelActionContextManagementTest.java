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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.contextmanagement.ContextManagementPolicy;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.model.ApproximateTokenEstimator;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.TokenEstimate;
import org.apache.flink.agents.api.chat.model.routing.Escalation;
import org.apache.flink.agents.api.chat.model.routing.QualityContext;
import org.apache.flink.agents.api.chat.model.routing.QualitySignal;
import org.apache.flink.agents.api.chat.model.routing.QualitySignals;
import org.apache.flink.agents.api.chat.model.routing.QualityVerdict;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.configuration.ConfigOption;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ContextManagementEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.TestResourceContexts;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.ChatFixtures;
import org.apache.flink.agents.plan.contextmanagement.ContextCompaction;
import org.apache.flink.agents.plan.contextmanagement.ContextManager;
import org.apache.flink.agents.plan.exceptions.UncapturableException;
import org.apache.flink.agents.plan.resource.python.PythonChatModelConnection;
import org.apache.flink.agents.plan.resource.python.PythonChatModelSetup;
import org.apache.flink.agents.plan.resource.python.PythonMCPPrompt;
import org.apache.flink.agents.plan.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.plan.routing.ModelRouter;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Context management through {@link ChatModelAction}: what the model is sent under a policy, that
 * the stored conversation stays complete, where the policy comes from, and what is reported.
 */
class ChatModelActionContextManagementTest {

    private static final String MODEL = "model";
    private static final String TOOL_CALL_CONTEXT = "_TOOL_CALL_CONTEXT";

    /** One report of a call made within the action. */
    private static final class Report {
        final String phase;
        final String entityType;
        final String entityName;
        final Map<String, Object> metadata;
        final Throwable error;
        final String problemCategory;

        Report(
                String phase,
                String entityType,
                String entityName,
                Map<String, Object> metadata,
                Throwable error,
                String problemCategory) {
            this.phase = phase;
            this.entityType = entityType;
            this.entityName = entityName;
            this.metadata = metadata;
            this.error = error;
            this.problemCategory = problemCategory;
        }
    }

    /** A runner context whose configuration the test sets, and which records what is reported. */
    private static final class Context extends ChatModelActionRoutingTest.FakeRunnerContext
            implements ExecutionReporter {
        private final AgentConfiguration config = new AgentConfiguration(new HashMap<>());
        final List<Report> reports = new ArrayList<>();

        /** What the durable calls returned when they were called, in order. */
        final List<Object> durableResults = new ArrayList<>();

        /** The chat model connections that can be resolved, by name. */
        final Map<String, Resource> connections = new HashMap<>();

        /** How many of the next lookups of a chat model fail. */
        int failingLookups;

        /** The names of the chat models that were looked up, in order. */
        final List<String> chatModelLookups = new ArrayList<>();

        Context() {
            this(null);
        }

        Context(ModelRouter router) {
            super(router);
        }

        <T> Context with(ConfigOption<T> option, T value) {
            config.set(option, value);
            return this;
        }

        @Override
        public ReadableConfiguration getConfig() {
            return config;
        }

        @Override
        public Resource getResource(String name, ResourceType type) {
            if (type == ResourceType.CHAT_MODEL) {
                chatModelLookups.add(name);
                if (failingLookups > 0) {
                    failingLookups--;
                    throw new IllegalStateException("the resource is not ready");
                }
            }
            if (type == ResourceType.CHAT_MODEL_CONNECTION && connections.containsKey(name)) {
                return connections.get(name);
            }
            return super.getResource(name, type);
        }

        @Override
        public <T> T durableExecute(DurableCallable<T> callable) throws Exception {
            return super.durableExecute(recording(callable));
        }

        @Override
        public <T> DurableFuture<T> durableExecuteAsync(DurableCallable<T> callable) {
            return super.durableExecuteAsync(recording(callable));
        }

        /** The callable, with its result added to {@link #durableResults} when it is called. */
        private <T> DurableCallable<T> recording(DurableCallable<T> callable) {
            return new DurableCallable<>() {
                @Override
                public String getId() {
                    return callable.getId();
                }

                @Override
                public Class<T> getResultClass() {
                    return callable.getResultClass();
                }

                @Override
                public T call() throws Exception {
                    T result = callable.call();
                    durableResults.add(result);
                    return result;
                }
            };
        }

        /** The recorded estimates, in order. */
        List<ContextManager.EstimateOutcome> estimates() {
            return durableResults.stream()
                    .filter(result -> result instanceof ContextManager.EstimateOutcome)
                    .map(result -> (ContextManager.EstimateOutcome) result)
                    .collect(Collectors.toList());
        }

        /** The reports about calls to {@code entityName}, in order. */
        List<Report> reportsAbout(String entityName) {
            return reports.stream()
                    .filter(report -> report.entityName.equals(entityName))
                    .collect(Collectors.toList());
        }

        @Override
        public void reportExecutionStarted(
                String executionId,
                String entityType,
                String entityName,
                Map<String, Object> entityMetadata) {
            reports.add(new Report("started", entityType, entityName, entityMetadata, null, null));
        }

        @Override
        public void reportExecutionSucceeded(
                String executionId,
                String entityType,
                String entityName,
                Map<String, Object> entityMetadata) {
            reports.add(
                    new Report("succeeded", entityType, entityName, entityMetadata, null, null));
        }

        @Override
        public void reportExecutionFailed(
                String executionId,
                String entityType,
                String entityName,
                Map<String, Object> entityMetadata,
                Throwable error,
                String problemCategory) {
            reports.add(
                    new Report(
                            "failed",
                            entityType,
                            entityName,
                            entityMetadata,
                            error,
                            problemCategory));
        }
    }

    /**
     * A chat model that records what it is sent: the requests of the tool-call loop, and the
     * tool-free requests a summarizer receives.
     */
    private static class ScriptedModel extends ChatModelActionRoutingTest.FakeChatModel {
        final List<List<ChatMessage>> requests = new ArrayList<>();
        final List<List<ChatMessage>> summaryRequests = new ArrayList<>();
        final List<String> tokenMetrics = new ArrayList<>();
        private final Deque<Object> summaries = new ArrayDeque<>();
        private int usageFactor;
        private long usageOffset;
        private Long fixedUsage;

        ScriptedModel(Object... outcomes) {
            super(outcomes);
        }

        /**
         * Scripts the summarizer. A String is written as the line of every round the request shows,
         * unless it is blank or has lines of its own, which are returned as they are. A ChatResult
         * is returned as it is, and an exception is thrown.
         */
        ScriptedModel summaries(Object... scripted) {
            Collections.addAll(summaries, scripted);
            return this;
        }

        /** Reports an input size of {@code factor} times the approximate estimate. */
        ScriptedModel reportingUsageTimes(int factor) {
            this.usageFactor = factor;
            return this;
        }

        /** Reports an input size of the approximate estimate plus {@code tokens}. */
        ScriptedModel reportingUsagePlus(long tokens) {
            this.usageOffset = tokens;
            return this;
        }

        /** Reports the same input size for every call, whatever the request holds. */
        ScriptedModel reportingUsageOf(long promptTokens) {
            this.fixedUsage = promptTokens;
            return this;
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages,
                Map<String, Object> promptArgs,
                Map<String, Object> modelParams) {
            requests.add(List.copyOf(messages));
            ChatResult result = super.chat(messages, promptArgs, modelParams);
            if (usageFactor == 0 && usageOffset == 0 && fixedUsage == null) {
                return result;
            }
            long prompt =
                    fixedUsage != null
                            ? fixedUsage
                            : Math.max(usageFactor, 1) * approximate(messages) + usageOffset;
            return new ChatResult(
                    result.getMessage(),
                    "provider-model",
                    null,
                    new TokenUsage(prompt, 1L),
                    result.getFinishReason(),
                    Map.of());
        }

        @Override
        public ChatResult chatWithoutTools(List<ChatMessage> messages) {
            summaryRequests.add(List.copyOf(messages));
            Object next = summaries.poll();
            if (next instanceof RuntimeException) {
                throw (RuntimeException) next;
            }
            if (next instanceof ChatResult) {
                return (ChatResult) next;
            }
            return ChatFixtures.response(
                    linesFor(messages.get(1).getText(), next == null ? "notes" : (String) next));
        }

        @Override
        public void recordTokenMetrics(
                FlinkAgentsMetricGroup metricGroup,
                String modelName,
                long promptTokens,
                long completionTokens) {
            tokenMetrics.add(modelName + " " + promptTokens + " " + completionTokens);
        }
    }

    /** A chat model that puts a system message into the list it is handed. */
    private static final class InsertingModel extends ScriptedModel {
        InsertingModel(Object... outcomes) {
            super(outcomes);
        }

        @Override
        public List<ChatMessage> prepareRequestMessages(
                List<ChatMessage> messages, Map<String, Object> promptArgs) {
            messages.add(0, ChatMessage.system("Answer briefly."));
            return messages;
        }

        // As a chat model does whose connection counts tokens: it counts the request it
        // prepares.
        @Override
        public TokenEstimate estimateInputTokens(
                List<ChatMessage> messages, Map<String, Object> promptArgs) {
            return TokenEstimate.exact(approximate(prepareRequestMessages(messages, promptArgs)));
        }
    }

    /** A chat model whose connection counts tokens itself. */
    private static final class ExactModel extends ScriptedModel {
        ExactModel(Object... outcomes) {
            super(outcomes);
        }

        @Override
        public TokenEstimate estimateInputTokens(
                List<ChatMessage> messages, Map<String, Object> promptArgs) {
            return TokenEstimate.exact(approximate(messages));
        }
    }

    /**
     * A chat model that counts half the tokens of the approximation. Its estimates are on that
     * scale, and so is the input size it reports.
     */
    private static final class HalfScaleModel extends ScriptedModel {
        HalfScaleModel(Object... outcomes) {
            super(outcomes);
        }

        @Override
        public TokenEstimate estimateInputTokens(
                List<ChatMessage> messages, Map<String, Object> promptArgs) {
            return TokenEstimate.approximate(approximate(messages) / 2);
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages,
                Map<String, Object> promptArgs,
                Map<String, Object> modelParams) {
            ChatResult result = super.chat(messages, promptArgs, modelParams);
            return new ChatResult(
                    result.getMessage(),
                    "provider-model",
                    null,
                    new TokenUsage(approximate(messages) / 2, 1L),
                    result.getFinishReason(),
                    Map.of());
        }
    }

    private static long approximate(List<ChatMessage> messages) {
        return ApproximateTokenEstimator.estimate(messages, List.of()).getTokens();
    }

    // ── Driving a request ──────────────────────────────────────────────────

    private static ChatRequestEvent request() {
        return new ChatRequestEvent(
                MODEL, List.of(ChatMessage.system("You are terse."), ChatMessage.user("go")));
    }

    /** A model reply that calls the lookup tool once. */
    private static ChatResult callTool(String callId) {
        return ChatFixtures.response(
                "",
                List.of(
                        Map.of(
                                "id",
                                callId,
                                "type",
                                "function",
                                "function",
                                Map.of("name", "lookup", "arguments", Map.of("q", callId)))));
    }

    /** A model reply that calls the lookup tool once and says {@code text} beside the call. */
    private static ChatResult callTool(String callId, String text) {
        return new ChatResult(
                ChatMessage.assistant(
                        List.of(
                                new TextBlock(text),
                                new ToolCallBlock(callId, "lookup", Map.of("q", callId)))));
    }

    private static void send(Context ctx, ChatRequestEvent event) throws Exception {
        ChatModelAction.processChatRequestOrToolResponse(event, ctx);
    }

    /** Answers the latest tool request with one result. */
    private static void toolResult(Context ctx, String callId, String text) throws Exception {
        List<ToolRequestEvent> toolRequests =
                ctx.sentEvents.stream()
                        .filter(e -> ToolRequestEvent.EVENT_TYPE.equals(e.getType()))
                        .map(ToolRequestEvent::fromEvent)
                        .collect(Collectors.toList());
        ToolRequestEvent latest = toolRequests.get(toolRequests.size() - 1);
        ChatModelAction.processChatRequestOrToolResponse(
                new ToolResponseEvent(
                        latest.getId(),
                        Map.of(callId, ToolResponse.text(text)),
                        Map.of(callId, true),
                        Map.of()),
                ctx);
    }

    private static List<ContextManagementEvent> events(Context ctx) {
        return ctx.sentEvents.stream()
                .filter(e -> ContextManagementEvent.EVENT_TYPE.equals(e.getType()))
                .map(ContextManagementEvent::fromEvent)
                .collect(Collectors.toList());
    }

    private static String toolText(ChatMessage message) {
        return ((ToolResultBlock) message.getBlocks().get(0)).getText();
    }

    @SuppressWarnings("unchecked")
    private static List<ChatMessage> stored(Context ctx, UUID requestId) throws Exception {
        Map<UUID, Object> contexts =
                (Map<UUID, Object>) ctx.getSensoryMemory().get(TOOL_CALL_CONTEXT).getValue();
        return (List<ChatMessage>) contexts.get(requestId);
    }

    /** What is kept for a request, at its own memory path; null when nothing is. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(Context ctx, UUID requestId) throws Exception {
        String path = ContextManager.STATE_KEY + "." + requestId;
        if (!ctx.getSensoryMemory().isExist(path)) {
            return null;
        }
        Map<String, Object> state =
                (Map<String, Object>) ctx.getSensoryMemory().get(path).getValue();
        return state.isEmpty() ? null : state;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(ContextManagementEvent event) {
        return (List<Map<String, Object>>) event.getDetails().get("results");
    }

    private static ChatMessage result(String callId, String text) {
        return ChatMessage.tool(new ToolResultBlock(callId, List.of(new TextBlock(text)), false));
    }

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    private static final Pattern ROUND_HEADING = Pattern.compile("(?m)^Round (\\d+):$");

    /**
     * The answer of a scripted summarizer to a request: {@code text} as the line of every round the
     * request shows, or {@code text} itself when it is blank or already has the line of a round.
     */
    private static String linesFor(String asked, String text) {
        if (text.isBlank() || text.contains("Round ")) {
            return text;
        }
        List<String> lines = new ArrayList<>();
        Matcher shown = ROUND_HEADING.matcher(asked);
        while (shown.find()) {
            lines.add("Round " + shown.group(1) + ": " + text);
        }
        return String.join("\n", lines);
    }

    /** The notes on rounds 1, 2 and so on: one line for each text. */
    private static String notes(String... texts) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            lines.add("Round " + (i + 1) + ": " + texts[i]);
        }
        return String.join("\n", lines);
    }

    /**
     * The text block a summary adds to the user message in front of the rounds it stands for.
     * {@code texts} are the lines of rounds 1 to {@code rounds}; one text stands for every round.
     */
    private static String summaryBlock(int rounds, String... texts) {
        String[] lines = texts;
        if (texts.length != rounds) {
            assertThat(texts).hasSize(1);
            lines = new String[rounds];
            java.util.Arrays.fill(lines, texts[0]);
        }
        return ContextCompaction.SUMMARY_SEPARATOR + ContextCompaction.summaryText(notes(lines));
    }

    // ── Off by default ─────────────────────────────────────────────────────

    @Test
    void withoutAPolicyTheModelIsSentTheWholeConversationAndNothingIsKeptOrReported()
            throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        Context ctx = new Context();
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 5000));
        toolResult(ctx, "c2", repeat("2", 5000));

        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        assertThat(model.requests).hasSize(3);
        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(toolText(model.requests.get(2).get(3))).isEqualTo(repeat("1", 5000));
        assertThat(toolText(model.requests.get(2).get(5))).isEqualTo(repeat("2", 5000));
        assertThat(events(ctx)).isEmpty();
        assertThat(ctx.getSensoryMemory().isExist(ContextManager.STATE_KEY)).isFalse();
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "chat");
    }

    @Test
    void aRequestWithoutARoundIsSentAsItIsWhateverThePolicy() throws Exception {
        ScriptedModel model = new ScriptedModel(ChatFixtures.response("first"), callTool("c1"));
        // A limit below the minimum: reading this policy fails.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 5);
        ctx.register(MODEL, model);

        // Only tool-call rounds are changed, so a request that has none is not looked at.
        send(ctx, request());
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("first");
        assertThat(model.requests.get(0)).isEqualTo(request().getMessages());
        assertThat(ctx.getSensoryMemory().isExist(ContextManager.STATE_KEY)).isFalse();

        // The policy is read once a request has a round. A job is never in this state: its
        // options are checked when it is submitted.
        send(ctx, request());
        assertThatThrownBy(() -> toolResult(ctx, "c1", "result"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context-management.tool-results.max-chars");
    }

    // ── Truncation ─────────────────────────────────────────────────────────

    @Test
    void anOversizedResultIsTruncatedForTheModelStoredInFullAndReportedOnce() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("a", 1000));

        // The second model call sees the result cut to the limit.
        String sent = toolText(model.requests.get(1).get(3));
        assertThat(sent).contains("[935 characters truncated]");
        assertThat(sent.length()).isLessThanOrEqualTo(100);
        // The stored conversation keeps the whole result.
        assertThat(toolText(stored(ctx, request.getId()).get(3))).isEqualTo(repeat("a", 1000));

        List<ContextManagementEvent> afterFirstRound = events(ctx);
        assertThat(afterFirstRound).hasSize(1);
        ContextManagementEvent event = afterFirstRound.get(0);
        assertThat(event.getRequestId()).isEqualTo(request.getId());
        assertThat(event.getModel()).isEqualTo(MODEL);
        assertThat(event.getStep()).isEqualTo(ContextManagementEvent.STEP_TRUNCATE);
        assertThat(event.getDetails()).containsEntry("max_chars", 100);
        assertThat(results(event))
                .containsExactly(
                        Map.of(
                                "round",
                                1,
                                "call_id",
                                "c1",
                                "tool",
                                "lookup",
                                "original_chars",
                                1000,
                                "sent_chars",
                                sent.length()));

        toolResult(ctx, "c2", "short");

        // The result is still sent truncated, and is not reported again.
        assertThat(toolText(model.requests.get(2).get(3))).isEqualTo(sent);
        assertThat(toolText(model.requests.get(2).get(5))).isEqualTo("short");
        assertThat(events(ctx)).hasSize(1);
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
    }

    @Test
    void roundsThatArriveWithTheRequestAreStoredInFull() throws Exception {
        ScriptedModel model = new ScriptedModel(callTool("c1"), ChatFixtures.response("final"));
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100);
        ctx.register(MODEL, model);
        List<ChatMessage> history =
                List.of(
                        ChatMessage.user("go"),
                        callTool("h1").getMessage(),
                        ChatMessage.tool(
                                new ToolResultBlock(
                                        "h1", List.of(new TextBlock(repeat("a", 1000))), false)));
        ChatRequestEvent request = new ChatRequestEvent(MODEL, history);

        send(ctx, request);

        // The first model call is sent the round cut to the limit, and the conversation that is
        // stored for the later calls holds the round as it arrived.
        assertThat(toolText(model.requests.get(0).get(2))).contains("[935 characters truncated]");
        assertThat(toolText(stored(ctx, request.getId()).get(2))).isEqualTo(repeat("a", 1000));

        toolResult(ctx, "c1", "short");
        assertThat(toolText(model.requests.get(1).get(2))).contains("[935 characters truncated]");
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
    }

    @Test
    void aChatModelIsHandedAListItMayChange() throws Exception {
        // A chat model of the user's own may change the list it is handed for a call that
        // follows a tool round, to take the instructions out of it for example. Without a
        // policy that list can be changed, and so can the one with the managed messages.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), ChatFixtures.response("final")) {
                    @Override
                    public ChatResult chat(
                            List<ChatMessage> messages,
                            Map<String, Object> promptArgs,
                            Map<String, Object> modelParams) {
                        ChatResult result = super.chat(messages, promptArgs, modelParams);
                        if (messages.size() > 2) {
                            messages.remove(0);
                        }
                        return result;
                    }
                };
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 500));

        // The call that follows the tool round is sent the managed messages, and answers.
        assertThat(toolText(model.requests.get(1).get(3))).contains("characters truncated");
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
    }

    // ── Masking ────────────────────────────────────────────────────────────

    @Test
    void theResultsOfOlderRoundsAreMaskedAndEachRoundIsReportedWhenItIsFirstMasked()
            throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                        callTool("c1"),
                        callTool("c2"),
                        callTool("c3"),
                        ChatFixtures.response("final"));
        Context ctx =
                new Context()
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 300));
        // One round: nothing to mask yet.
        assertThat(toolText(model.requests.get(1).get(3))).isEqualTo(repeat("1", 300));
        assertThat(events(ctx)).isEmpty();

        toolResult(ctx, "c2", repeat("2", 300));
        // Two rounds: the first result is a placeholder, the latest is sent as it is.
        List<ChatMessage> third = model.requests.get(2);
        assertThat(third).hasSize(6);
        assertThat(toolText(third.get(3)))
                .isEqualTo(
                        "[Tool result omitted by context management: lookup({\"q\":\"c1\"})"
                                + " returned 300 characters]");
        assertThat(third.get(2).getToolCalls().get(0).getCallId()).isEqualTo("c1");
        assertThat(toolText(third.get(5))).isEqualTo(repeat("2", 300));
        assertThat(events(ctx)).hasSize(1);
        ContextManagementEvent first = events(ctx).get(0);
        assertThat(first.getStep()).isEqualTo(ContextManagementEvent.STEP_MASK);
        assertThat(first.getDetails())
                .containsEntry("keep_last_rounds", 1)
                .containsEntry("step_rounds", 1)
                .containsEntry("rounds", 1);
        assertThat(results(first))
                .containsExactly(
                        Map.of(
                                "round",
                                1,
                                "call_id",
                                "c1",
                                "tool",
                                "lookup",
                                "omitted_chars",
                                300));
        // The stored conversation keeps both results.
        assertThat(toolText(stored(ctx, request.getId()).get(3))).isEqualTo(repeat("1", 300));

        toolResult(ctx, "c3", repeat("3", 300));
        List<ChatMessage> fourth = model.requests.get(3);
        assertThat(toolText(fourth.get(3))).startsWith("[Tool result omitted");
        assertThat(toolText(fourth.get(5))).startsWith("[Tool result omitted");
        assertThat(toolText(fourth.get(7))).isEqualTo(repeat("3", 300));
        // Only the round that is newly masked is reported.
        assertThat(events(ctx)).hasSize(2);
        assertThat(results(events(ctx).get(1)))
                .containsExactly(
                        Map.of(
                                "round",
                                2,
                                "call_id",
                                "c2",
                                "tool",
                                "lookup",
                                "omitted_chars",
                                300));

        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        // Masking adds no durable call.
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "chat", "chat");
    }

    @Test
    void maskingInStepsWaitsUntilTheStepIsFullAndReportsItsRoundsTogether() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                        callTool("c1"),
                        callTool("c2"),
                        callTool("c3"),
                        ChatFixtures.response("final"));
        Context ctx =
                new Context()
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                1)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS,
                                2);
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 300));
        toolResult(ctx, "c2", repeat("2", 300));
        // Two rounds, one eligible: the step of two is not full, nothing is masked.
        assertThat(toolText(model.requests.get(2).get(3))).isEqualTo(repeat("1", 300));
        assertThat(events(ctx)).isEmpty();

        toolResult(ctx, "c3", repeat("3", 300));
        // Three rounds, two eligible: both are masked in one step.
        List<ChatMessage> fourth = model.requests.get(3);
        assertThat(toolText(fourth.get(3))).startsWith("[Tool result omitted");
        assertThat(toolText(fourth.get(5))).startsWith("[Tool result omitted");
        assertThat(toolText(fourth.get(7))).isEqualTo(repeat("3", 300));
        assertThat(events(ctx)).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("step_rounds", 2)
                .containsEntry("rounds", 2);
        // Each result tells its round, counted from 1: a provider can give the calls of two
        // rounds the same id.
        assertThat(results(events(ctx).get(0)))
                .extracting(result -> result.get("round") + " " + result.get("call_id"))
                .containsExactly("1 c1", "2 c2");
    }

    @Test
    void roundsThatArriveWithTheRequestAreMaskedAndReportedOnTheFirstCall() throws Exception {
        ScriptedModel model = new ScriptedModel(ChatFixtures.response("final"));
        Context ctx =
                new Context()
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        List<ChatMessage> history =
                List.of(
                        ChatMessage.user("go"),
                        callTool("h1").getMessage(),
                        ChatMessage.tool(
                                new ToolResultBlock(
                                        "h1", List.of(new TextBlock(repeat("1", 300))), false)),
                        callTool("h2").getMessage(),
                        ChatMessage.tool(
                                new ToolResultBlock(
                                        "h2", List.of(new TextBlock(repeat("2", 300))), false)));

        send(ctx, new ChatRequestEvent(MODEL, history));

        assertThat(toolText(model.requests.get(0).get(2))).startsWith("[Tool result omitted");
        assertThat(toolText(model.requests.get(0).get(4))).isEqualTo(repeat("2", 300));
        assertThat(events(ctx)).hasSize(1);
        assertThat(results(events(ctx).get(0)))
                .extracting(result -> result.get("call_id"))
                .containsExactly("h1");
    }

    // ── Where the policy comes from ────────────────────────────────────────

    @Test
    void aPolicyOnTheRequestReplacesTheAgentsDefaultForEveryRoundOfTheRequest() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        // The agent masks all but the last round; this request switches that off.
        Context ctx =
                new Context()
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request =
                request().withContextManagement(ContextManagementPolicy.disabled());

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 300));
        // The request's policy is kept for its tool rounds.
        assertThat(state(ctx, request.getId())).containsEntry("policy", Map.of());
        toolResult(ctx, "c2", repeat("2", 300));

        assertThat(toolText(model.requests.get(2).get(3))).isEqualTo(repeat("1", 300));
        assertThat(events(ctx)).isEmpty();
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        // What was kept for the request is removed when it completes.
        assertThat(state(ctx, request.getId())).isNull();
    }

    @Test
    void aPolicyOnTheRequestAppliesWithoutAnyConfiguration() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        Context ctx = new Context();
        ctx.register(MODEL, model);
        ChatRequestEvent request =
                request()
                        .withContextManagement(
                                ContextManagementPolicy.builder().toolResultMaxChars(100).build());

        send(ctx, request);
        toolResult(ctx, "c1", repeat("a", 1000));
        toolResult(ctx, "c2", repeat("b", 1000));

        // Both tool rounds use the request's policy.
        assertThat(toolText(model.requests.get(1).get(3))).contains("characters truncated");
        assertThat(toolText(model.requests.get(2).get(5))).contains("characters truncated");
        assertThat(events(ctx)).hasSize(2);
        assertThat(state(ctx, request.getId())).isNull();
    }

    @Test
    void aRequestWithoutAPolicyUsesTheAgentsDefaultAndAnotherRequestCanDiffer() throws Exception {
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100);
        ScriptedModel model =
                new ScriptedModel(
                        callTool("a1"),
                        ChatFixtures.response("first done"),
                        callTool("b1"),
                        ChatFixtures.response("second done"));
        ctx.register(MODEL, model);

        // First request: no policy of its own, the configured limit of 100 applies.
        send(ctx, request());
        toolResult(ctx, "a1", repeat("a", 1000));
        assertThat(toolText(model.requests.get(1).get(3)).length()).isLessThanOrEqualTo(100);

        // Second request: its own limit of 500 applies instead.
        send(
                ctx,
                request()
                        .withContextManagement(
                                ContextManagementPolicy.builder().toolResultMaxChars(500).build()));
        toolResult(ctx, "b1", repeat("b", 1000));
        int sent = toolText(model.requests.get(3).get(3)).length();
        assertThat(sent).isGreaterThan(100).isLessThanOrEqualTo(500);
    }

    // ── Summarization ──────────────────────────────────────────────────────

    /** Budget of 1000 tokens, summarize above 800, keep the last round. */
    private static Context summarizing() {
        return new Context()
                .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1000)
                .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1);
    }

    /**
     * Three rounds of about 500 tokens each and a short one; returns the stored conversation after
     * them.
     */
    private static List<ChatMessage> fourRounds(ScriptedModel model, boolean approximate)
            throws Exception {
        model.summaries("Account c1 holds 10.", "Account c2 holds 20.");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();
        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));
        toolResult(ctx, "c3", repeat("3", 2000));
        // A short round: the input is estimated for the call that follows it, and it is under
        // the trigger, so the call is sent the messages that were estimated.
        toolResult(ctx, "c4", "4");
        // Both summaries were decided by an estimate of the expected kind.
        assertThat(events(ctx)).hasSize(2);
        for (ContextManagementEvent event : events(ctx)) {
            assertThat(event.getDetails()).containsEntry("estimate_approximate", approximate);
        }
        return new ArrayList<>(stored(ctx, request.getId()));
    }

    /** The numbers of the rounds each summarizer request shows. */
    private static List<List<String>> roundsAsked(ScriptedModel model) {
        List<List<String>> asked = new ArrayList<>();
        for (List<ChatMessage> request : model.summaryRequests) {
            List<String> numbers = new ArrayList<>();
            Matcher heading = ROUND_HEADING.matcher(request.get(1).getText());
            while (heading.find()) {
                numbers.add(heading.group(1));
            }
            asked.add(numbers);
        }
        return asked;
    }

    @Test
    void whatAChatModelDoesWithTheListItIsHandedStaysWithIt() throws Exception {
        ScriptedModel plain =
                new ScriptedModel(
                        callTool("c1"),
                        callTool("c2"),
                        callTool("c3"),
                        callTool("c4"),
                        callTool("c5"));
        ScriptedModel inserting =
                new InsertingModel(
                        callTool("c1"),
                        callTool("c2"),
                        callTool("c3"),
                        callTool("c4"),
                        callTool("c5"));
        List<ChatMessage> stored = fourRounds(plain, true);

        // The chat model's estimate and its request are prepared from lists of their own. The
        // message it puts into them reaches neither the stored conversation, whose rounds the
        // request is managed by, nor the messages the model is sent.
        assertThat(fourRounds(inserting, false)).isEqualTo(stored);
        assertThat(plain.requests).hasSize(5);
        assertThat(inserting.requests).isEqualTo(plain.requests);
        assertThat(roundsAsked(inserting))
                .isEqualTo(roundsAsked(plain))
                .isEqualTo(List.of(List.of("1"), List.of("2")));
        // The summarizer is shown it with the chat model's other instructions.
        assertThat(inserting.summaryRequests.get(0).get(1).getText())
                .contains("Answer briefly.\nYou are terse.");
    }

    @Test
    void aSummarizerIsHandedAListItMayChange() throws Exception {
        // A connection may take the instructions out of the list it is handed, to send them
        // apart from the messages.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3")) {
                    @Override
                    public ChatResult chatWithoutTools(List<ChatMessage> messages) {
                        ChatResult result = super.chatWithoutTools(messages);
                        messages.remove(0);
                        return result;
                    }
                };
        model.summaries("Account c1 holds 10.");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // The summary is written and used.
        assertThat(model.summaryRequests).hasSize(1);
        List<ChatMessage> third = model.requests.get(2);
        assertThat(third).hasSize(4);
        assertThat(third.get(1).getText()).endsWith(summaryBlock(1, "Account c1 holds 10."));
    }

    @Test
    void whenTheEstimateExceedsTheTriggerOlderRoundsAreReplacedByASummaryOfTheStoredRounds()
            throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                ChatFixtures.response("final"))
                        .summaries("Account c1 holds 10.", "Account c2 holds 20.");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        // One round of about 500 tokens: under the trigger, and the last round is always kept.
        toolResult(ctx, "c1", repeat("1", 2000));
        assertThat(model.summaryRequests).isEmpty();
        assertThat(model.requests.get(1)).hasSize(4);

        // Two rounds, about 1040 tokens: round 1 is folded into a summary before the model call.
        toolResult(ctx, "c2", repeat("2", 2000));
        assertThat(model.summaryRequests).hasSize(1);
        List<ChatMessage> summaryRequest = model.summaryRequests.get(0);
        assertThat(summaryRequest).hasSize(2);
        assertThat(summaryRequest.get(0).getRole()).isEqualTo(MessageRole.SYSTEM);
        String asked = summaryRequest.get(1).getText();
        // The summarizer reads the request and the stored round, in full.
        assertThat(asked)
                .startsWith(
                        "The agent's instructions, for context only:\nYou are terse.\n\n"
                                + "The request the agent is working on, for context only:\ngo\n\n"
                                + "Rounds to write notes for:\n")
                .contains("Round 1:\nlookup({\"q\":\"c1\"}) returned: ")
                .contains(repeat("1", 2000))
                .doesNotContain("Round 2:")
                .endsWith(
                        "\nWrite one line for each round above, at most 30 words each. Use only"
                                + " what the rounds above contain.");

        // The model is sent the summary in place of round 1, and round 2 as it is.
        List<ChatMessage> third = model.requests.get(2);
        assertThat(third).hasSize(4);
        assertThat(third.get(0).getText()).isEqualTo("You are terse.");
        assertThat(third.get(1).getRole()).isEqualTo(MessageRole.USER);
        assertThat(third.get(1).getBlocks()).hasSize(2);
        assertThat(((TextBlock) third.get(1).getBlocks().get(0)).getText()).isEqualTo("go");
        assertThat(((TextBlock) third.get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, "Account c1 holds 10."));
        assertThat(third.get(2).getToolCalls().get(0).getCallId()).isEqualTo("c2");
        assertThat(toolText(third.get(3))).isEqualTo(repeat("2", 2000));
        // The stored conversation still has both rounds, and by now the third call.
        List<ChatMessage> twoRounds = stored(ctx, request.getId()).subList(0, 6);
        assertThat(stored(ctx, request.getId())).hasSize(7);
        assertThat(toolText(twoRounds.get(3))).isEqualTo(repeat("1", 2000));

        assertThat(events(ctx)).hasSize(1);
        ContextManagementEvent event = events(ctx).get(0);
        assertThat(event.getStep()).isEqualTo(ContextManagementEvent.STEP_SUMMARIZE);
        assertThat(event.getModel()).isEqualTo(MODEL);
        assertThat(event.getDetails())
                .containsEntry("summarizer", MODEL)
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 1)
                .containsEntry("summary_chars", "Round 1: Account c1 holds 10.".length())
                .containsEntry("max_input_tokens", 1000)
                .containsEntry("estimate_approximate", true)
                .containsEntry("estimated_input_tokens_before", approximate(twoRounds))
                .containsEntry("estimated_input_tokens_after", approximate(third))
                .doesNotContainKey("error");
        assertThat((Long) event.getDetails().get("estimated_input_tokens_before"))
                .isGreaterThan(800L);

        // Three rounds: the summary and two rounds are over the trigger again, so round 2 is
        // folded into the notes that already cover round 1.
        toolResult(ctx, "c3", repeat("3", 2000));
        assertThat(model.summaryRequests).hasSize(2);
        assertThat(model.summaryRequests.get(1).get(1).getText())
                .doesNotContain("Account c1 holds 10.")
                .contains("Round 2:\n")
                .contains(repeat("2", 2000))
                .doesNotContain("Round 1:")
                .doesNotContain("Round 3:");
        List<ChatMessage> fourth = model.requests.get(3);
        assertThat(fourth).hasSize(4);
        assertThat(((TextBlock) fourth.get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(2, "Account c1 holds 10.", "Account c2 holds 20."));
        assertThat(fourth.get(2).getToolCalls().get(0).getCallId()).isEqualTo("c3");
        assertThat(events(ctx)).hasSize(2);
        assertThat(events(ctx).get(1).getDetails())
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 2);

        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        // The input is estimated for a call that has a round due for its line, which the first
        // two calls do not. The estimate is a durable call, and a summary is one of its own,
        // made before the round's model call.
        assertThat(ctx.durableCallIds)
                .containsExactly(
                        "chat",
                        "chat",
                        "estimate",
                        "summarize",
                        "chat",
                        "estimate",
                        "summarize",
                        "chat");
        assertThat(state(ctx, request.getId())).isNull();
    }

    @Test
    void theSummarizerIsToldEveryUserMessageOfTheRequest() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), ChatFixtures.response("final"))
                        .summaries("notes on the earlier turn");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // A conversation that already has a turn with a tool round, and a new question after it.
        List<ChatMessage> history =
                List.of(
                        ChatMessage.user("first question"),
                        callTool("h1").getMessage(),
                        ChatMessage.tool(
                                new ToolResultBlock(
                                        "h1", List.of(new TextBlock(repeat("1", 2000))), false)),
                        ChatMessage.assistant("first answer"),
                        ChatMessage.user("second question"));

        send(ctx, new ChatRequestEvent(MODEL, history));
        // One round so far, and it is the kept one.
        assertThat(model.summaryRequests).isEmpty();
        toolResult(ctx, "c1", repeat("2", 2000));

        // The notes have to serve the latest question, so the summarizer is told both.
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .startsWith(
                        "The request the agent is working on, for context only:\nfirst question\nsecond question\n\n"
                                + "Rounds to write notes for:\nRound 1:\n");
        // The summary takes the place of the earlier round, in front of the turns that followed.
        List<ChatMessage> sent = model.requests.get(1);
        assertThat(sent.stream().map(ChatMessage::getRole).collect(Collectors.toList()))
                .containsExactly(
                        MessageRole.USER,
                        MessageRole.ASSISTANT,
                        MessageRole.USER,
                        MessageRole.ASSISTANT,
                        MessageRole.TOOL);
        assertThat(sent.get(0).getText())
                .isEqualTo("first question" + summaryBlock(1, "notes on the earlier turn"));
        assertThat(sent.get(2).getText()).isEqualTo("second question");
    }

    @Test
    void theSummarizerIsShownTheInstructionsOfTheBoundPrompt() throws Exception {
        // The instructions of this chat model are in its prompt, not in the request's messages.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final")) {
                    @Override
                    public Object getPrompt() {
                        return Prompt.fromMessages(
                                List.of(
                                        ChatMessage.system(
                                                "Audit the accounts of {region}. Reply with"
                                                        + " the total.")));
                    }
                };
        model.summaries("notes");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(
                ctx,
                new ChatRequestEvent(
                        MODEL, List.of(ChatMessage.user("go")), Map.of("region", "EMEA"), null));
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // Without them a summarizer cannot tell what the request still needs.
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .startsWith(
                        "The agent's instructions, for context only:\n"
                                + "Audit the accounts of EMEA. Reply with the total.\n\n"
                                + "The request the agent is working on, for context only:\ngo\n\n"
                                + "Rounds to write notes for:\nRound 1:\n");
    }

    @Test
    void longInstructionsInAnyScriptLeaveRoomForTheRounds() throws Exception {
        // 2000 characters of instructions that are a token each, as many tokens as the whole
        // budget.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final")) {
                    @Override
                    public Object getPrompt() {
                        return Prompt.fromMessages(
                                List.of(ChatMessage.system(repeat("\u5e10", 2000))));
                    }
                };
        model.summaries("notes on round 1");
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 2000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO,
                                0.1)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);

        send(ctx, new ChatRequestEvent(MODEL, List.of(ChatMessage.user("go"))));
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // The summarizer is shown an eighth of the budget of them, 250 tokens, counted in
        // tokens and not in characters, and the round fits beside them.
        assertThat(model.summaryRequests).hasSize(1);
        String asked = model.summaryRequests.get(0).get(1).getText();
        assertThat(asked.chars().filter(c -> c == '\u5e10').count()).isEqualTo(241);
        assertThat(asked).contains("[1759 characters truncated]").contains(repeat("1", 2000));
        assertThat(approximate(model.summaryRequests.get(0))).isLessThanOrEqualTo(2000);
        assertThat(events(ctx).get(0).getDetails()).doesNotContainKey("error");
    }

    @Test
    void theConfiguredSummarizerWritesTheSummary() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        ScriptedModel summarizer = new ScriptedModel().summaries("notes from the small model");
        Context ctx =
                summarizing()
                        .with(
                                AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL,
                                "small");
        ctx.register(MODEL, model);
        ctx.register("small", summarizer);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(summarizer.summaryRequests).hasSize(1);
        assertThat(model.summaryRequests).isEmpty();
        assertThat(((TextBlock) model.requests.get(2).get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, "notes from the small model"));
        assertThat(events(ctx).get(0).getDetails()).containsEntry("summarizer", "small");
    }

    @Test
    void aSummaryThatCannotBeWrittenIsSkippedReportedAndTriedAgainOnTheNextRound()
            throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                ChatFixtures.response("final"))
                        .summaries(
                                new IllegalStateException("summarizer is down"),
                                "notes on round 1",
                                "notes on round 2");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // The request goes on with the whole conversation.
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(ctx.hasChatResponse()).isFalse();
        assertThat(events(ctx)).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 0)
                .containsEntry("summarized_rounds", 0)
                .doesNotContainKey("summary_chars")
                .doesNotContainKey("estimated_input_tokens_after");
        assertThat((String) events(ctx).get(0).getDetails().get("error"))
                .contains("summarizer is down");

        // The next round asks again, for both rounds that are now due. Together they do not fit
        // the budget of one summarizer request, so they are folded one after the other.
        toolResult(ctx, "c3", repeat("3", 2000));
        assertThat(model.summaryRequests).hasSize(3);
        assertThat(model.summaryRequests.get(1).get(1).getText())
                .contains("Round 1:\n")
                .doesNotContain("Round 2:");
        assertThat(model.summaryRequests.get(2).get(1).getText())
                .doesNotContain("notes on round 1")
                .contains("Round 2:\n")
                .doesNotContain("Round 1:")
                .doesNotContain("Round 3:");
        assertThat(model.requests.get(3)).hasSize(4);
        assertThat(((TextBlock) model.requests.get(3).get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(2, "notes on round 1", "notes on round 2"));
        assertThat(events(ctx).get(1).getDetails())
                .containsEntry("rounds", 2)
                .containsEntry("summarized_rounds", 2)
                .doesNotContainKey("error");
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
    }

    @Test
    void aSummaryThatKeepsFailingIsTriedAgainAfterOneTwoFourAndThenEightRounds() throws Exception {
        Object[] replies = new Object[26];
        for (int i = 0; i < 25; i++) {
            replies[i] = callTool("c" + (i + 1));
        }
        replies[25] = ChatFixtures.response("final");
        Object[] down = new Object[5];
        java.util.Arrays.fill(down, new IllegalStateException("summarizer is down"));
        ScriptedModel model = new ScriptedModel(replies).summaries(down);
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // The request alone takes about 900 of the 1000 tokens of the budget, so every call
        // with a round that is due is over the trigger.
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        // The step is tried with 2 rounds, and after each failure the request waits longer:
        // 1 round, then 2, 4 and 8, and no longer than 8.
        List<Integer> tried = List.of(2, 3, 5, 9, 17);
        int failures = 0;
        for (int rounds = 1; rounds < 25; rounds++) {
            toolResult(ctx, "c" + rounds, "r");
            if (tried.contains(rounds)) {
                failures++;
            }
            assertThat(model.summaryRequests).as("with %s rounds", rounds).hasSize(failures);
            // While the request waits, its input is not estimated either.
            assertThat(ctx.estimates()).as("with %s rounds", rounds).hasSize(failures);
        }
        assertThat(state(ctx, request.getId()))
                .containsEntry("failed_steps", 5)
                .containsEntry("retry_at_rounds", 25);
        assertThat(events(ctx)).hasSize(5);
        assertThat(events(ctx))
                .allSatisfy(
                        event ->
                                assertThat((String) event.getDetails().get("error"))
                                        .contains("summarizer is down"));

        // With 25 rounds the summarizer answers, which ends the wait.
        toolResult(ctx, "c25", "r");
        assertThat(model.summaryRequests).hasSizeGreaterThan(5);
        assertThat(events(ctx)).hasSize(6);
        assertThat(events(ctx).get(5).getDetails())
                .containsEntry("rounds", 24)
                .doesNotContainKey("error");
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
    }

    @Test
    void aSummaryThatIsWrittenEndsTheWait() throws Exception {
        IllegalStateException down = new IllegalStateException("summarizer is down");
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                callTool("c4"),
                                callTool("c5"),
                                callTool("c6"),
                                callTool("c7"))
                        .summaries(down, down, "ok", down);
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // The request alone takes about 900 of the 1000 tokens of the budget, so every call
        // with a round that is due is over the trigger.
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        toolResult(ctx, "c1", "r");
        toolResult(ctx, "c2", "r");
        toolResult(ctx, "c3", "r");
        // Two failures in a row: the request waits two rounds.
        assertThat(state(ctx, request.getId()))
                .containsEntry("failed_steps", 2)
                .containsEntry("retry_at_rounds", 5);
        toolResult(ctx, "c4", "r");
        assertThat(model.summaryRequests).hasSize(2);

        // With 5 rounds the lines of rounds 1 to 4 are written, which ends the wait.
        toolResult(ctx, "c5", "r");
        assertThat(model.summaryRequests).hasSize(3);
        assertThat(state(ctx, request.getId()))
                .containsEntry("written_rounds", 4)
                .doesNotContainKeys("failed_steps", "retry_at_rounds");

        // So the next failure is a first one again, and the request waits one round.
        toolResult(ctx, "c6", "r");
        assertThat(model.summaryRequests).hasSize(4);
        assertThat(state(ctx, request.getId()))
                .containsEntry("failed_steps", 1)
                .containsEntry("retry_at_rounds", 7);
    }

    @Test
    void aRoundWhoseAnswerCannotBeUsedInThreeStepsInARowGetsNoLine() throws Exception {
        Object[] replies = new Object[10];
        for (int i = 0; i < 9; i++) {
            replies[i] = callTool("c" + (i + 1));
        }
        replies[9] = ChatFixtures.response("final");
        // A summarizer that writes no line for round 1, whatever else it is shown.
        ScriptedModel model =
                new ScriptedModel(replies) {
                    @Override
                    public ChatResult chatWithoutTools(List<ChatMessage> messages) {
                        ChatResult answer = super.chatWithoutTools(messages);
                        return messages.get(1).getText().contains("Round 1:\n")
                                ? ChatFixtures.response("I cannot write about this.")
                                : answer;
                    }
                };
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // The request alone takes about 900 of the 1000 tokens of the budget, so every call
        // with a round that is due is over the trigger.
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        // The steps with 2 and 3 rounds stop at round 1.
        for (int rounds = 1; rounds <= 4; rounds++) {
            toolResult(ctx, "c" + rounds, repeat("r", 600));
        }
        assertThat(events(ctx)).hasSize(2);
        assertThat(events(ctx))
                .allSatisfy(
                        event ->
                                assertThat(event.getDetails())
                                        .containsEntry(
                                                "error", "the summarizer wrote no line for round 1")
                                        .doesNotContainKey("passed_over_round"));
        assertThat(state(ctx, request.getId()))
                .containsEntry("unusable_answers", 2)
                .doesNotContainKey("written_rounds");

        // The step with 5 rounds is the third in a row that stops there. Round 1 gets no line
        // from now on and stays in the messages.
        toolResult(ctx, "c5", repeat("r", 600));
        assertThat(events(ctx)).hasSize(3);
        assertThat(events(ctx).get(2).getDetails())
                .containsEntry("error", "the summarizer wrote no line for round 1")
                .containsEntry("passed_over_round", 1);
        // What held the steps up is behind, so the request does not wait as it does after a
        // third failure. The count of failed steps in a row stays until a summary is written.
        assertThat(state(ctx, request.getId()))
                .containsEntry("written_rounds", 1)
                .containsEntry("failed_steps", 3)
                .doesNotContainKeys("unusable_answers", "retry_at_rounds");

        // The next model call summarizes the rounds after it: rounds 2 to 5 with 6 rounds, and
        // one more with each round after that.
        toolResult(ctx, "c6", repeat("r", 600));
        assertThat(state(ctx, request.getId())).doesNotContainKey("failed_steps");
        assertThat(events(ctx)).hasSize(4);
        assertThat(events(ctx).get(3).getDetails())
                .containsEntry("rounds", 4)
                .containsEntry("summarized_rounds", 4)
                .doesNotContainKeys("error", "passed_over_round");
        for (int rounds = 7; rounds <= 9; rounds++) {
            toolResult(ctx, "c" + rounds, repeat("r", 600));
        }
        assertThat(events(ctx)).hasSize(7);
        assertThat(events(ctx).get(6).getDetails()).containsEntry("summarized_rounds", 7);
        List<ChatMessage> last = model.requests.get(9);
        assertThat(last).hasSize(5);
        assertThat(last.get(1).getToolCalls().get(0).getCallId()).isEqualTo("c1");
        assertThat(toolText(last.get(2))).isEqualTo(repeat("r", 600));
        assertThat(last.get(3).getToolCalls().get(0).getCallId()).isEqualTo("c9");
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
    }

    @Test
    void aSummarizerWhoseAnswersCanNeverBeUsedIsNotCalledForEveryRound() throws Exception {
        Object[] calls = new Object[31];
        for (int number = 1; number <= 31; number++) {
            calls[number - 1] = callTool("c" + number);
        }
        // A summarizer whose answers are never the lines of rounds.
        ScriptedModel model =
                new ScriptedModel(calls) {
                    @Override
                    public ChatResult chatWithoutTools(List<ChatMessage> messages) {
                        summaryRequests.add(List.copyOf(messages));
                        return ChatFixtures.response(
                                "Here is what happened: the tool returned some numbers.");
                    }
                };
        Context ctx =
                new Context().with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 4000);
        ctx.register(MODEL, model);

        send(ctx, request());
        List<Integer> askedBeforeCall = new ArrayList<>();
        for (int number = 1; number <= 30; number++) {
            int asked = model.summaryRequests.size();
            toolResult(ctx, "c" + number, repeat(String.valueOf(number % 10), 2000));
            if (model.summaryRequests.size() > asked) {
                askedBeforeCall.add(number + 1);
            }
        }

        // The steps before calls 8, 9 and 11 stop at round 1, which is passed over with the
        // third. The round after it is tried with the next call, and the count of failed steps
        // goes on: after the fourth failure the request waits eight rounds, as it does for a
        // summarizer that keeps failing. Round 2 is passed over before call 28.
        assertThat(askedBeforeCall).containsExactly(8, 9, 11, 12, 20, 28, 29);
        List<Object> passedOver = new ArrayList<>();
        for (ContextManagementEvent event : events(ctx)) {
            if (event.getDetails().containsKey("passed_over_round")) {
                passedOver.add(event.getDetails().get("passed_over_round"));
            }
        }
        assertThat(passedOver).containsExactly(1, 2);
    }

    @Test
    void theCountOfUnusableAnswersStartsAgainAtAnotherRound() throws Exception {
        String other = "Round 9: a line for a round that was not shown";
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                callTool("c4"),
                                callTool("c5"),
                                callTool("c6"))
                        // The steps with 2 and 3 rounds get no line for round 1. The step
                        // with 5 rounds gets it, and then no line for round 2.
                        .summaries(
                                other,
                                other,
                                other,
                                "Round 1: notes on round 1",
                                "Round 1: notes on round 1",
                                other);
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        for (int rounds = 1; rounds <= 4; rounds++) {
            toolResult(ctx, "c" + rounds, repeat("r", 600));
        }
        assertThat(state(ctx, request.getId())).containsEntry("unusable_answers", 2);
        toolResult(ctx, "c5", repeat("r", 600));

        // The third step in a row stops with an answer that cannot be used, but at round 2:
        // it is the first such answer for that round, which is not given up.
        assertThat(events(ctx)).hasSize(3);
        assertThat(events(ctx).get(2).getDetails())
                .containsEntry("error", "the summarizer wrote no line for round 2")
                .containsEntry("rounds", 1)
                .doesNotContainKey("passed_over_round");
        assertThat(state(ctx, request.getId()))
                .containsEntry("unusable_answers", 1)
                .containsEntry("written_rounds", 1);
    }

    @Test
    void theCountOfUnusableAnswersGoesWithARoundThatIsPassedOver() throws Exception {
        // A summarizer that writes no line at all.
        ScriptedModel model =
                new ScriptedModel(
                        callTool("c1"),
                        callTool("c2"),
                        callTool("c3"),
                        callTool("c4"),
                        callTool("c5"),
                        callTool("c6")) {
                    @Override
                    public ChatResult chatWithoutTools(List<ChatMessage> messages) {
                        super.chatWithoutTools(messages);
                        return ChatFixtures.response("I cannot write about this.");
                    }
                };
        // The last three rounds keep their results, and a summary would leave the last round.
        Context ctx =
                summarizing()
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                3);
        ctx.register(MODEL, model);
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        // The steps with 2 and 3 rounds stop at round 1.
        for (int rounds = 1; rounds <= 3; rounds++) {
            toolResult(ctx, "c" + rounds, repeat("r", 600));
        }
        assertThat(state(ctx, request.getId())).containsEntry("unusable_answers", 2);

        // With 4 rounds, round 1 is past the masking boundary and gets no line any more. The
        // two answers were about it, so their count goes with it.
        toolResult(ctx, "c4", repeat("r", 600));
        assertThat(state(ctx, request.getId()))
                .containsEntry("written_rounds", 1)
                .doesNotContainKey("unusable_answers");

        // With 5 rounds the next step is tried, and round 2 is masked too. It stops at round 3
        // with the first answer that cannot be used for that round, which is not given up.
        toolResult(ctx, "c5", repeat("r", 600));
        List<Map<String, Object>> summaries =
                events(ctx).stream()
                        .filter(e -> ContextManagementEvent.STEP_SUMMARIZE.equals(e.getStep()))
                        .map(ContextManagementEvent::getDetails)
                        .collect(Collectors.toList());
        assertThat(summaries.get(summaries.size() - 1))
                .containsEntry("error", "the summarizer wrote no line for round 3")
                .doesNotContainKey("passed_over_round");
        assertThat(state(ctx, request.getId()))
                .containsEntry("unusable_answers", 1)
                .containsEntry("written_rounds", 2);
    }

    @Test
    void aStepThatFailsForAnotherReasonStartsTheCountOfUnusableAnswersAgain() throws Exception {
        Object[] replies = new Object[10];
        for (int i = 0; i < 10; i++) {
            replies[i] = callTool("c" + (i + 1));
        }
        // The summarizer writes no line for round 1. Its second call does not answer at all.
        int[] asked = new int[1];
        ScriptedModel model =
                new ScriptedModel(replies) {
                    @Override
                    public ChatResult chatWithoutTools(List<ChatMessage> messages) {
                        if (++asked[0] == 2) {
                            throw new IllegalStateException("summarizer is down");
                        }
                        super.chatWithoutTools(messages);
                        return ChatFixtures.response("I cannot write about this.");
                    }
                };
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        // Steps are tried with 2, 3, 5 and 9 rounds. The second fails because the summarizer
        // cannot be reached, which says nothing about round 1.
        for (int rounds = 1; rounds <= 9; rounds++) {
            toolResult(ctx, "c" + rounds, repeat("r", 600));
        }

        assertThat(events(ctx)).hasSize(4);
        assertThat((String) events(ctx).get(1).getDetails().get("error"))
                .contains("summarizer is down");
        // So the answers of the steps with 5 and 9 rounds are the first and the second in a
        // row, and round 1 is not given up.
        assertThat(events(ctx))
                .allSatisfy(
                        event ->
                                assertThat(event.getDetails())
                                        .doesNotContainKey("passed_over_round"));
        assertThat(state(ctx, request.getId()))
                .containsEntry("unusable_answers", 2)
                .containsEntry("failed_steps", 4)
                .doesNotContainKey("written_rounds");
    }

    @Test
    void anEmptySummaryIsNotUsed() throws Exception {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("", "the chat model returned no text");
        errors.put("   ", "the chat model returned no text");
        errors.put(" \t\n\n \r\n", "the chat model returned no text");
        // White space is the same list of characters in Java and in Python: an em space is
        // white space, and a control character is text, read as a line.
        errors.put("\u2003 \u00a0", "the chat model returned no text");
        errors.put("\u001f", "the summarizer wrote no line for round 1");
        for (Map.Entry<String, String> answered : errors.entrySet()) {
            ScriptedModel model =
                    new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3"))
                            .summaries(ChatFixtures.response(answered.getKey()));
            Context ctx = summarizing();
            ctx.register(MODEL, model);
            ChatRequestEvent request = request();

            send(ctx, request);
            toolResult(ctx, "c1", repeat("1", 2000));
            toolResult(ctx, "c2", repeat("2", 2000));

            assertThat(model.requests.get(2)).hasSize(6);
            assertThat(events(ctx).get(0).getDetails())
                    .as("answered '%s'", answered.getKey())
                    .containsEntry("error", answered.getValue());
            // An answer without text is an answer that cannot be used for the round.
            assertThat(state(ctx, request.getId())).containsEntry("unusable_answers", 1);
        }
    }

    @Test
    void aChatModelDeclaredInPythonCannotWriteTheSummary() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        Context ctx =
                summarizing()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL, "py");
        ctx.register(MODEL, model);
        ctx.register("py", mock(PythonChatModelSetup.class));

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(model.requests.get(2)).hasSize(6);
        assertThat((String) events(ctx).get(0).getDetails().get("error"))
                .contains("'py' is declared in Python")
                .contains("context-management.summarization.model");
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        // The refusal is the outcome of the summary's durable call. No model was called, so no
        // LLM call is reported.
        assertThat(ctx.durableCallIds)
                .containsExactly("chat", "chat", "estimate", "summarize", "chat");
        assertThat(ctx.reportsAbout("py")).isEmpty();
    }

    @Test
    void aSummarizerThatCannotBeResolvedStillTakesTheDurableCallOfTheSummary() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        Context ctx =
                summarizing()
                        .with(
                                AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL,
                                "small");
        ctx.register(MODEL, model);
        ctx.unresolvable.add("small");

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry(
                        "error",
                        "chat model 'small' cannot be used: java.lang.IllegalArgumentException:"
                                + " resource not found: small")
                .containsEntry("rounds", 0);
        // The durable call records why there is no summary. A replay then makes the same calls
        // in the same order, whether or not the summarizer can be resolved by then.
        assertThat(ctx.durableCallIds)
                .containsExactly("chat", "chat", "estimate", "summarize", "chat");
        // No model was called for the summary, so no LLM call is reported for it.
        assertThat(ctx.reportsAbout("small")).isEmpty();
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
    }

    @Test
    void nothingIsSummarizedWhileNoMoreRoundsThanTheKeptOnesAreLeft() throws Exception {
        ScriptedModel model = new ScriptedModel(callTool("c1"), ChatFixtures.response("final"));
        // One huge round is far over the budget, but it is the last round and is kept.
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 20_000));

        assertThat(model.summaryRequests).isEmpty();
        assertThat(model.requests.get(1)).hasSize(4);
        assertThat(events(ctx)).isEmpty();
    }

    @Test
    void roundsThatFitOneSummarizerRequestAreFoldedInOneCall() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                ChatFixtures.response("final"))
                        .summaries("notes on both rounds");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        // Rounds of about 310 tokens: the third one crosses the trigger of 800.
        toolResult(ctx, "c1", repeat("1", 1200));
        toolResult(ctx, "c2", repeat("2", 1200));
        assertThat(model.summaryRequests).isEmpty();
        toolResult(ctx, "c3", repeat("3", 1200));

        assertThat(model.summaryRequests).hasSize(1);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .contains("Round 1:\n")
                .contains("Round 2:\n")
                .doesNotContain("Round 3:");
        assertThat(events(ctx)).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 2)
                .containsEntry("summarized_rounds", 2);
        assertThat(model.requests.get(3)).hasSize(4);
    }

    @Test
    void oneSummarizerRequestShowsTenRoundsAtMost() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final"))
                        .summaries("first ten", "the eleventh");
        // A budget that has room for many rounds in one request, and a low trigger.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 20_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO,
                                0.01)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // Twelve rounds arrive with the request, so eleven are due at once.
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("go"));
        for (int number = 1; number <= 12; number++) {
            history.add(callTool("h" + number).getMessage());
            history.add(result("h" + number, repeat(String.valueOf(number), 100)));
        }

        send(ctx, new ChatRequestEvent(MODEL, history));

        // A summarizer that is asked for many lines at once is more likely to leave one out,
        // so the rounds are asked about ten at a time.
        assertThat(model.summaryRequests).hasSize(2);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .contains("Round 10:\n")
                .doesNotContain("Round 11:");
        assertThat(model.summaryRequests.get(1).get(1).getText())
                .contains("Round 11:\n")
                .doesNotContain("Round 10:")
                .doesNotContain("Round 12:");
        String[] lines = new String[11];
        java.util.Arrays.fill(lines, "first ten");
        lines[10] = "the eleventh";
        assertThat(((TextBlock) model.requests.get(0).get(0).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(11, lines));
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 11)
                .containsEntry("summarized_rounds", 11);
    }

    @Test
    void truncationMaskingAndSummarizationCombineInThatOrder() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                callTool("c4"),
                                ChatFixtures.response("final"))
                        .summaries("notes on round 3");
        // Results are cut to 1000 characters, the last two rounds keep their results, and
        // rounds before the last one are summarized above 590 estimated tokens.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 1000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                2)
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO,
                                0.59)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        // One round, cut.
        toolResult(ctx, "c1", repeat("1", 4000));
        assertThat(toolText(model.requests.get(1).get(3))).hasSize(1000);

        // Two rounds, both cut: under the trigger.
        toolResult(ctx, "c2", repeat("2", 4000));
        assertThat(toolText(model.requests.get(2).get(3))).hasSize(1000);
        assertThat(toolText(model.requests.get(2).get(5))).hasSize(1000);

        // Three rounds. Cut alone they would be over the trigger. The first is masked, and
        // what is left is under it, so nothing is summarized.
        toolResult(ctx, "c3", repeat("3", 4000));
        List<ChatMessage> fourth = model.requests.get(3);
        assertThat(toolText(fourth.get(3))).startsWith("[Tool result omitted");
        assertThat(approximate(fourth)).isLessThan(590L);
        assertThat(approximate(stored(ctx, request.getId()).subList(0, 8))).isGreaterThan(590L);
        assertThat(model.summaryRequests).isEmpty();

        // Four rounds, two of them masked: over the trigger. Round 3 is the oldest round that
        // still has its result and is not kept, so it is the one that is summarized.
        toolResult(ctx, "c4", repeat("4", 4000));
        assertThat(model.summaryRequests).hasSize(1);
        // The summarizer reads the stored result, cut to the same limit.
        String asked = model.summaryRequests.get(0).get(1).getText();
        assertThat(asked)
                .contains("Round 3:\nlookup({\"q\":\"c3\"}) returned: 3333")
                .contains("[3035 characters truncated]")
                .doesNotContain("Round 1:")
                .doesNotContain("Round 2:")
                .doesNotContain("Tool result omitted");
        List<ChatMessage> fifth = model.requests.get(4);
        assertThat(fifth).hasSize(8);
        assertThat(((TextBlock) fifth.get(1).getBlocks().get(1)).getText())
                .isEqualTo(
                        ContextCompaction.SUMMARY_SEPARATOR
                                + ContextCompaction.summaryText("Round 3: notes on round 3"));
        assertThat(toolText(fifth.get(3))).startsWith("[Tool result omitted");
        assertThat(toolText(fifth.get(5))).startsWith("[Tool result omitted");
        assertThat(fifth.get(6).getToolCalls().get(0).getCallId()).isEqualTo("c4");
        assertThat(toolText(fifth.get(7))).hasSize(1000).contains("characters truncated");

        List<String> steps =
                events(ctx).stream()
                        .map(ContextManagementEvent::getStep)
                        .collect(Collectors.toList());
        assertThat(steps)
                .containsExactly(
                        "truncate",
                        "truncate",
                        "truncate",
                        "mask",
                        "summarize",
                        "truncate",
                        "mask");
    }

    @Test
    void aSummaryIsUsedWhenTheModelEndedItsAnswerByItself() throws Exception {
        // No reason, the reasons of the connections for a complete answer, and the
        // end-of-sequence token as endpoints that speak the OpenAI protocol name it.
        for (String finishReason :
                new String[] {null, "", "stop", "STOP", "end_turn", "eos", "eos_token"}) {
            ChatResult finished =
                    new ChatResult(
                            ChatMessage.assistant("Round 1: account c1 holds 10"),
                            null,
                            null,
                            null,
                            finishReason,
                            Map.of());
            ScriptedModel model =
                    new ScriptedModel(
                                    callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                            .summaries(finished);
            Context ctx = summarizing();
            ctx.register(MODEL, model);

            send(ctx, request());
            toolResult(ctx, "c1", repeat("1", 2000));
            toolResult(ctx, "c2", repeat("2", 2000));

            assertThat(events(ctx).get(0).getDetails())
                    .as("finish reason %s", finishReason)
                    .containsEntry("rounds", 1)
                    .doesNotContainKey("error");
            assertThat(model.requests.get(2)).hasSize(4);
        }
    }

    @Test
    void aSummaryTheProviderDidNotFinishIsNotUsed() throws Exception {
        // The token limit as the chat action reads it and as providers spell it, a context
        // window, a filter, a pause, a sequence of the request at which the answer stopped, and
        // a reason that is not known.
        for (String finishReason :
                new String[] {
                    "length",
                    "max_tokens",
                    "MAX_TOKENS",
                    "model_length",
                    "model_context_window_exceeded",
                    "content_filter",
                    "pause_turn",
                    "tool_use",
                    "stop_sequence",
                    "something_new"
                }) {
            ChatResult cutOff =
                    new ChatResult(
                            ChatMessage.assistant("Round 1: account c1 ho"),
                            null,
                            null,
                            null,
                            finishReason,
                            Map.of());
            ScriptedModel model =
                    new ScriptedModel(
                                    callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                            .summaries(cutOff);
            Context ctx = summarizing();
            ctx.register(MODEL, model);

            send(ctx, request());
            toolResult(ctx, "c1", repeat("1", 2000));
            toolResult(ctx, "c2", repeat("2", 2000));

            // The answer has the line of the round, and the line stops in the middle.
            assertThat(model.requests.get(2)).hasSize(6);
            assertThat(events(ctx).get(0).getDetails())
                    .containsEntry(
                            "error",
                            "the summary is incomplete (finish_reason='" + finishReason + "')")
                    .containsEntry("rounds", 0);
        }
    }

    @Test
    void anAnswerForSeveralRoundsThatWasNotFinishedIsAskedAgainOneRoundAtATime() throws Exception {
        // The summarizer's output limit ends its answer for three rounds in the second line.
        ChatResult cutOff =
                new ChatResult(
                        ChatMessage.assistant("Round 1: account h1 holds 10\nRound 2: acc"),
                        null,
                        null,
                        null,
                        "length",
                        Map.of());
        ScriptedModel model = new ScriptedModel(ChatFixtures.response("final")).summaries(cutOff);
        // A budget that has room for many rounds in one request, and a low trigger.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 20_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO,
                                0.01)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // Four rounds arrive with the request, so three are due at once.
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("go"));
        for (int number = 1; number <= 4; number++) {
            history.add(callTool("h" + number).getMessage());
            history.add(result("h" + number, repeat(String.valueOf(number), 400)));
        }

        send(ctx, new ChatRequestEvent(MODEL, history));

        // Nothing is kept of the answer that was cut off, not even its first line: each round
        // is asked about by itself, and the step does not fail.
        assertThat(model.summaryRequests).hasSize(4);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .contains("Round 1:\n", "Round 2:\n", "Round 3:\n");
        for (int number = 1; number <= 3; number++) {
            Matcher shown =
                    ROUND_HEADING.matcher(model.summaryRequests.get(number).get(1).getText());
            assertThat(shown.find()).isTrue();
            assertThat(shown.group(1)).isEqualTo(String.valueOf(number));
            assertThat(shown.find()).isFalse();
        }
        assertThat(events(ctx)).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 3)
                .containsEntry("summarized_rounds", 3)
                .doesNotContainKey("error");
        List<ChatMessage> sent = model.requests.get(0);
        assertThat(sent).hasSize(3);
        assertThat(((TextBlock) sent.get(0).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(3, "notes"));
    }

    @Test
    void aSummaryThatAsksForAToolCallIsNotUsed() throws Exception {
        // A summarizer is called without tools. This one answers with the line of the round
        // and a tool call all the same, and says that it ended by itself.
        ChatResult withCall =
                new ChatResult(
                        ChatMessage.assistant(
                                List.of(
                                        new TextBlock("Round 1: account c1 holds 10"),
                                        new ToolCallBlock("x", "lookup", Map.of("q", "c9")))),
                        null,
                        null,
                        null,
                        "stop",
                        Map.of());
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                        .summaries(withCall);
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // The round stays in the messages.
        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("error", "the summary asks for a tool call")
                .containsEntry("rounds", 0);
    }

    @Test
    void onlyTheRoundsOfAnAnswerThatCannotBeUsedAreAskedOneAtATime() throws Exception {
        // The summarizer's output limit ends its answer for ten rounds in the second line.
        ChatResult cutOff =
                new ChatResult(
                        ChatMessage.assistant("Round 1: account h1 holds 10\nRound 2: acc"),
                        null,
                        null,
                        null,
                        "length",
                        Map.of());
        ScriptedModel model = new ScriptedModel(ChatFixtures.response("final")).summaries(cutOff);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 20_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO,
                                0.01)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // Twenty-one rounds arrive with the request, so twenty are due at once.
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("go"));
        for (int number = 1; number <= 21; number++) {
            history.add(callTool("h" + number).getMessage());
            history.add(result("h" + number, repeat(String.valueOf(number % 10), 400)));
        }

        send(ctx, new ChatRequestEvent(MODEL, history));

        List<String> shown = new ArrayList<>();
        for (List<ChatMessage> asked : model.summaryRequests) {
            List<String> numbers = new ArrayList<>();
            Matcher heading = ROUND_HEADING.matcher(asked.get(1).getText());
            while (heading.find()) {
                numbers.add(heading.group(1));
            }
            shown.add(String.join(",", numbers));
        }
        // The first request shows ten rounds, and its answer cannot be used. Those ten rounds
        // are asked about one at a time. A summarizer that could not answer for ten rounds is
        // then asked about five: the ten rounds after them take two requests, not ten.
        assertThat(shown)
                .containsExactly(
                        "1,2,3,4,5,6,7,8,9,10",
                        "1",
                        "2",
                        "3",
                        "4",
                        "5",
                        "6",
                        "7",
                        "8",
                        "9",
                        "10",
                        "11,12,13,14,15",
                        "16,17,18,19,20");
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 20)
                .doesNotContainKey("error");
    }

    @Test
    void aVeryLongResultIsShownNoLongerThanARequestHasRoomFor() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                        .summaries("notes on the large round");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        AtomicLong counted = new AtomicLong();

        send(ctx, request());
        // What the manager counts to size a summarizer request is one text at a time.
        try (MockedStatic<ApproximateTokenEstimator> estimator =
                mockStatic(
                        ApproximateTokenEstimator.class,
                        invocation -> {
                            if (invocation.getMethod().getName().equals("estimate")) {
                                List<ChatMessage> counting = invocation.getArgument(0);
                                if (counting.size() == 1) {
                                    counted.addAndGet(counting.get(0).getText().length());
                                }
                            }
                            return invocation.callRealMethod();
                        })) {
            // Two million characters that take a token each, with no limit on a result.
            toolResult(ctx, "c1", repeat("\u8d26", 2_000_000));
            toolResult(ctx, "c2", repeat("2", 100));
        }

        assertThat(model.summaryRequests).hasSize(1);
        assertThat(approximate(model.summaryRequests.get(0))).isEqualTo(999L);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .contains("[1999340 characters truncated]");
        assertThat(events(ctx).get(0).getDetails()).containsEntry("rounds", 1);
        // The request has room for a few hundred of them. The round is put together and
        // counted in that size, not in the size of the result.
        assertThat(counted.get()).isLessThan(100_000L);
    }

    @Test
    void aRoundTooLargeForOneSummarizerRequestHasItsResultsCut() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                        .summaries("notes on the large round");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        // About 5000 tokens, five times the budget. It is the last round, so it is kept.
        toolResult(ctx, "c1", repeat("1", 20_000));
        assertThat(model.summaryRequests).isEmpty();
        toolResult(ctx, "c2", repeat("2", 100));

        // The round is summarized from a request that fits the budget: the limit on its result
        // is halved until it does, here three times, and then raised to the longest one that
        // fits, so the summarizer is shown as much as the budget holds.
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(approximate(model.summaryRequests.get(0))).isEqualTo(1000L);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .contains("Round 1:\n")
                .contains("[17355 characters truncated]");
        assertThat(((TextBlock) model.requests.get(2).get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, "notes on the large round"));
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 1)
                .doesNotContainKey("error");
    }

    @Test
    void aSummaryIsItsOwnLlmCallWithTokenMetrics() throws Exception {
        ChatResult notes =
                new ChatResult(
                        ChatMessage.assistant("Round 1: notes"),
                        "small-provider-model",
                        null,
                        new TokenUsage(700L, 12L),
                        null,
                        Map.of());
        ScriptedModel model =
                new ScriptedModel(
                        callTool("c1"),
                        callTool("c2"),
                        callTool("c3"),
                        ChatFixtures.response("final"));
        ScriptedModel summarizer =
                new ScriptedModel()
                        .summaries(notes, new IllegalStateException("summarizer is down"));
        Context ctx =
                summarizing()
                        .with(
                                AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL,
                                "small");
        ctx.withActionMetricGroup(mock(FlinkAgentsMetricGroup.class));
        ctx.register(MODEL, model);
        ctx.register("small", summarizer);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // The summary is reported under the summarizer's name, with its own usage.
        List<Report> reports = ctx.reportsAbout("small");
        assertThat(reports)
                .extracting(report -> report.phase)
                .containsExactly("started", "succeeded");
        assertThat(reports)
                .allMatch(report -> report.entityType.equals(ExecutionReporter.EntityTypes.LLM));
        assertThat(reports.get(1).metadata)
                .containsEntry("usage", Map.of("promptTokens", 700L, "completionTokens", 12L));
        assertThat(summarizer.tokenMetrics).containsExactly("small-provider-model 700 12");

        // A summary that fails is reported as a failed model call.
        toolResult(ctx, "c3", repeat("3", 2000));
        reports = ctx.reportsAbout("small");
        assertThat(reports)
                .extracting(report -> report.phase)
                .containsExactly("started", "succeeded", "started", "failed");
        assertThat(reports.get(3).error).hasMessageContaining("summarizer is down");
        assertThat(reports.get(3).problemCategory)
                .isEqualTo(ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED);
        assertThat(summarizer.tokenMetrics).hasSize(1);
    }

    @Test
    void aSummaryIsWrittenAsynchronouslyWhenChatCallsAre() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                        .summaries("notes written asynchronously");
        Context ctx = summarizing().with(AgentExecutionOptions.CHAT_ASYNC, true);
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(((TextBlock) model.requests.get(2).get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, "notes written asynchronously"));
        // The estimate is made off the operator's thread too: it renders the bound prompt.
        assertThat(ctx.durableAsyncCallIds)
                .containsExactly("chat", "chat", "estimate", "summarize", "chat");
        assertThat(ctx.durableCallIds).isEqualTo(ctx.durableAsyncCallIds);
    }

    /** A chat model that is declared in Java and names its connection. */
    private static final class OverConnection extends ScriptedModel {
        private final String connection;

        OverConnection(String connection, Object... outcomes) {
            super(outcomes);
            this.connection = connection;
        }

        @Override
        public String getConnectionName() {
            return connection;
        }
    }

    /** A chat model that is declared in Python. */
    private static final class DeclaredInPython extends ScriptedModel
            implements PythonResourceWrapper {
        DeclaredInPython(Object... outcomes) {
            super(outcomes);
        }

        @Override
        public Object getPythonResource() {
            return null;
        }
    }

    /**
     * The ids of the durable calls that left the operator's thread in a request that writes one
     * summary, with {@code chat.async} set.
     *
     * @param asyncSupported whether the Flink version can call Python from another thread
     */
    private static List<String> asyncCallIds(boolean asyncSupported, ScriptedModel model)
            throws Exception {
        Context ctx = summarizing().with(AgentExecutionOptions.CHAT_ASYNC, true);
        ctx.register(MODEL, model);
        ctx.connections.put("python-connection", mock(PythonChatModelConnection.class));
        ctx.connections.put("java-connection", mock(BaseChatModelConnection.class));
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(Utils::supportAsync).thenReturn(asyncSupported);
            send(ctx, request());
            toolResult(ctx, "c1", repeat("1", 2000));
            toolResult(ctx, "c2", repeat("2", 2000));
        }
        assertThat(ctx.durableCallIds)
                .containsExactly("chat", "chat", "estimate", "summarize", "chat");
        return ctx.durableAsyncCallIds;
    }

    /** A chat model that is declared in Java and binds a prompt that Python renders. */
    private static final class WithPythonPrompt extends ScriptedModel {
        private final PythonMCPPrompt prompt = mock(PythonMCPPrompt.class);

        WithPythonPrompt(Object... outcomes) {
            super(outcomes);
            when(prompt.formatMessages(any(), any()))
                    .thenAnswer(
                            call -> new ArrayList<>(List.of(ChatMessage.system("You are terse."))));
        }

        @Override
        public Object getPrompt() {
            return prompt;
        }
    }

    /**
     * A chat model that an agent inherits: it was opened with the connection of the agent that
     * declares it, which is the one its own resource context gives for the name.
     */
    private static final class Inherited extends ScriptedModel {
        private final Resource bound;

        Inherited(Resource bound, Object... outcomes) {
            super(outcomes);
            this.bound = bound;
        }

        @Override
        public String getConnectionName() {
            return "java-connection";
        }

        @Override
        public ResourceContext getResourceContext() {
            return TestResourceContexts.fromGetResource((name, type) -> bound);
        }
    }

    private static Object[] twoRoundsAndAnAnswer() {
        return new Object[] {callTool("c1"), callTool("c2"), ChatFixtures.response("final")};
    }

    @Test
    void aCallThatReachesPythonStaysOnTheOperatorsThreadWhereItCannotLeaveIt() throws Exception {
        // The chat model is declared in Java and its connection in Python: the estimate and
        // the summary are made with it.
        assertThat(
                        asyncCallIds(
                                false,
                                new OverConnection("python-connection", twoRoundsAndAnAnswer())))
                .doesNotContain("estimate", "summarize");
        // The chat model is declared in Python: the estimate is made with it, and it cannot
        // write the summary.
        assertThat(asyncCallIds(false, new DeclaredInPython(twoRoundsAndAnAnswer())))
                .doesNotContain("estimate");
        // The chat model was opened with a connection in Python, and the agent that inherits
        // it has a connection in Java under the same name.
        assertThat(
                        asyncCallIds(
                                false,
                                new Inherited(
                                        mock(PythonChatModelConnection.class),
                                        twoRoundsAndAnAnswer())))
                .doesNotContain("estimate", "summarize");
        // The chat model binds a prompt that Python renders: the estimate renders it, and the
        // summary is written without it.
        assertThat(asyncCallIds(false, new WithPythonPrompt(twoRoundsAndAnAnswer())))
                .doesNotContain("estimate")
                .contains("summarize");
    }

    @Test
    void aCallFollowsChatAsyncWhereItCanReachPythonOrDoesNotReachIt() throws Exception {
        // A Flink version that can call Python from another thread.
        assertThat(
                        asyncCallIds(
                                true,
                                new OverConnection("python-connection", twoRoundsAndAnAnswer())))
                .contains("estimate", "summarize");
        assertThat(asyncCallIds(true, new DeclaredInPython(twoRoundsAndAnAnswer())))
                .contains("estimate");
        assertThat(asyncCallIds(true, new WithPythonPrompt(twoRoundsAndAnAnswer())))
                .contains("estimate", "summarize");
        // One that cannot, and a chat model that does not reach Python: its connection is
        // declared in Java, cannot be resolved here, or has no name.
        for (String connection : new String[] {"java-connection", "missing", null}) {
            assertThat(asyncCallIds(false, new OverConnection(connection, twoRoundsAndAnAnswer())))
                    .as("with the connection %s", connection)
                    .contains("estimate", "summarize");
        }
    }

    @Test
    void anEstimateOnAnotherScaleIsLoweredByTheShareTheLinesSave() throws Exception {
        ScriptedModel model =
                new HalfScaleModel(callTool("c1"), callTool("c2"), callTool("c3"))
                        .summaries("notes on round 1");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 4000));
        toolResult(ctx, "c2", repeat("2", 4000));

        // The estimate of 1018 tokens is half of what the approximation counts. The line saves
        // 928 tokens of the approximation, which would leave 90; the messages are left with 54
        // percent of their size, and so is the estimate.
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("estimated_input_tokens_before", 1018L)
                .containsEntry("estimated_input_tokens_after", 553L);
        // The model call reported 554 tokens, so the estimate was right and next to nothing is
        // corrected. Lowered by the difference, the estimate would have been corrected four
        // times.
        assertThat((Double) state(ctx, request.getId()).get("calibration"))
                .isCloseTo(1.0, within(0.01));
    }

    @Test
    void aRoundTheSummaryCoversIsNotReportedAsMasked() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                ChatFixtures.response("final"))
                        .summaries("notes on round 1");
        // Masking moves two rounds at a time, and a summary leaves the last round.
        Context ctx =
                new Context()
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                1)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS,
                                2)
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 600)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        assertThat(events(ctx)).isEmpty();
        // Two rounds are over the trigger of 480 tokens and neither is masked yet: round 1 is
        // summarized.
        toolResult(ctx, "c2", repeat("2", 2000));
        assertThat(events(ctx))
                .extracting(ContextManagementEvent::getStep)
                .containsExactly("summarize");
        toolResult(ctx, "c3", repeat("3", 2000));

        // Rounds 1 and 2 cross the masking boundary together. Round 1 is in the summary, so only
        // round 2 is sent as a placeholder and reported as masked.
        List<ChatMessage> fourth = model.requests.get(3);
        assertThat(fourth).hasSize(6);
        assertThat(((TextBlock) fourth.get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, "notes on round 1"));
        assertThat(toolText(fourth.get(3))).startsWith("[Tool result omitted");
        assertThat(toolText(fourth.get(5))).isEqualTo(repeat("3", 2000));
        assertThat(events(ctx))
                .extracting(ContextManagementEvent::getStep)
                .containsExactly("summarize", "mask");
        assertThat(events(ctx).get(1).getDetails()).containsEntry("rounds", 1);
        assertThat(results(events(ctx).get(1)))
                .extracting(result -> result.get("call_id"))
                .containsExactly("c2");
        // Round 2 is masked now, so it gets no line.
        assertThat(model.summaryRequests).hasSize(1);
    }

    @Test
    void aRoundWhoseResultsAreMaskedGetsNoLine() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                        callTool("c1"),
                        callTool("c2"),
                        callTool("c3"),
                        ChatFixtures.response("final"));
        // Only the last round keeps its results, and a summary would leave the last round too.
        Context ctx =
                summarizing()
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // The request alone takes about 900 of the 1000 tokens of the budget, so every call is
        // over the trigger.
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 600));
        toolResult(ctx, "c2", repeat("2", 600));
        // How far the rounds without a line go is kept, so they are looked at once.
        assertThat(state(ctx, request.getId())).containsOnly(entry("written_rounds", 1));
        toolResult(ctx, "c3", repeat("3", 600));

        // A round is masked from the call on at which it would be due for its line. Its results
        // are placeholders by then, and a line would be about as long as they are: the
        // summarizer is not asked, and the input is not estimated for it.
        assertThat(model.summaryRequests).isEmpty();
        assertThat(ctx.estimates()).isEmpty();
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "chat", "chat");
        assertThat(events(ctx))
                .extracting(ContextManagementEvent::getStep)
                .containsExactly("mask", "mask");
        List<ChatMessage> fourth = model.requests.get(3);
        assertThat(fourth).hasSize(7);
        assertThat(fourth.get(0).getBlocks()).hasSize(1);
        assertThat(toolText(fourth.get(2))).startsWith("[Tool result omitted");
        assertThat(toolText(fourth.get(4))).startsWith("[Tool result omitted");
        assertThat(toolText(fourth.get(6))).isEqualTo(repeat("3", 600));
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
    }

    @Test
    void aRoundPastTheMaskingBoundaryThatIsStillLongGetsItsLine() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                                // The model says 5000 characters beside its first tool call.
                                callTool("c1", repeat("t", 5000)),
                                callTool("c2"),
                                callTool("c3"),
                                ChatFixtures.response("final"))
                        .summaries("notes on round 1");
        // Only the last round keeps its results, and a summary would leave the last round too.
        Context ctx =
                summarizing()
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);

        send(ctx, request());
        // The result of round 1 is shorter than its placeholder, so it is not masked.
        toolResult(ctx, "c1", "ok");
        toolResult(ctx, "c2", repeat("2", 600));

        // Round 1 is past the masking boundary, and masking left it as long as it was: about
        // 1270 tokens. That is more than a line takes, so the round is due, the input is
        // estimated and the round gets its line.
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(model.summaryRequests.get(0).get(1).getText()).contains("Round 1:\n");
        List<ChatMessage> third = model.requests.get(2);
        assertThat(third).hasSize(4);
        assertThat(((TextBlock) third.get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, "notes on round 1"));
        assertThat(third.get(2).getToolCalls().get(0).getCallId()).isEqualTo("c2");
        assertThat(events(ctx))
                .extracting(ContextManagementEvent::getStep)
                .containsExactly("summarize");

        // Round 2 is past the boundary next. Its result is a placeholder and its call is short:
        // it takes no more than a line, so it gets none.
        toolResult(ctx, "c3", repeat("3", 600));
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(toolText(model.requests.get(3).get(3))).startsWith("[Tool result omitted");
        assertThat(ctx.durableCallIds)
                .containsExactly("chat", "chat", "estimate", "summarize", "chat", "chat");
    }

    @Test
    void aRoundPastTheMaskingBoundaryIsShortByTheSameSizeWithAnyBudget() throws Exception {
        // The reply of round 1 has 800 characters, about 200 tokens.
        ScriptedModel model =
                new ScriptedModel(callTool("c1", repeat("t", 800)), callTool("c2"), callTool("c3"))
                        .summaries("notes on round 1");
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 30_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // The request alone is over the trigger of 24000 tokens.
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 100_000))));

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // Round 1 is past the masking boundary: its result is a placeholder and its reply is
        // sent. With this budget a line may take 455 tokens, and the round takes less. It is
        // still not short, which is 95 tokens with any budget, so it gets its line.
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(state(ctx, request.getId()))
                .containsEntry("summary", "Round 1: notes on round 1");
    }

    @Test
    void aShortRoundPastTheMaskingBoundaryIsNotShownToTheSummarizerBesideALongOne()
            throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final")).summaries("notes on round 1");
        Context ctx =
                summarizing()
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // Four rounds arrive with a request that takes about 900 of the 1000 tokens of the
        // budget by itself. The first three rounds are past the masking boundary: round 1 with
        // 1000 characters of the model's own text, rounds 2 and 3 with nothing but a short
        // call and the placeholder of their result.
        List<ChatMessage> history =
                List.of(
                        ChatMessage.user(repeat("u", 3600)),
                        callTool("h1", repeat("t", 1000)).getMessage(),
                        result("h1", "ok"),
                        callTool("h2").getMessage(),
                        result("h2", repeat("2", 600)),
                        callTool("h3").getMessage(),
                        result("h3", repeat("3", 600)),
                        callTool("h4").getMessage(),
                        result("h4", "4"));

        send(ctx, new ChatRequestEvent(MODEL, history));

        // Round 1 gets its line. Rounds 2 and 3 get none, so the request that asks about
        // round 1 does not show them, although it has room for them.
        assertThat(model.summaryRequests).hasSize(1);
        // Round 1 is shown whole: it is not a round that fills a request by itself.
        assertThat(model.summaryRequests.get(0).get(1).getText()).contains(repeat("t", 1000));
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .contains("Round 1:\n")
                .doesNotContain("Round 2:")
                .doesNotContain("Round 3:");
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 1);
        List<ChatMessage> sent = model.requests.get(0);
        assertThat(sent).hasSize(7);
        assertThat(((TextBlock) sent.get(0).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, "notes on round 1"));
        assertThat(toolText(sent.get(2))).startsWith("[Tool result omitted");
        assertThat(toolText(sent.get(4))).startsWith("[Tool result omitted");
        assertThat(toolText(sent.get(6))).isEqualTo("4");
    }

    @Test
    void aRoundThatLoadsASkillIsNotSummarized() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                ChatFixtures.response(
                                        "",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        "s1",
                                                        "type",
                                                        "function",
                                                        "function",
                                                        Map.of(
                                                                "name",
                                                                "load_skill",
                                                                "arguments",
                                                                Map.of("name", "audit"))))),
                                callTool("c3"),
                                ChatFixtures.response("final"))
                        .summaries("notes on round 1");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 1500));
        toolResult(ctx, "s1", repeat("i", 1500));
        toolResult(ctx, "c3", repeat("3", 1500));

        // Rounds 1 and 2 are due. The summarizer is shown round 1 only; round 2 holds the
        // instructions of a skill and stays in the model's input as it is.
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .contains("Round 1:\n")
                .doesNotContain("Round 2:")
                .doesNotContain("load_skill");
        // The summary joins the user message, in front of the round that stays.
        List<ChatMessage> fourth = model.requests.get(3);
        assertThat(fourth).hasSize(6);
        assertThat(((TextBlock) fourth.get(1).getBlocks().get(1)).getText())
                .isEqualTo(
                        ContextCompaction.SUMMARY_SEPARATOR
                                + ContextCompaction.summaryText("Round 1: notes on round 1"));
        assertThat(fourth.get(2).getToolCalls().get(0).getName()).isEqualTo("load_skill");
        assertThat(toolText(fourth.get(3))).isEqualTo(repeat("i", 1500));
        assertThat(toolText(fourth.get(5))).isEqualTo(repeat("3", 1500));
        // One round got its line, and the summary has the line of that round alone.
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 1);
    }

    @Test
    void aRoundThatLoadsASkillIsPassedOverWithoutAskingTheSummarizer() throws Exception {
        ChatResult loadSkill =
                ChatFixtures.response(
                        "",
                        List.of(
                                Map.of(
                                        "id",
                                        "s1",
                                        "type",
                                        "function",
                                        "function",
                                        Map.of(
                                                "name",
                                                "load_skill",
                                                "arguments",
                                                Map.of("name", "audit")))));
        ScriptedModel model =
                new ScriptedModel(
                                loadSkill,
                                callTool("c2"),
                                callTool("c3"),
                                ChatFixtures.response("final"))
                        .summaries("notes on round 2");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "s1", repeat("i", 2500));
        toolResult(ctx, "c2", repeat("2", 2000));

        // Round 1 is due, and it loads a skill: there is nothing to estimate, nothing to ask
        // the summarizer and nothing to report. The model is sent both rounds as they are.
        assertThat(model.summaryRequests).isEmpty();
        assertThat(events(ctx)).isEmpty();
        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "chat");

        // Round 2 is due next. The summary has its line alone, and round 1 stays.
        toolResult(ctx, "c3", repeat("3", 2000));
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .contains("Round 2:\n")
                .doesNotContain("Round 1:");
        List<ChatMessage> fourth = model.requests.get(3);
        assertThat(fourth).hasSize(6);
        assertThat(((TextBlock) fourth.get(1).getBlocks().get(1)).getText())
                .isEqualTo(
                        ContextCompaction.SUMMARY_SEPARATOR
                                + ContextCompaction.summaryText("Round 2: notes on round 2"));
        assertThat(toolText(fourth.get(3))).isEqualTo(repeat("i", 2500));
        assertThat(fourth.get(4).getToolCalls().get(0).getCallId()).isEqualTo("c3");
        assertThat(events(ctx)).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 1);
    }

    @Test
    void roundsAfterAnOversizedOneStartABatchOfTheirOwn() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final"))
                        .summaries("notes on round 1", "notes on round 2");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // Three rounds arrive with the request: one with two results of 3000 characters, one
        // with a result of 1000 characters, and the kept one.
        List<ChatMessage> history =
                List.of(
                        ChatMessage.user("go"),
                        ChatMessage.assistant(
                                List.of(
                                        new ToolCallBlock("h1a", "lookup", Map.of("q", "h1a")),
                                        new ToolCallBlock("h1b", "lookup", Map.of("q", "h1b")))),
                        result("h1a", repeat("a", 3000)),
                        result("h1b", repeat("b", 3000)),
                        callTool("h2").getMessage(),
                        result("h2", repeat("2", 1000)),
                        callTool("h3").getMessage(),
                        result("h3", repeat("3", 100)));

        send(ctx, new ChatRequestEvent(MODEL, history));

        // Round 1 fits a request only with its results cut to about 750 characters, and is the
        // only round of that request. That limit is not applied to round 2, which is shown
        // whole in a request of its own.
        assertThat(model.summaryRequests).hasSize(2);
        String first = model.summaryRequests.get(0).get(1).getText();
        assertThat(first).contains("Round 1:\n").doesNotContain("Round 2:");
        assertThat(first.split("characters truncated", -1)).hasSize(3);
        assertThat(model.summaryRequests.get(1).get(1).getText())
                .doesNotContain("notes on round 1")
                .contains(repeat("2", 1000))
                .doesNotContain("characters truncated");
        assertThat(model.summaryRequests)
                .allSatisfy(request -> assertThat(approximate(request)).isLessThanOrEqualTo(1000));
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 2)
                .containsEntry("summarized_rounds", 2);
        assertThat(ctx.durableCallIds)
                .containsExactly("estimate", "summarize", "summarize", "chat");
    }

    @Test
    void aRoundWhoseTextIsTooLongEvenWithCutResultsIsCutAsAWhole() throws Exception {
        // The reply that asks for the tool also carries 7000 characters of text.
        ChatResult wordy =
                new ChatResult(
                        ChatMessage.assistant(
                                List.of(
                                        new TextBlock(repeat("w", 7000)),
                                        new ToolCallBlock("c1", "lookup", Map.of("q", "c1")))));
        ScriptedModel model =
                new ScriptedModel(wordy, callTool("c2"), ChatFixtures.response("final"))
                        .summaries("notes on the wordy round");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 300));
        toolResult(ctx, "c2", repeat("2", 300));

        assertThat(model.summaryRequests).hasSize(1);
        assertThat(approximate(model.summaryRequests.get(0))).isLessThanOrEqualTo(1000);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .contains("Round 1:\nAssistant: www")
                .contains("characters truncated");
        assertThat(events(ctx).get(0).getDetails()).doesNotContainKey("error");
    }

    @Test
    void aRoundThatIsCutMoreThanOnceTellsHowMuchOfTheWholeRoundIsMissing() throws Exception {
        // 3000 characters that count as a token each: cutting the round to four characters for
        // a token of room is not enough, so it is cut again.
        ChatResult wordy =
                new ChatResult(
                        ChatMessage.assistant(
                                List.of(
                                        new TextBlock(repeat("é", 3000)),
                                        new ToolCallBlock("c1", "lookup", Map.of("q", "c1")))));
        ScriptedModel model =
                new ScriptedModel(wordy, callTool("c2"), ChatFixtures.response("final"))
                        .summaries("notes on the wordy round");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", "ten");
        toolResult(ctx, "c2", "twenty");

        assertThat(model.summaryRequests).hasSize(1);
        assertThat(approximate(model.summaryRequests.get(0))).isLessThanOrEqualTo(1000);
        String asked = model.summaryRequests.get(0).get(1).getText();
        java.util.regex.Matcher marker =
                java.util.regex.Pattern.compile("\\[(\\d+) characters truncated]").matcher(asked);
        assertThat(marker.find()).isTrue();
        long kept = asked.chars().filter(c -> c == 'é').count();
        // Every cut is made from the whole round, so the one marker accounts for all of it.
        assertThat(Long.parseLong(marker.group(1)) + kept).isEqualTo(3000);
        assertThat(marker.find()).isFalse();
        assertThat(asked).contains("Round 1:\nAssistant: é").contains("returned: ten\n");
    }

    @Test
    void aBudgetTooSmallForASummarizerRequestFailsTheSummaryWithoutACall() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        // The summarizer's instructions alone are about 250 tokens, and what it is told about
        // this request takes 100 more.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 400)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);

        send(
                ctx,
                new ChatRequestEvent(
                        MODEL,
                        List.of(
                                ChatMessage.system(repeat("s", 400)),
                                ChatMessage.user(repeat("u", 400)))));
        toolResult(ctx, "c1", repeat("1", 500));
        toolResult(ctx, "c2", repeat("2", 500));

        assertThat(model.summaryRequests).isEmpty();
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry(
                        "error",
                        "a summarizer request has no room for a round within"
                                + " max_input_tokens=400")
                .containsEntry("rounds", 0);
        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "estimate", "chat");
    }

    @Test
    void aSummarizerRequestWithRoomForLessThanARoundIsNotSent() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        // The summarizer's instructions and what it is told about this request take about 430
        // tokens, which leaves about 20: less than a round needs.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 450)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);

        send(
                ctx,
                new ChatRequestEvent(
                        MODEL,
                        List.of(
                                ChatMessage.system(repeat("s", 400)),
                                ChatMessage.user(repeat("u", 400)))));
        // Round 1 is short enough for what is left. The request is not sent all the same:
        // whether rounds can be shown does not depend on the round.
        toolResult(ctx, "c1", "ok");
        toolResult(ctx, "c2", repeat("2", 1200));

        assertThat(model.summaryRequests).isEmpty();
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry(
                        "error",
                        "a summarizer request has no room for a round within"
                                + " max_input_tokens=450")
                .containsEntry("rounds", 0);
    }

    /**
     * One round that looks up several accounts at once. Each result starts with the amount of its
     * account and has {@code chars} characters.
     */
    private static List<ChatMessage> parallelRound(int accounts, int chars) {
        List<ChatMessage> round = new ArrayList<>();
        List<ToolCallBlock> calls = new ArrayList<>();
        for (int account = 1; account <= accounts; account++) {
            calls.add(new ToolCallBlock("a" + account, "lookup", Map.of("account", account)));
        }
        round.add(ChatMessage.assistant(calls));
        for (int account = 1; account <= accounts; account++) {
            String amount = "amount=" + account + "00 ";
            round.add(result("a" + account, amount + repeat("x", chars - amount.length())));
        }
        return round;
    }

    @Test
    void whatIsLeftOfAResultStaysWithTheCallItAnswers() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final")).summaries("notes on round 1");
        // A budget that leaves a summarizer request about 115 tokens for rounds.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 390)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // Round 1 has two calls. Its results do not fit even at their shortest limit.
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("go"));
        history.addAll(parallelRound(2, 600));
        history.add(callTool("h2").getMessage());
        history.add(result("h2", repeat("2", 1200)));

        send(ctx, new ChatRequestEvent(MODEL, history));

        // Each result is cut by itself, so its amount stands in the line of its own call. Cut
        // as one text, the round would show the call for account 1 and then the end of the
        // result for account 2.
        assertThat(model.summaryRequests).hasSize(1);
        String asked = model.summaryRequests.get(0).get(1).getText();
        String[] shown = asked.substring(asked.indexOf("Round 1:\n")).split("\n");
        assertThat(shown[0]).isEqualTo("Round 1:");
        assertThat(shown[1]).startsWith("lookup({\"account\":1}) returned: amount=100 x");
        assertThat(shown[3]).startsWith("x");
        assertThat(shown[4]).startsWith("lookup({\"account\":2}) returned: amount=200 x");
        assertThat(asked.split("characters truncated", -1)).hasSize(3);
        assertThat(approximate(model.summaryRequests.get(0))).isLessThanOrEqualTo(390);
        assertThat(events(ctx).get(0).getDetails()).containsEntry("rounds", 1);
    }

    @Test
    void resultsThatDoNotFitInTheirShortestFormAreLeftOutAndCounted() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final")).summaries("notes on round 1");
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 600)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // Round 1 has 60 calls: more than the room of about 325 tokens holds, even with every
        // result cut to the least that is left of a part.
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("go"));
        history.addAll(parallelRound(60, 600));
        history.add(callTool("h2").getMessage());
        history.add(result("h2", repeat("2", 1200)));

        send(ctx, new ChatRequestEvent(MODEL, history));

        assertThat(model.summaryRequests).hasSize(1);
        String asked = model.summaryRequests.get(0).get(1).getText();
        String round =
                asked.substring(asked.indexOf("Round 1:\n"), asked.indexOf("\n\nWrite one line"));
        String[] lines = round.split("\n");
        // The first results are shown, each with its call, and the last line tells how many
        // lines of the round are not shown.
        List<String> shown = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("lookup(")) {
                shown.add(line.substring(0, line.indexOf(')')));
            }
        }
        assertThat(shown).isNotEmpty().hasSizeLessThan(60);
        for (int index = 0; index < shown.size(); index++) {
            assertThat(shown.get(index)).isEqualTo("lookup({\"account\":" + (index + 1) + "}");
        }
        assertThat(lines[lines.length - 1])
                .isEqualTo("[" + (60 - shown.size()) + " more lines of this round are not shown]");
        assertThat(approximate(model.summaryRequests.get(0))).isLessThanOrEqualTo(600);
        assertThat(events(ctx).get(0).getDetails()).doesNotContainKey("error");
    }

    @Test
    void aRoundThatIsCutPartByPartKeepsTheLimitOnAToolResult() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1", repeat("a", 40_000)),
                                callTool("c2"),
                                ChatFixtures.response("final"))
                        .summaries("notes on round 1");
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1500)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1)
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100);
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("z", 606));
        toolResult(ctx, "c2", repeat("2", 50));

        // The model is sent 100 characters of the first result.
        String sent = toolText(model.requests.get(1).get(3));
        assertThat(sent).hasSize(100);
        // The reply of round 1 is far too long for a summarizer request, so the round is cut
        // part by part. The summarizer is shown the result as the model was, and no more of it.
        assertThat(model.summaryRequests).hasSize(1);
        String asked = model.summaryRequests.get(0).get(1).getText();
        assertThat(asked).contains(sent);
        assertThat(asked.chars().filter(c -> c == 'z').count())
                .isEqualTo(sent.chars().filter(c -> c == 'z').count());
        assertThat(approximate(model.summaryRequests.get(0))).isEqualTo(1500L);
    }

    @Test
    void aRoundThatIsCutPartByPartShowsAResultThatFitsWhole() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1", repeat("a", 40_000)),
                                callTool("c2"),
                                ChatFixtures.response("final"))
                        .summaries("notes on round 1");
        // No limit on a tool result.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1500)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("z", 606));
        toolResult(ctx, "c2", repeat("2", 50));

        // The reply is what is too long. It is cut, and the result, which has room, is shown
        // whole: it is not cut to the shortest limit that results are tried with.
        assertThat(model.summaryRequests).hasSize(1);
        String asked = model.summaryRequests.get(0).get(1).getText();
        assertThat(asked.chars().filter(c -> c == 'z').count()).isEqualTo(606);
        assertThat(asked).contains("[35976 characters truncated]");
        assertThat(approximate(model.summaryRequests.get(0))).isEqualTo(1499L);
    }

    @Test
    void aRoundThatCannotFitARequestIsNotPutTogetherForIt() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final")).summaries("notes on round 1");
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 16_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // Round 1 has 64 calls with results of 70,000 characters each.
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("go"));
        history.addAll(parallelRound(64, 70_000));
        history.add(callTool("h2").getMessage());
        history.add(result("h2", repeat("2", 1200)));
        AtomicLong counted = new AtomicLong();

        // What the manager counts to size a summarizer request is one text at a time.
        try (MockedStatic<ApproximateTokenEstimator> estimator =
                mockStatic(
                        ApproximateTokenEstimator.class,
                        invocation -> {
                            if (invocation.getMethod().getName().equals("estimate")) {
                                List<ChatMessage> counting = invocation.getArgument(0);
                                if (counting.size() == 1) {
                                    counted.addAndGet(counting.get(0).getText().length());
                                }
                            }
                            return invocation.callRealMethod();
                        })) {
            send(ctx, new ChatRequestEvent(MODEL, history));
        }

        assertThat(model.summaryRequests).hasSize(1);
        assertThat(approximate(model.summaryRequests.get(0))).isEqualTo(15_985L);
        assertThat(
                        model.summaryRequests
                                .get(0)
                                .get(1)
                                .getText()
                                .split("\\[69090 characters truncated\\]", -1))
                .hasSize(65);
        assertThat(events(ctx).get(0).getDetails()).containsEntry("rounds", 1);
        // About 4.5 million characters of results. What is counted to size the request is in
        // the size of the request.
        assertThat(counted.get()).isLessThan(200_000L);
    }

    @Test
    void aResultIsShownBeforeTheTextTheAssistantWroteWithItsCall() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final")).summaries("notes on round 1");
        // A budget that leaves a summarizer request 51 tokens for rounds: room for the label,
        // one part and the line that counts what is left out.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 356)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // Round 1 has a long text beside its call, and one result.
        List<ChatMessage> history =
                List.of(
                        ChatMessage.user("go"),
                        callTool("h1", repeat("t", 2000)).getMessage(),
                        result("h1", "amount=100 " + repeat("x", 600)),
                        callTool("h2").getMessage(),
                        result("h2", repeat("2", 1200)));

        send(ctx, new ChatRequestEvent(MODEL, history));

        // There is room for one part. It is the result with its call: the text the assistant
        // wrote says less about what the round found.
        assertThat(model.summaryRequests).hasSize(1);
        String asked = model.summaryRequests.get(0).get(1).getText();
        String shown =
                asked.substring(asked.indexOf("Round 1:\n"), asked.indexOf("\n\nWrite one line"));
        assertThat(shown)
                .startsWith("Round 1:\nlookup({\"q\":\"h1\"}) returned: amount=100 x")
                .endsWith("\n[1 more lines of this round are not shown]")
                .doesNotContain("Assistant:");
        assertThat(approximate(model.summaryRequests.get(0))).isLessThanOrEqualTo(356);
    }

    @Test
    void aRoundOfAnyScriptIsCutToTheRoomOfASummarizerRequest() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final")).summaries("notes on round 1");
        // A budget that leaves a summarizer request about 60 tokens for rounds.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 500)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // Round 1 is in a script that takes a token for each character, so 100 characters of
        // it do not fit. Round 2 is kept.
        List<ChatMessage> history =
                List.of(
                        ChatMessage.system(repeat("s", 400)),
                        ChatMessage.user(repeat("u", 400)),
                        callTool("h1", repeat("\u8d26", 300)).getMessage(),
                        result("h1", repeat("\u8d26", 300)),
                        callTool("h2").getMessage(),
                        result("h2", repeat("2", 1200)));

        send(ctx, new ChatRequestEvent(MODEL, history));

        // The round is cut to what fits and gets its line, so it does not hold up the rounds
        // after it.
        assertThat(model.summaryRequests).hasSize(1);
        String asked = model.summaryRequests.get(0).get(1).getText();
        assertThat(asked).contains("Round 1:\n").contains("characters truncated");
        assertThat(asked.chars().filter(c -> c == '\u8d26').count()).isBetween(1L, 59L);
        assertThat(approximate(model.summaryRequests.get(0))).isLessThanOrEqualTo(500);
        assertThat(events(ctx).get(0).getDetails())
                .doesNotContainKey("error")
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 1);
    }

    @Test
    void onlyTheLineOfEachRoundShownIsKept() throws Exception {
        // The summarizer adds a preamble, a line for a round it was not shown, a total and a
        // next step.
        String answered =
                "Here are the notes:\n"
                        + "- **Round 1:** account c1 holds 10\n"
                        + "Round 7: account c7 holds 70\n"
                        + "Total so far: 80\n"
                        + "Next, look up c2.";
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                        .summaries(answered);
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // The notes hold the line of round 1 and nothing else.
        assertThat(((TextBlock) model.requests.get(2).get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, "account c1 holds 10"));
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("summary_chars", "Round 1: account c1 holds 10".length())
                .doesNotContainKey("error");
    }

    @Test
    void anAnswerWithoutTheLineOfARoundIsNotUsed() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                ChatFixtures.response("final"))
                        .summaries(
                                "Round 2: the summarizer mixed up the rounds",
                                "notes on round 1",
                                "notes on round 2");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // Nothing is kept of an answer that leaves out a round it was shown.
        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("error", "the summarizer wrote no line for round 1")
                .containsEntry("rounds", 0);
        // What is kept is that the step failed, from how many rounds on it is tried again, and
        // that it stopped at a round whose answer could not be used.
        assertThat(state(ctx, request.getId()))
                .containsOnly(
                        entry("failed_steps", 1),
                        entry("retry_at_rounds", 3),
                        entry("unusable_answers", 1));

        // The next model call asks again.
        toolResult(ctx, "c3", repeat("3", 2000));
        assertThat(((TextBlock) model.requests.get(3).get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(2, "notes on round 1", "notes on round 2"));
    }

    @Test
    void roundsAreAskedAboutOneAtATimeAfterAnAnswerThatLeftOneOut() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final"))
                        .summaries(
                                "Round 1: the summarizer wrote one line for two rounds", "a", "b");
        // A budget that has room for both rounds in one request, and a low trigger.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 20_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO,
                                0.01)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("go"));
        for (int number = 1; number <= 3; number++) {
            history.add(callTool("h" + number).getMessage());
            history.add(result("h" + number, repeat(String.valueOf(number), 400)));
        }

        send(ctx, new ChatRequestEvent(MODEL, history));

        // Nothing is kept of the first answer. Each round is then shown alone, where its line
        // cannot be left out for another.
        assertThat(model.summaryRequests).hasSize(3);
        assertThat(model.summaryRequests.get(0).get(1).getText()).contains("Round 2:\n");
        assertThat(model.summaryRequests.get(1).get(1).getText())
                .contains("Round 1:\n")
                .doesNotContain("Round 2:");
        assertThat(model.summaryRequests.get(2).get(1).getText())
                .contains("Round 2:\n")
                .doesNotContain("Round 1:");
        assertThat(((TextBlock) model.requests.get(0).get(0).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(2, "a", "b"));
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 2)
                .doesNotContainKey("error");
        assertThat(ctx.durableCallIds)
                .containsExactly("estimate", "summarize", "summarize", "summarize", "chat");
    }

    @Test
    void aLineThatIsFarLongerThanAskedIsCut() throws Exception {
        // The summarizer is asked for 30 words and copies 2000 characters of the result.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                        .summaries("Round 1: " + repeat("n", 2000));
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // Three tokens for each word asked are kept of a line: 90 here, 360 characters.
        // The marker of the cut is on the line: the note of a round stays one line.
        String line = repeat("n", 163) + " ...[1675 characters truncated]... " + repeat("n", 162);
        assertThat(((TextBlock) model.requests.get(2).get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, line));
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("summary_chars", "Round 1: ".length() + 360);
    }

    @Test
    void notesThatAreFullTakeNoMoreRounds() throws Exception {
        // Every line is as long as a line may be, and the notes may take half of the budget of
        // 1000 tokens: four lines of 92 tokens fit beside the heading.
        ScriptedModel model =
                new ScriptedModel(
                        callTool("c1"),
                        callTool("c2"),
                        callTool("c3"),
                        callTool("c4"),
                        callTool("c5"),
                        callTool("c6"),
                        callTool("c7"),
                        callTool("c8"));
        String longLine = repeat("n", 400);
        model.summaries(longLine, longLine, longLine, longLine, longLine, longLine);
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        for (int number = 1; number <= 7; number++) {
            toolResult(ctx, "c" + number, repeat(String.valueOf(number), 2000));
        }

        // Rounds 1 to 4 are in the notes. A fifth line of the most tokens a line can take would
        // not fit, so the summarizer is not asked about round 5, which stays in the input with
        // the rounds after it.
        List<Map<String, Object>> summaries =
                events(ctx).stream()
                        .map(ContextManagementEvent::getDetails)
                        .collect(Collectors.toList());
        assertThat(summaries)
                .extracting(details -> details.get("summarized_rounds"))
                .containsExactly(1, 2, 3, 4, 4, 4);
        assertThat(summaries)
                .extracting(details -> details.get("rounds"))
                .containsExactly(1, 1, 1, 1, 0, 0);
        assertThat(model.summaryRequests).hasSize(4);
        assertThat(summaries.get(5))
                .containsEntry(
                        "error",
                        "the summary is full: it takes 446 of the 500 estimated tokens it may"
                                + " take with max_input_tokens=1000, which leaves no room for the"
                                + " line of another round");
        assertThat(state(ctx, request.getId())).containsEntry("written_rounds", 4);
        assertThat(model.requests.get(7)).hasSize(8);
    }

    @Test
    void aRoundInAScriptThatTakesATokenForEachCharacterIsCutToFit() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final")).summaries("notes on round 1");
        // A budget that leaves a summarizer request 155 tokens for rounds.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 460)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // Round 1 has 150 characters that are a token each: with its call it takes about 160
        // tokens.
        List<ChatMessage> history =
                List.of(
                        ChatMessage.user("go"),
                        callTool("h1").getMessage(),
                        result("h1", repeat("\u8d26", 150)),
                        callTool("h2").getMessage(),
                        result("h2", repeat("2", 1200)));

        send(ctx, new ChatRequestEvent(MODEL, history));

        // Four characters for a token of room would be the whole round, which does not fit;
        // the longest cut that fits keeps most of it.
        assertThat(model.summaryRequests).hasSize(1);
        String asked = model.summaryRequests.get(0).get(1).getText();
        assertThat(asked)
                .contains("Round 1:\nlookup({\"q\":\"h1\"}) returned: \u8d26")
                .contains("characters truncated");
        assertThat(asked.chars().filter(c -> c == '\u8d26').count()).isGreaterThan(100);
        assertThat(approximate(model.summaryRequests.get(0))).isLessThanOrEqualTo(460);
        assertThat(events(ctx).get(0).getDetails())
                .doesNotContainKey("error")
                .containsEntry("rounds", 1);
    }

    // ── Token estimates ────────────────────────────────────────────────────

    @Test
    void anApproximateEstimateIsCorrectedByTheInputSizeTheProviderReported() throws Exception {
        // The provider reports three times the approximate estimate, so the trigger of 3200 is
        // crossed at about 1560 estimated tokens.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3"), callTool("c4"))
                        .reportingUsageTimes(3);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 4000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 2000));
        // Nothing was estimated for the call with one round, so there is nothing to compare.
        assertThat(state(ctx, request.getId())).isNull();
        // Two rounds, about 1040 estimated tokens: the first estimate, not corrected yet.
        toolResult(ctx, "c2", repeat("2", 2000));
        assertThat(model.summaryRequests).isEmpty();
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 3.0);
        toolResult(ctx, "c3", repeat("3", 2000));

        assertThat(model.summaryRequests).isNotEmpty();
        long threeRounds = approximate(stored(ctx, request.getId()).subList(0, 8));
        assertThat(threeRounds).isLessThan(3200L);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("estimated_input_tokens_before", 3 * threeRounds)
                .containsEntry("estimate_approximate", true);
    }

    @Test
    void aReportedInputSizeBelowTheEstimateDoesNotLowerIt() throws Exception {
        // A provider that reports cached input tokens separately can report a small input size
        // for a large request. Taken at face value it would hold the estimate of three rounds
        // at a fraction of its 1550 tokens and the trigger of 1200 would not be reached.
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                ChatFixtures.response("final"))
                        .reportingUsageOf(10L);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1500)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 2000));
        // Two rounds are 1037 estimated tokens, under the trigger, and 10 reported ones.
        toolResult(ctx, "c2", repeat("2", 2000));
        assertThat(model.summaryRequests).isEmpty();
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 1.0);
        toolResult(ctx, "c3", repeat("3", 2000));

        assertThat(model.summaryRequests).isNotEmpty();
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("estimated_input_tokens_before", 1550L);
    }

    @Test
    void anExactEstimateIsUsedAsItIs() throws Exception {
        // The same request and reported usage as above, but the chat model counts tokens itself:
        // about 1040 tokens stay under the trigger of 1600.
        ExactModel model =
                new ExactModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        model.reportingUsageTimes(3);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 2000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 2000));
        // An exact estimate needs no correction, so none is kept.
        assertThat(state(ctx, request.getId())).isNull();
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(model.summaryRequests).isEmpty();
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
    }

    @Test
    void aLaterSmallerReportDoesNotTakeBackTheCorrection() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                        callTool("c1"),
                        callTool("c2"),
                        callTool("c3"),
                        callTool("c4"),
                        ChatFixtures.response("final")) {
                    @Override
                    public ChatResult chat(
                            List<ChatMessage> messages,
                            Map<String, Object> promptArgs,
                            Map<String, Object> modelParams) {
                        // The first call whose input is estimated, the one with two rounds,
                        // is billed in full, three times the estimate. The later ones report
                        // 10 input tokens, as a provider does that counts the input it served
                        // from its cache apart.
                        if (requests.size() >= 3) {
                            reportingUsageOf(10L);
                        }
                        return super.chat(messages, promptArgs, modelParams);
                    }
                };
        model.reportingUsageTimes(3);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 4000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));
        assertThat(model.summaryRequests).isEmpty();
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 3.0);
        // Three rounds, about 1560 estimated tokens, count three times and cross the trigger
        // of 3200: rounds 1 and 2 are summarized.
        toolResult(ctx, "c3", repeat("3", 2000));
        int summaryRequests = model.summaryRequests.size();
        assertThat(summaryRequests).isPositive();
        // The call that followed reported 10 tokens. The factor of 3 stays.
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 3.0);

        // So the summary and two rounds, about 1090 estimated tokens, still count three times
        // and cross the trigger again.
        toolResult(ctx, "c4", repeat("4", 2000));
        assertThat(approximate(model.requests.get(3))).isLessThan(3200L);
        assertThat(model.summaryRequests).hasSize(summaryRequests + 1);
    }

    @Test
    void theCorrectionComesFromTheCallWithTheLargestReportedInput() throws Exception {
        // The provider counts 2000 tokens the estimate does not cover, a prompt and tools bound
        // on the other language's side for example.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3"), callTool("c4"))
                        .reportingUsagePlus(2000);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 500));
        toolResult(ctx, "c2", repeat("2", 500));
        // Two rounds: about 290 estimated and 2290 reported tokens, which is the most the
        // factor can be.
        assertThat(approximate(model.requests.get(2))).isLessThan(500L);
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 4.0);

        // Three rounds, the third a large one: the fixed part weighs less in a larger request,
        // and the factor follows it down.
        toolResult(ctx, "c3", repeat("3", 4000));
        long estimated = approximate(model.requests.get(3));
        Map<String, Object> state = state(ctx, request.getId());
        assertThat(state)
                .containsEntry("calibration", (double) (estimated + 2000) / estimated)
                .containsEntry("calibration_tokens", estimated + 2000);
        assertThat((Double) state.get("calibration")).isLessThan(3.0);
    }

    @Test
    void aShorterInputWithALargerFactorRaisesTheCorrection() throws Exception {
        // The provider counts 6000 tokens the estimate does not cover (see above). Two large
        // results cross the trigger of 8000 at once, so the factor is first learned from a
        // request in which that fixed part weighs little.
        Object[] calls = new Object[13];
        for (int number = 1; number <= 13; number++) {
            calls[number - 1] = callTool("c" + number);
        }
        ScriptedModel model = new ScriptedModel(calls).reportingUsagePlus(6000);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 10_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 80_000));
        toolResult(ctx, "c2", repeat("2", 80_000));
        Map<String, Object> state = state(ctx, request.getId());
        long large = approximate(model.requests.get(2)) + 6000;
        assertThat(large).isGreaterThan(26_000L);
        assertThat(state).containsEntry("calibration_tokens", large);
        assertThat((Double) state.get("calibration")).isBetween(1.29, 1.31);

        // With both large rounds summarized the input is short, and most of what the provider
        // counts is the fixed part. The call reports less than the one before and more than
        // four times its estimate: its factor is the larger one now, and the most a factor can
        // be. The largest reported input is still that of the large call, with its factor.
        toolResult(ctx, "c3", repeat("3", 1200));
        long shortInput = approximate(model.requests.get(3)) + 6000;
        assertThat(shortInput).isLessThan(7000L);
        assertThat(state(ctx, request.getId()))
                .containsEntry("calibration", 4.0)
                .containsEntry("calibration_tokens", large)
                .containsEntry("calibration_at_largest", state.get("calibration"));
        int summaryRequests = model.summaryRequests.size();

        // The factor of the large request, 1.3, would hold the estimates of the rounds that
        // follow under the trigger until the reported input is half over it, and over the
        // budget. With the factor of the short request a summary is written when the reported
        // input comes to the trigger.
        for (int number = 4; number <= 12; number++) {
            toolResult(ctx, "c" + number, repeat(String.valueOf(number % 10), 1200));
        }
        assertThat(model.summaryRequests.size()).isGreaterThan(summaryRequests);
        for (List<ChatMessage> sent : model.requests.subList(3, model.requests.size())) {
            assertThat(approximate(sent) + 6000).isLessThanOrEqualTo(8000L);
        }
        // None of these calls reported more than the large one did.
        assertThat(state(ctx, request.getId()))
                .containsEntry("calibration", 4.0)
                .containsEntry("calibration_tokens", large);
    }

    @Test
    void aReportThatIsNotTheLargestDoesNotLowerTheCorrection() throws Exception {
        // As above: the large rounds give a factor of about 1.3, and the short input that their
        // summaries leave gives one of 4.
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                callTool("c4"),
                                callTool("c5"))
                        .reportingUsagePlus(6000);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 10_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 80_000));
        toolResult(ctx, "c2", repeat("2", 80_000));
        toolResult(ctx, "c3", repeat("3", 1200));
        Map<String, Object> state = state(ctx, request.getId());
        assertThat(state).containsEntry("calibration", 4.0);
        Double atLargest = (Double) state.get("calibration_at_largest");
        assertThat(atLargest).isBetween(1.29, 1.31);

        // From here the provider reports the size of the request and no more, as one does that
        // counts the input it served from its cache apart. The next call reports more than the
        // short one did and less than the large one, with a factor of 1. It does not lower the
        // correction below that of the large call.
        model.reportingUsagePlus(0).reportingUsageTimes(1);
        toolResult(ctx, "c4", repeat("4", 40_000));
        long reported = approximate(model.requests.get(4));
        state = state(ctx, request.getId());
        assertThat(reported)
                .isGreaterThan(approximate(model.requests.get(3)) + 6000)
                .isLessThan((Long) state.get("calibration_tokens"));
        assertThat(state).containsEntry("calibration", atLargest);
    }

    @Test
    void theLaterOfTwoCallsThatReportTheSameInputGivesTheFactor() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3"), callTool("c4"))
                        .reportingUsageOf(5000L);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 4000));
        toolResult(ctx, "c2", repeat("2", 4000));
        long first = approximate(model.requests.get(2));
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 5000.0 / first);

        // The same reported input for a longer request: the later call is the one with the
        // largest reported input, and its factor is the smaller one.
        toolResult(ctx, "c3", repeat("3", 4000));
        long second = approximate(model.requests.get(3));
        assertThat(second).isGreaterThan(first);
        assertThat(state(ctx, request.getId()))
                .containsEntry("calibration", 5000.0 / second)
                .containsEntry("calibration_tokens", 5000L)
                .containsEntry("calibration_at_largest", 5000.0 / second);
    }

    @Test
    void theInputSizeAFallbackCandidateReportsDoesNotCorrectTheSelectedModelsEstimate()
            throws Exception {
        ScriptedModel small = new ScriptedModel(new IllegalStateException("small is down"));
        ScriptedModel big =
                new ScriptedModel(callTool("c2"), callTool("c3")).reportingUsageTimes(3);
        Context ctx =
                new Context(router(true))
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register("small", small);
        ctx.register("big", big);
        ChatRequestEvent request =
                new ChatRequestEvent(
                        "router",
                        List.of(
                                ChatMessage.user("go"),
                                callTool("h1").getMessage(),
                                ChatMessage.tool(
                                        new ToolResultBlock(
                                                "h1", List.of(new TextBlock("result")), false)),
                                callTool("h2").getMessage(),
                                ChatMessage.tool(
                                        new ToolResultBlock(
                                                "h2", List.of(new TextBlock("result")), false))));

        send(ctx, request);
        // The request came with two rounds, so its input was estimated, for small. What big
        // reports for the same messages is no measure of it, so nothing is kept.
        assertThat(ctx.estimates()).hasSize(1);
        assertThat(big.requests).hasSize(1);
        assertThat(state(ctx, request.getId())).isNull();

        // The tool round stays on big, and what big reports now corrects its own estimate.
        toolResult(ctx, "c2", "result");
        assertThat(big.requests).hasSize(2);
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 3.0);
    }

    @Test
    void anEstimateTheChatModelCannotGiveStillCountsItsBoundPrompt() throws Exception {
        // This chat model has no connection to estimate with, and 1600 characters of
        // instructions in its prompt: about 400 tokens the request's own messages do not show.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3")) {
                    @Override
                    public Object getPrompt() {
                        return Prompt.fromMessages(List.of(ChatMessage.system(repeat("i", 1600))));
                    }
                };
        model.summaries("notes on round 1");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request = new ChatRequestEvent(MODEL, List.of(ChatMessage.user("go")));

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 1000));
        toolResult(ctx, "c2", repeat("2", 1000));

        // Two rounds are about 530 tokens, under the trigger of 800. With the prompt the model
        // is sent about 930, so round 1 is summarized.
        List<ChatMessage> twoRounds = stored(ctx, request.getId()).subList(0, 5);
        assertThat(approximate(twoRounds)).isLessThan(800L);
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry(
                        "estimated_input_tokens_before",
                        approximate(model.prepareRequestMessages(twoRounds, Map.of())))
                .containsEntry("estimate_approximate", true);
    }

    // ── Lines that are held, and the recorded estimate ─────────────────────

    @Test
    void linesThatDoNotMakeTheInputSmallerAreHeldUntilTheyDo() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                callTool("c4"),
                                callTool("c5"))
                        .summaries("ok", "ok", "ok");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // The request alone takes about 900 of the 1000 tokens of the budget, so every call is
        // over the trigger, and the first two rounds are a few tokens each.
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        toolResult(ctx, "c1", "1");
        assertThat(model.summaryRequests).isEmpty();
        toolResult(ctx, "c2", "2");

        // Round 1 is due and gets its line. The heading and the line take more than the round
        // they would replace, so the model is still sent the round, and the line is held.
        assertThat(model.summaryRequests).hasSize(1);
        List<ChatMessage> third = model.requests.get(2);
        assertThat(third).hasSize(5);
        assertThat(third.get(0).getBlocks()).hasSize(1);
        assertThat(toolText(third.get(2))).isEqualTo("1");
        assertThat(state(ctx, request.getId()))
                .containsEntry("pending_summary", "Round 1: ok")
                .containsEntry("written_rounds", 1)
                .doesNotContainKey("summary");
        assertThat(events(ctx)).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 0)
                .containsEntry("pending_rounds", 1)
                .containsEntry("estimated_input_tokens_before", approximate(third))
                .doesNotContainKeys("summary_chars", "estimated_input_tokens_after", "error");

        // Round 2 is due next. Round 1 has its line and is not asked about again, and two
        // lines are still longer than two short rounds.
        toolResult(ctx, "c3", repeat("3", 600));
        assertThat(model.summaryRequests).hasSize(2);
        assertThat(model.summaryRequests.get(1).get(1).getText())
                .contains("Round 2:\n")
                .doesNotContain("Round 1:");
        assertThat(model.requests.get(3)).hasSize(7);
        assertThat(model.requests.get(3).get(0).getBlocks()).hasSize(1);
        assertThat(state(ctx, request.getId()))
                .containsEntry("pending_summary", "Round 1: ok\nRound 2: ok")
                .containsEntry("written_rounds", 2)
                .doesNotContainKey("summary");
        assertThat(events(ctx).get(1).getDetails())
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 0)
                .containsEntry("pending_rounds", 2);

        // Round 3 holds 600 characters. With its line, the three lines are shorter than the
        // three rounds, and the model is sent them from this call on.
        toolResult(ctx, "c4", "4");
        assertThat(model.summaryRequests).hasSize(3);
        assertThat(model.summaryRequests.get(2).get(1).getText())
                .contains("Round 3:\n")
                .doesNotContain("Round 1:")
                .doesNotContain("Round 2:");
        List<ChatMessage> fifth = model.requests.get(4);
        assertThat(fifth).hasSize(3);
        assertThat(((TextBlock) fifth.get(0).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(3, "ok"));
        assertThat(fifth.get(1).getToolCalls().get(0).getCallId()).isEqualTo("c4");
        assertThat(state(ctx, request.getId()))
                .containsEntry("summary", "Round 1: ok\nRound 2: ok\nRound 3: ok")
                .containsEntry("written_rounds", 3)
                .doesNotContainKey("pending_summary");
        assertThat(events(ctx).get(2).getDetails())
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 3)
                .containsEntry("summary_chars", "Round 1: ok\nRound 2: ok\nRound 3: ok".length())
                .containsEntry("estimated_input_tokens_after", approximate(fifth))
                .doesNotContainKey("pending_rounds");
        assertThat(approximate(fifth)).isLessThan(approximate(model.requests.get(3)));

        // The two inputs are compared by the size of their messages, which takes no call.
        assertThat(ctx.durableCallIds)
                .containsExactly(
                        "chat",
                        "chat",
                        "estimate",
                        "summarize",
                        "chat",
                        "estimate",
                        "summarize",
                        "chat",
                        "estimate",
                        "summarize",
                        "chat");
    }

    @Test
    void linesAreUsedExactlyWhenTheMessagesAreSmallerWithThem() throws Exception {
        // One round with a result of a given size, a second round, and the line "ok" for the
        // first. Around the size at which the heading and the line take what the round takes,
        // the model is sent the line exactly when the messages are smaller with it.
        boolean smaller = false;
        boolean equal = false;
        boolean larger = false;
        for (int chars = 250; chars <= 360; chars++) {
            ScriptedModel model =
                    new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3"))
                            .summaries("ok");
            // A trigger of 10 tokens: every call with a round is over it.
            Context ctx =
                    summarizing()
                            .with(
                                    AgentExecutionOptions
                                            .CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO,
                                    0.01);
            ctx.register(MODEL, model);
            ChatRequestEvent request = request();

            send(ctx, request);
            toolResult(ctx, "c1", repeat("1", chars));
            toolResult(ctx, "c2", "2");

            List<ChatMessage> stored = stored(ctx, request.getId()).subList(0, 6);
            List<ChatMessage> withLine =
                    List.of(
                            stored.get(0),
                            ChatMessage.user(
                                    List.of(
                                            new TextBlock("go"),
                                            new TextBlock(summaryBlock(1, "ok")))),
                            stored.get(4),
                            stored.get(5));
            long without = approximate(stored);
            long with = approximate(withLine);
            List<ChatMessage> third = model.requests.get(2);
            assertThat(third.size() == 4)
                    .as(
                            "a result of %d characters: %d tokens with the line, %d without",
                            chars, with, without)
                    .isEqualTo(with < without);
            assertThat(approximate(third)).isEqualTo(Math.min(with, without));
            smaller |= with < without;
            equal |= with == without;
            larger |= with > without;
        }
        assertThat(smaller).isTrue();
        assertThat(equal).isTrue();
        assertThat(larger).isTrue();
    }

    @Test
    void aRoundWithAMediaResultIsNotSummarized() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final"))
                        .summaries("notes on round 1", "notes on round 3");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatMessage chart =
                ChatMessage.tool(
                        new ToolResultBlock(
                                "h2",
                                List.of(
                                        new TextBlock("the chart of account 2"),
                                        ImageBlock.fromUrl("image/png", "https://example.org/2")),
                                false));
        List<ChatMessage> history =
                List.of(
                        ChatMessage.user("go"),
                        callTool("h1").getMessage(),
                        result("h1", repeat("1", 2000)),
                        callTool("h2").getMessage(),
                        chart,
                        callTool("h3").getMessage(),
                        result("h3", repeat("3", 2000)),
                        callTool("h4").getMessage(),
                        result("h4", "4"));

        send(ctx, new ChatRequestEvent(MODEL, history));

        // A summarizer is shown text only. Round 2 returned an image, so it is not shown to
        // the summarizer and stays in the model's input as it is, after the summary.
        assertThat(model.summaryRequests).hasSize(2);
        assertThat(model.summaryRequests)
                .allSatisfy(
                        request ->
                                assertThat(request.get(1).getText())
                                        .doesNotContain("Round 2:")
                                        .doesNotContain("the chart"));
        List<ChatMessage> sent = model.requests.get(0);
        assertThat(sent).hasSize(5);
        assertThat(((TextBlock) sent.get(0).getBlocks().get(1)).getText())
                .isEqualTo(
                        ContextCompaction.SUMMARY_SEPARATOR
                                + ContextCompaction.summaryText(
                                        "Round 1: notes on round 1\nRound 3: notes on round 3"));
        assertThat(sent.get(2)).isSameAs(chart);
        assertThat(sent.get(3).getToolCalls().get(0).getCallId()).isEqualTo("h4");
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 2)
                .containsEntry("summarized_rounds", 2)
                .doesNotContainKey("pending_rounds");
    }

    @Test
    void theInputIsEstimatedOnceForAModelCallAlsoWhenASummaryIsWritten() throws Exception {
        int[] asked = new int[1];
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final")) {
                    @Override
                    public TokenEstimate estimateInputTokens(
                            List<ChatMessage> messages, Map<String, Object> promptArgs) {
                        asked[0]++;
                        return super.estimateInputTokens(messages, promptArgs);
                    }
                };
        model.summaries("notes on round 1");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        // With one round none is due for a line, so nothing is estimated.
        assertThat(asked[0]).isZero();
        toolResult(ctx, "c2", repeat("2", 2000));

        // The call with two rounds wrote a summary and sent it. A count of the provider's own
        // is asked for once: the input with the new line is not counted a second time.
        assertThat(model.requests.get(2)).hasSize(4);
        assertThat(asked[0]).isEqualTo(1);
    }

    @Test
    void aSummarizerRequestIsSizedWithTheCorrectionOfTheEstimates() throws Exception {
        // The provider reports twice the approximate estimate.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3"), callTool("c4"))
                        .reportingUsageTimes(2);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 2000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 4800));
        // Two rounds, about 1480 estimated tokens: under the trigger of 1600 as long as the
        // estimate is not corrected.
        toolResult(ctx, "c2", repeat("2", 1000));
        assertThat(model.summaryRequests).isEmpty();
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 2.0);
        toolResult(ctx, "c3", repeat("3", 1000));

        // Round 1 is about 1200 tokens by the approximation, which the provider counts as
        // 2400. The request to the summarizer may take 2000 of the provider's tokens, so it is
        // put together within 1000 of the approximation, and round 1 is cut to fit.
        assertThat(model.summaryRequests).isNotEmpty();
        assertThat(approximate(model.summaryRequests.get(0))).isLessThanOrEqualTo(1000);
        assertThat(model.summaryRequests.get(0).get(1).getText()).contains("characters truncated");
        assertThat(events(ctx).get(0).getDetails()).doesNotContainKey("error");
    }

    @Test
    void whatTheSummarizerIsToldAboutTheRequestIsCutWithTheCorrectionToo() throws Exception {
        // The provider reports four times the approximate estimate.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3"), callTool("c4"))
                        .reportingUsageTimes(4);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 4000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        // Instructions and a request of 400 tokens each.
        ChatRequestEvent request =
                new ChatRequestEvent(
                        MODEL,
                        List.of(
                                ChatMessage.system(repeat("s", 1600)),
                                ChatMessage.user(repeat("u", 1600))));

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 400));
        toolResult(ctx, "c2", repeat("2", 400));
        assertThat(model.summaryRequests).isEmpty();
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 4.0);
        toolResult(ctx, "c3", repeat("3", 400));

        // A summarizer request may take 4000 of the provider's tokens, which are 1000 of the
        // approximation. The instructions and the request text are each cut to an eighth of
        // that, so the rounds still fit beside them.
        assertThat(model.summaryRequests).isNotEmpty();
        assertThat(model.summaryRequests)
                .allSatisfy(asked -> assertThat(approximate(asked)).isLessThanOrEqualTo(1000));
        String asked = model.summaryRequests.get(0).get(1).getText();
        assertThat(asked).contains("characters truncated").contains("Round 1:\n");
        assertThat(events(ctx).get(0).getDetails()).doesNotContainKey("error");
    }

    @Test
    void anEstimateTheChatModelDoesNotReturnIsReplacedByTheApproximation() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final")) {
                    @Override
                    public TokenEstimate estimateInputTokens(
                            List<ChatMessage> messages, Map<String, Object> promptArgs) {
                        return null;
                    }
                };
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", "1");
        toolResult(ctx, "c2", "2");

        // No count is not a failure of the request: the size of the request is used.
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        assertThat(ctx.estimates()).hasSize(1);
        assertThat(ctx.estimates().get(0).tokens).isEqualTo(approximate(model.requests.get(2)));
        assertThat(ctx.estimates().get(0).approximate).isTrue();
    }

    @Test
    void theSummarizerIsShownTheInstructionsTheChatModelGivesItsProvider() throws Exception {
        // A chat model that gives its provider instructions as a parameter, not as a message.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final")) {
                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("instructions", "Audit the accounts.");
                    }
                };
        model.summaries("notes on round 1");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(model.summaryRequests).hasSize(1);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .startsWith(
                        "The agent's instructions, for context only:\n"
                                + "Audit the accounts.\nYou are terse.\n\n"
                                + "The request the agent is working on, for context only:\ngo\n\n");
        assertThat(ctx.estimates().get(0).instructions)
                .isEqualTo("Audit the accounts.\nYou are terse.");
    }

    @Test
    void instructionsGivenToTheProviderInsideAdditionalKwargsAreShownToTheSummarizer()
            throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final")) {
                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of(
                                "additional_kwargs", Map.of("instructions", "Audit the accounts."));
                    }
                };
        model.summaries("notes on round 1");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(model.summaryRequests).hasSize(1);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .startsWith(
                        "The agent's instructions, for context only:\n"
                                + "Audit the accounts.\nYou are terse.\n\n");
        assertThat(ctx.estimates().get(0).instructions)
                .isEqualTo("Audit the accounts.\nYou are terse.");
    }

    @Test
    void anEstimateTheChatModelCannotGiveStillCountsTheInstructionsItGivesItsProvider()
            throws Exception {
        // This chat model has no connection to estimate with, and gives its provider 1600
        // characters of instructions as a parameter: about 400 tokens the messages do not show.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3")) {
                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("instructions", repeat("i", 1600));
                    }
                };
        model.summaries("notes on round 1");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request = new ChatRequestEvent(MODEL, List.of(ChatMessage.user("go")));

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 1000));
        toolResult(ctx, "c2", repeat("2", 1000));

        // Two rounds are about 530 tokens, under the trigger of 800. With the instructions the
        // provider is sent about 930, so round 1 is summarized.
        List<ChatMessage> twoRounds = stored(ctx, request.getId()).subList(0, 5);
        assertThat(approximate(twoRounds)).isLessThan(800L);
        assertThat(model.summaryRequests).hasSize(1);
        List<ChatMessage> counted = new ArrayList<>();
        counted.add(ChatMessage.system(repeat("i", 1600)));
        counted.addAll(twoRounds);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("estimated_input_tokens_before", approximate(counted));
    }

    @Test
    void aFaultOfTheFrameworkIsNotTakenForAFailedSummaryOrAMissingEstimate() throws Exception {
        RuntimeException fault =
                UncapturableException.wrap(new IllegalStateException("the framework failed"));
        // In a summarizer call.
        ScriptedModel summarizer =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                        .summaries(fault);
        Context ctx = summarizing();
        ctx.register(MODEL, summarizer);
        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));

        assertThatThrownBy(() -> toolResult(ctx, "c2", repeat("2", 2000))).isSameAs(fault);
        // Nothing is reported as a summary that could not be written, and no model call
        // follows.
        assertThat(events(ctx)).isEmpty();
        assertThat(summarizer.requests).hasSize(2);

        // In an estimate.
        ScriptedModel estimating =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final")) {
                    @Override
                    public TokenEstimate estimateInputTokens(
                            List<ChatMessage> messages, Map<String, Object> promptArgs) {
                        throw fault;
                    }
                };
        Context other = summarizing();
        other.register(MODEL, estimating);
        send(other, request());
        toolResult(other, "c1", repeat("1", 2000));

        assertThatThrownBy(() -> toolResult(other, "c2", repeat("2", 2000))).isSameAs(fault);
        assertThat(estimating.requests).hasSize(2);
    }

    @Test
    void toolsThatCannotBeMeasuredAreLeftOutOfTheFallbackEstimate() throws Exception {
        // A chat model that cannot estimate its input, and whose tools cannot be read.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3")) {
                    @Override
                    public TokenEstimate estimateInputTokens(
                            List<ChatMessage> messages, Map<String, Object> promptArgs) {
                        throw new IllegalStateException("no estimate");
                    }

                    @Override
                    public List<Tool> getTools() {
                        throw new IllegalStateException("no tools");
                    }
                };
        model.summaries("notes on round 1");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // The approximation is used. It counts the request without the tools, and the request
        // goes on.
        assertThat(ctx.estimates().get(0).tokens)
                .isEqualTo(approximate(stored(ctx, request.getId()).subList(0, 6)));
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(state(ctx, request.getId()))
                .containsEntry("summary", "Round 1: notes on round 1");
    }

    @Test
    void aBudgetWithoutRoomForOneLineSaysSo() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        // Half of the budget is 170 tokens. The heading of the summary takes 77 of them and a
        // line may take 95.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 340)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // No line was written yet, so the summary is not full: the budget is too small.
        assertThat(model.summaryRequests).isEmpty();
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry(
                        "error",
                        "the summary has no room for a line: it may take 170 estimated tokens"
                                + " with max_input_tokens=340, and its heading takes 77 of them");
    }

    @Test
    void notesThatAreFullFailTheStepBeforeARoundIsRendered() throws Exception {
        // A budget that no summarizer request fits, and notes that have no room for a line:
        // with their heading they take more than the 150 tokens they may take.
        ScriptedModel model = new ScriptedModel(ChatFixtures.response("final"));
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 300)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        List<ChatMessage> history =
                List.of(
                        ChatMessage.user("go"),
                        callTool("h1").getMessage(),
                        result("h1", "1"),
                        callTool("h2").getMessage(),
                        result("h2", repeat("2", 2000)),
                        callTool("h3").getMessage(),
                        result("h3", repeat("3", 2000)));
        ChatRequestEvent request = new ChatRequestEvent(MODEL, history);
        Map<String, Object> written = new HashMap<>();
        written.put("summary", "Round 1: " + repeat("n", 391));
        written.put("written_rounds", 1);
        ctx.getSensoryMemory().set(ContextManager.STATE_KEY + "." + request.getId(), written);

        send(ctx, request);

        // Round 2 is due. Without room for its line nothing is put together for the summarizer,
        // so the step fails for the notes and not for a round that fits no request.
        assertThat(model.summaryRequests).isEmpty();
        assertThat((String) events(ctx).get(0).getDetails().get("error"))
                .startsWith("the summary is full: it takes 177 of the 150 estimated tokens");
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 0)
                .containsEntry("summarized_rounds", 1)
                .doesNotContainKey("pending_rounds");
    }

    @Test
    void linesThatAreHeldAreGivenUpWhenTheNotesHaveNoRoom() throws Exception {
        // The summarizer writes lines as long as a line may be.
        String longLine = repeat("n", 400);
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                callTool("c4"),
                                callTool("c5"),
                                callTool("c6"),
                                callTool("c7"))
                        .summaries(longLine, longLine, longLine, longLine, longLine);
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // The request alone takes about 900 of the 1000 tokens of the budget, so every call
        // with a round that is due is over the trigger.
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        // Four short rounds. Their lines are longer than they are, so the lines are held, and
        // with them the notes have no room for another line.
        for (int number = 1; number <= 4; number++) {
            toolResult(ctx, "c" + number, "ok");
        }
        toolResult(ctx, "c5", repeat("5", 10_000));
        Map<String, Object> state = state(ctx, request.getId());
        assertThat(((String) state.get("pending_summary")).split("\n")).hasSize(4);
        assertThat(state).doesNotContainKey("summary");

        // Round 5 is long and due. The lines that are held were never sent: they are given up,
        // so the line of round 5 has room, and their rounds stay in the messages.
        toolResult(ctx, "c6", "ok");
        Map<String, Object> details = events(ctx).get(events(ctx).size() - 1).getDetails();
        assertThat(details)
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 1)
                .doesNotContainKeys("error", "pending_rounds");
        state = state(ctx, request.getId());
        assertThat((String) state.get("summary")).startsWith("Round 5: ").doesNotContain("\n");
        assertThat(state).containsEntry("written_rounds", 5).doesNotContainKey("pending_summary");
        List<ChatMessage> sent = model.requests.get(6);
        assertThat(sent).hasSize(1 + 4 * 2 + 2);
        for (int index = 2; index <= 8; index += 2) {
            assertThat(toolText(sent.get(index))).isEqualTo("ok");
        }
        assertThat(sent.get(9).getToolCalls().get(0).getCallId()).isEqualTo("c6");
    }

    @Test
    void linesThatAreGivenUpAreGoneWhenTheStepFailsAfterThat() throws Exception {
        // The summarizer writes lines as long as a line may be, and then it is down.
        String longLine = repeat("n", 400);
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                callTool("c4"),
                                callTool("c5"),
                                callTool("c6"),
                                callTool("c7"))
                        .summaries(
                                longLine,
                                longLine,
                                longLine,
                                longLine,
                                new IllegalStateException("down"));
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        // Four short rounds, whose lines are held and leave the notes no room.
        for (int number = 1; number <= 4; number++) {
            toolResult(ctx, "c" + number, "ok");
        }
        toolResult(ctx, "c5", repeat("5", 10_000));
        assertThat(((String) state(ctx, request.getId()).get("pending_summary")).split("\n"))
                .hasSize(4);

        // Round 5 is due. The lines that are held are given up for the room of its line, and
        // the summarizer does not write it.
        toolResult(ctx, "c6", "ok");
        Map<String, Object> details = events(ctx).get(events(ctx).size() - 1).getDetails();
        assertThat((String) details.get("error")).contains("down");
        assertThat(details).containsEntry("rounds", 0).doesNotContainKey("pending_rounds");
        assertThat(state(ctx, request.getId()))
                .containsEntry("written_rounds", 4)
                .doesNotContainKeys("pending_summary", "summary");
    }

    @Test
    void linesWrittenInAStepStayWhenTheNotesHaveNoRoomForMore() throws Exception {
        // The summarizer writes lines as long as a line may be.
        String longLine = repeat("n", 400);
        ScriptedModel model =
                new ScriptedModel(callTool("c7"))
                        .summaries(longLine, longLine, longLine, longLine, longLine);
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // Six rounds arrive with the request, and five of them are due at once. Each is a
        // summarizer request of its own.
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("go"));
        for (int number = 1; number <= 6; number++) {
            history.add(callTool("h" + number).getMessage());
            history.add(result("h" + number, repeat(String.valueOf(number), 2000)));
        }
        ChatRequestEvent request = new ChatRequestEvent(MODEL, history);

        send(ctx, request);

        // The notes have room for four lines. The step ends there: the four lines were written
        // in it, so they are kept, and the model is sent them.
        assertThat(model.summaryRequests).hasSize(4);
        Map<String, Object> details = events(ctx).get(0).getDetails();
        assertThat((String) details.get("error"))
                .startsWith("the summary is full: it takes 446 of the 500 estimated tokens");
        assertThat(details)
                .containsEntry("rounds", 4)
                .containsEntry("summarized_rounds", 4)
                .doesNotContainKey("pending_rounds");
        Map<String, Object> state = state(ctx, request.getId());
        String[] lines = ((String) state.get("summary")).split("\n");
        assertThat(lines).hasSize(4);
        for (int number = 1; number <= 4; number++) {
            assertThat(lines[number - 1]).startsWith("Round " + number + ": ");
        }
        assertThat(state).containsEntry("written_rounds", 4).doesNotContainKey("pending_summary");
        List<ChatMessage> sent = model.requests.get(0);
        assertThat(sent).hasSize(1 + 2 * 2);
        assertThat(toolText(sent.get(2))).isEqualTo(repeat("5", 2000));
        assertThat(toolText(sent.get(4))).isEqualTo(repeat("6", 2000));
    }

    @Test
    void onlyLinesThatAnEarlierStepLeftHeldAreGivenUpForRoom() throws Exception {
        // The summarizer writes lines as long as a line may be, and is down for one call.
        String longLine = repeat("n", 400);
        ScriptedModel model =
                new ScriptedModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                callTool("c4"),
                                callTool("c5"),
                                callTool("c6"),
                                callTool("c7"))
                        .summaries(
                                longLine,
                                longLine,
                                longLine,
                                new IllegalStateException("down"),
                                longLine,
                                longLine);
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // The request alone takes about 900 of the 1000 tokens of the budget, so every call
        // with a round that is due is over the trigger.
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        // Three short rounds. Their lines are longer than they are, so the lines are held, and
        // with them the notes have room for one more line.
        for (int number = 1; number <= 3; number++) {
            toolResult(ctx, "c" + number, "ok");
        }
        toolResult(ctx, "c4", repeat("4", 2000));
        assertThat(((String) state(ctx, request.getId()).get("pending_summary")).split("\n"))
                .hasSize(3);
        // The step for round 4 fails, so the next step finds two long rounds due.
        toolResult(ctx, "c5", repeat("5", 2000));
        assertThat((String) events(ctx).get(events(ctx).size() - 1).getDetails().get("error"))
                .contains("down");

        toolResult(ctx, "c6", "ok");

        // The line of round 4 takes the room that was left. For the line of round 5 the lines
        // of rounds 1 to 3 are given up, which an earlier step left held. The line of round 4
        // was written in this step and stays.
        assertThat(model.summaryRequests).hasSize(6);
        Map<String, Object> details = events(ctx).get(events(ctx).size() - 1).getDetails();
        assertThat(details)
                .containsEntry("rounds", 2)
                .containsEntry("summarized_rounds", 2)
                .doesNotContainKeys("error", "pending_rounds");
        Map<String, Object> state = state(ctx, request.getId());
        String[] lines = ((String) state.get("summary")).split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("Round 4: ");
        assertThat(lines[1]).startsWith("Round 5: ");
        assertThat(state).containsEntry("written_rounds", 5).doesNotContainKey("pending_summary");
        List<ChatMessage> sent = model.requests.get(6);
        assertThat(sent).hasSize(1 + 3 * 2 + 2);
        for (int index = 2; index <= 6; index += 2) {
            assertThat(toolText(sent.get(index))).isEqualTo("ok");
        }
        assertThat(sent.get(7).getToolCalls().get(0).getCallId()).isEqualTo("c6");
    }

    @Test
    void shortRoundsGetNoLineWhileHeldLinesFillTheNotes() throws Exception {
        // The summarizer writes lines as long as a line may be.
        String longLine = repeat("n", 400);
        Object[] calls = new Object[13];
        for (int number = 1; number <= 13; number++) {
            calls[number - 1] = callTool("c" + number);
        }
        ScriptedModel model =
                new ScriptedModel(calls)
                        .summaries(longLine, longLine, longLine, longLine, longLine);
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // The request alone takes about 900 of the 1000 tokens of the budget, so every call
        // with a round that is due is over the trigger.
        ChatRequestEvent request =
                new ChatRequestEvent(MODEL, List.of(ChatMessage.user(repeat("u", 3600))));

        send(ctx, request);
        // Four short rounds. Their lines are longer than they are, so the lines are held, and
        // with them the notes have no room for another line.
        for (int number = 1; number <= 5; number++) {
            toolResult(ctx, "c" + number, "ok");
        }
        assertThat(model.summaryRequests).hasSize(4);
        long estimates = ctx.durableCallIds.stream().filter("estimate"::equals).count();

        // More short rounds. A line for one of them would be held like the others, and asking
        // for it would give those up for nothing: the rounds get no line, and nothing is
        // estimated or asked for them.
        for (int number = 6; number <= 9; number++) {
            toolResult(ctx, "c" + number, "ok");
        }
        assertThat(model.summaryRequests).hasSize(4);
        assertThat(ctx.durableCallIds.stream().filter("estimate"::equals).count())
                .isEqualTo(estimates);
        Map<String, Object> state = state(ctx, request.getId());
        assertThat(state).containsEntry("written_rounds", 8);
        assertThat(((String) state.get("pending_summary")).split("\n")).hasSize(4);

        // A long round is due after them. The held lines are given up for its line.
        toolResult(ctx, "c10", repeat("x", 10_000));
        toolResult(ctx, "c11", "ok");
        assertThat(model.summaryRequests).hasSize(5);
        state = state(ctx, request.getId());
        assertThat((String) state.get("summary")).startsWith("Round 10: ").doesNotContain("\n");
        assertThat(state).doesNotContainKey("pending_summary");
    }

    @Test
    void aRoundThatStaysIsNotCountedAmongTheRoundsThatGotALine() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final"))
                        .summaries("notes on round 2", "notes on round 3");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // The first round of the request loads a skill; rounds 2 and 3 are due with it.
        List<ChatMessage> history =
                List.of(
                        ChatMessage.user("go"),
                        ChatMessage.assistant(
                                List.of(
                                        new ToolCallBlock(
                                                "s1", "load_skill", Map.of("name", "audit")))),
                        result("s1", repeat("i", 500)),
                        callTool("h2").getMessage(),
                        result("h2", repeat("2", 1500)),
                        callTool("h3").getMessage(),
                        result("h3", repeat("3", 1500)),
                        callTool("h4").getMessage(),
                        result("h4", "4"));

        send(ctx, new ChatRequestEvent(MODEL, history));

        // Three rounds were gone through. Two of them got a line, and only they are counted.
        assertThat(model.summaryRequests).hasSize(2);
        assertThat(events(ctx)).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 2)
                .containsEntry("summarized_rounds", 2);
        assertThat(model.requests.get(0)).hasSize(5);
    }

    @Test
    void roundsThatWerePassedOverAreNotReportedAsHeld() throws Exception {
        ChatResult loadSkill =
                new ChatResult(
                        ChatMessage.assistant(
                                List.of(
                                        new ToolCallBlock(
                                                "s1", "load_skill", Map.of("name", "audit")))));
        ScriptedModel model =
                new ScriptedModel(
                                loadSkill,
                                callTool("c2"),
                                callTool("c3"),
                                ChatFixtures.response("final"))
                        .summaries(new IllegalStateException("summarizer is down"));
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "s1", repeat("i", 2500));
        toolResult(ctx, "c2", repeat("2", 2000));
        toolResult(ctx, "c3", repeat("3", 2000));

        // Round 1 loads a skill and was passed over, and the line of round 2 could not be
        // written. No line is held, so no held round is reported.
        assertThat(events(ctx)).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 0)
                .containsEntry("summarized_rounds", 0)
                .containsKey("error")
                .doesNotContainKey("pending_rounds");
    }

    @Test
    void whatTheAgentWasAskedIsRecordedWithTheEstimateThatAsksForASummary() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                        callTool("c1"),
                        callTool("c2"),
                        callTool("c3"),
                        ChatFixtures.response("final"));
        // Summarize above 1200 tokens, keep the last round.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1500)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));
        toolResult(ctx, "c3", repeat("3", 2000));

        List<ContextManager.EstimateOutcome> estimates = ctx.estimates();
        // One estimate for a model call that has a round due, also for the call that a summary
        // is written for.
        assertThat(estimates).hasSize(2);
        // Two rounds, under the trigger: the number alone.
        assertThat(estimates.get(0).tokens).isEqualTo(approximate(model.requests.get(2)));
        assertThat(estimates.get(0).approximate).isTrue();
        assertThat(estimates.get(0).instructions).isNull();
        assertThat(estimates.get(0).request).isNull();
        // Three rounds, over the trigger: what the summarizer is told about the request is
        // recorded too, so a replay asks without rendering the prompt again.
        assertThat(estimates.get(1).tokens).isGreaterThan(1200L);
        assertThat(estimates.get(1).instructions).isEqualTo("You are terse.");
        assertThat(estimates.get(1).request).isEqualTo("go");
        // The input with the new lines is not estimated again: it is the estimate before, less
        // what the lines save.
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("estimated_input_tokens_before", estimates.get(1).tokens)
                .containsEntry("estimated_input_tokens_after", approximate(model.requests.get(3)));

        // The record is kept as JSON by the durable call.
        ObjectMapper mapper = new ObjectMapper();
        ContextManager.EstimateOutcome read =
                mapper.readValue(
                        mapper.writeValueAsString(estimates.get(1)),
                        ContextManager.EstimateOutcome.class);
        assertThat(read.tokens).isEqualTo(estimates.get(1).tokens);
        assertThat(read.approximate).isTrue();
        assertThat(read.instructions).isEqualTo("You are terse.");
        assertThat(read.request).isEqualTo("go");
    }

    @Test
    void aRecordedEstimateDecidesWithoutEstimatingOrRenderingThePromptAgain() throws Exception {
        // A chat model that counts how often it is asked for its request or an estimate of it.
        int[] asked = new int[1];
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final")) {
                    @Override
                    public TokenEstimate estimateInputTokens(
                            List<ChatMessage> messages, Map<String, Object> promptArgs) {
                        asked[0]++;
                        return super.estimateInputTokens(messages, promptArgs);
                    }

                    @Override
                    public List<ChatMessage> prepareRequestMessages(
                            List<ChatMessage> messages, Map<String, Object> promptArgs) {
                        asked[0]++;
                        return super.prepareRequestMessages(messages, promptArgs);
                    }
                };
        model.summaries("notes on round 1");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        // The first run estimated 900 tokens and recorded what the agent was asked. By its own
        // size the conversation of the replay is far below the trigger of 800.
        ContextManager.EstimateOutcome recorded = new ContextManager.EstimateOutcome();
        recorded.tokens = 900;
        recorded.approximate = true;
        recorded.instructions = "recorded instructions";
        recorded.request = "recorded request";
        ctx.durableStore.put(ContextManager.ESTIMATE_CALL_ID, recorded);

        send(ctx, request());
        toolResult(ctx, "c1", "1");
        toolResult(ctx, "c2", "2");

        // The decision follows the record: round 1 is due, and the summarizer is asked with
        // the recorded text.
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .startsWith(
                        "The agent's instructions, for context only:\nrecorded instructions\n\n"
                                + "The request the agent is working on, for context only:\n"
                                + "recorded request\n\n");
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("estimated_input_tokens_before", 900L);
        assertThat(asked[0]).isZero();
        // Round 1 is a few tokens, fewer than the heading and the line take, so the line is
        // held and the model is sent both rounds.
        assertThat(events(ctx).get(0).getDetails()).containsEntry("pending_rounds", 1);
        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(ctx.durableCallIds)
                .containsExactly("chat", "chat", "estimate", "summarize", "chat");
    }

    @Test
    void anEstimateIsMadeWhereItIsNotRecorded() throws Exception {
        // The counterpart of the test above: without a record the chat model is asked.
        int[] asked = new int[1];
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final")) {
                    @Override
                    public TokenEstimate estimateInputTokens(
                            List<ChatMessage> messages, Map<String, Object> promptArgs) {
                        asked[0]++;
                        return super.estimateInputTokens(messages, promptArgs);
                    }
                };
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", "1");
        // No round is due for a line yet.
        assertThat(asked[0]).isZero();
        toolResult(ctx, "c2", "2");

        assertThat(asked[0]).isEqualTo(1);
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "estimate", "chat");
    }

    @Test
    void aReportThatChangesNothingDoesNotWriteTheState() throws Exception {
        // A provider that reports the same small input for every call.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3"), callTool("c4"))
                        .reportingUsageOf(10);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();
        String path = ContextManager.STATE_KEY + "." + request.getId();

        send(ctx, request);
        toolResult(ctx, "c1", "1");
        // The first call whose input is estimated is the one with two rounds.
        toolResult(ctx, "c2", "2");
        assertThat(state(ctx, request.getId()))
                .containsEntry("calibration", 1.0)
                .containsEntry("calibration_tokens", 10L)
                .containsEntry("calibration_at_largest", 1.0);
        Object written = ctx.getSensoryMemory().get(path).getValue();

        // The next call reports the same: the factor and its measure stay, and so does the
        // stored state.
        toolResult(ctx, "c3", "3");
        assertThat(ctx.estimates()).hasSize(2);
        assertThat(ctx.getSensoryMemory().get(path).getValue()).isSameAs(written);
    }

    @Test
    void aSummarizerThatReturnsNothingIsAFailedSummary() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final")) {
                    @Override
                    public ChatResult chatWithoutTools(List<ChatMessage> messages) {
                        summaryRequests.add(List.copyOf(messages));
                        return null;
                    }
                };
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // The step reports it with the same text as on the Python side, and the request goes on
        // with the whole conversation.
        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("error", "the chat model returned no response");
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
    }

    // ── Replay ─────────────────────────────────────────────────────────────

    @Test
    void aRecordedSummaryIsReusedWithoutCallingTheSummarizer() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ctx.durableStore.put(
                ContextManager.SUMMARIZE_CALL_ID,
                outcome(ChatFixtures.response("Round 1: recorded notes")));

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(model.summaryRequests).isEmpty();
        assertThat(((TextBlock) model.requests.get(2).get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, "recorded notes"));
        assertThat(ctx.durableCallIds)
                .containsExactly("chat", "chat", "estimate", "summarize", "chat");
    }

    @Test
    void aRecordedSummaryIsReusedWhenTheSummarizerCanNoLongerBeResolved() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        Context ctx =
                summarizing()
                        .with(
                                AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL,
                                "small");
        ctx.register(MODEL, model);
        ctx.unresolvable.add("small");
        ctx.durableStore.put(
                ContextManager.SUMMARIZE_CALL_ID,
                outcome(ChatFixtures.response("Round 1: recorded notes")));

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(((TextBlock) model.requests.get(2).get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, "recorded notes"));
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 1)
                .doesNotContainKey("error");
        assertThat(ctx.durableCallIds)
                .containsExactly("chat", "chat", "estimate", "summarize", "chat");
    }

    @Test
    void aRecordedSummaryFailureIsReusedWithoutCallingTheSummarizer() throws Exception {
        // The first run recorded that the summarizer could not be used. By the replay it can.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                        .summaries("notes the replay must not ask for");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatModelInvoker.InvocationOutcome recorded = new ChatModelInvoker.InvocationOutcome();
        recorded.error = "chat model 'model' cannot be used: resource not found: model";
        ctx.durableStore.put(ContextManager.SUMMARIZE_CALL_ID, recorded);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        // The replay sends what the first run sent: the whole conversation.
        assertThat(model.summaryRequests).isEmpty();
        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("error", recorded.error)
                .containsEntry("rounds", 0);
        assertThat(ctx.durableCallIds)
                .containsExactly("chat", "chat", "estimate", "summarize", "chat");
    }

    private static ChatModelInvoker.InvocationOutcome outcome(ChatResult response) {
        ChatModelInvoker.InvocationOutcome outcome = new ChatModelInvoker.InvocationOutcome();
        outcome.response = response;
        return outcome;
    }

    // ── Cleanup and routing ────────────────────────────────────────────────

    @Test
    void whatIsKeptForARequestIsRemovedWhenTheRequestFails() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), new IllegalStateException("model is down"));
        Context ctx = new Context();
        ctx.register(MODEL, model);
        ChatRequestEvent request =
                request()
                        .withContextManagement(
                                ContextManagementPolicy.builder().toolResultMaxChars(100).build());

        send(ctx, request);
        assertThat(state(ctx, request.getId())).isNotNull();
        toolResult(ctx, "c1", repeat("a", 1000));

        assertThat(ctx.chatResponse().isFailed()).isTrue();
        assertThat(state(ctx, request.getId())).isNull();
    }

    @Test
    void theStateOfARequestHasAMemoryPathOfItsOwn() throws Exception {
        ScriptedModel model =
                new ScriptedModel(
                        callTool("a1"),
                        ChatFixtures.response("b is done"),
                        ChatFixtures.response("a is done"));
        Context ctx = new Context();
        ctx.register(MODEL, model);
        ContextManagementPolicy cut =
                ContextManagementPolicy.builder().toolResultMaxChars(100).build();
        ChatRequestEvent a = request().withContextManagement(cut);
        ChatRequestEvent b =
                request()
                        .withContextManagement(
                                ContextManagementPolicy.builder().toolResultMaxChars(200).build());

        // Request a waits for its tool while request b of the same key runs and completes.
        send(ctx, a);
        send(ctx, b);

        // Each request keeps its state at a path of its own: no value holds the state of both,
        // so what one request writes or clears cannot replace the state of the other.
        assertThat(ctx.getSensoryMemory().getFieldNames())
                .contains(
                        ContextManager.STATE_KEY + "." + a.getId(),
                        ContextManager.STATE_KEY + "." + b.getId())
                .doesNotContain(ContextManager.STATE_KEY);
        assertThat(state(ctx, b.getId())).isNull();
        assertThat(state(ctx, a.getId())).containsEntry("policy", cut.toMap());

        // Request a still has its own policy, and its state goes when it completes.
        toolResult(ctx, "a1", repeat("a", 1000));
        assertThat(toolText(model.requests.get(2).get(3)))
                .contains("characters truncated")
                .hasSizeLessThanOrEqualTo(100);
        assertThat(state(ctx, a.getId())).isNull();
    }

    /** A router over small and big that starts on small. */
    private static ModelRouter router(boolean fallback) throws Exception {
        return new ModelRouter(
                of("small", "big")
                        .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                        .defaultModel("small")
                        .fallback(fallback)
                        .build(),
                null);
    }

    @Test
    void everyCandidateOfARoutedRequestIsSentTheSameManagedMessages() throws Exception {
        ScriptedModel small = new ScriptedModel(new IllegalStateException("small is down"));
        ScriptedModel big = new ScriptedModel(ChatFixtures.response("final from big"));
        Context ctx =
                new Context(router(true))
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100);
        ctx.register("small", small);
        ctx.register("big", big);
        List<ChatMessage> history =
                List.of(
                        ChatMessage.user("go"),
                        callTool("h1").getMessage(),
                        ChatMessage.tool(
                                new ToolResultBlock(
                                        "h1", List.of(new TextBlock(repeat("a", 1000))), false)));

        send(ctx, new ChatRequestEvent("router", history));

        // The policy is applied once, for the selected model, and the fallback candidate is sent
        // the same messages.
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final from big");
        assertThat(toolText(small.requests.get(0).get(2))).contains("characters truncated");
        assertThat(big.requests.get(0)).isEqualTo(small.requests.get(0));
        assertThat(events(ctx)).hasSize(1);
        assertThat(events(ctx).get(0).getModel()).isEqualTo("small");
    }

    /** A conversation of two rounds that is over the trigger of {@link #summarizing()}. */
    private static List<ChatMessage> twoLongRounds() {
        return List.of(
                ChatMessage.system("You are terse."),
                ChatMessage.user("go"),
                callTool("c1").getMessage(),
                result("c1", repeat("1", 2000)),
                callTool("c2").getMessage(),
                result("c2", repeat("2", 2000)));
    }

    /** What a setup with the NATIVE strategy throws when its connection cannot carry a schema. */
    private static IllegalArgumentException noNativeSchema() {
        return new IllegalArgumentException(
                "the connection cannot apply the output schema natively");
    }

    @Test
    void aRequestWhoseSchemaNoChatModelCanCarryIsNotSummarized() throws Exception {
        ScriptedModel model = new ScriptedModel(ChatFixtures.response("final"));
        model.withGateFailure(noNativeSchema());
        Context ctx =
                summarizing()
                        .with(
                                AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS,
                                1900);
        ctx.register(MODEL, model);
        ChatRequestEvent request = new ChatRequestEvent(MODEL, twoLongRounds(), Map.class);

        send(ctx, request);

        // The request fails before its model call, as it does without a policy. No provider is
        // asked anything for it: the input is not estimated, which is recorded in place of an
        // estimate, and nothing is summarized. Its results are cut as for any request, and
        // nothing is kept.
        assertThat(ctx.chatResponse().isFailed()).isTrue();
        assertThat(model.requests).isEmpty();
        assertThat(model.summaryRequests).isEmpty();
        assertThat(ctx.durableCallIds).containsExactly("estimate");
        assertThat(ctx.estimates()).hasSize(1);
        assertThat(ctx.estimates().get(0).tokens).isEqualTo(-1L);
        assertThat(events(ctx))
                .extracting(ContextManagementEvent::getStep)
                .containsExactly(ContextManagementEvent.STEP_TRUNCATE);
        assertThat(state(ctx, request.getId())).isNull();
    }

    @Test
    void aRequestWhoseChatModelIsNotDeclaredIsNotSummarized() throws Exception {
        ScriptedModel small = new ScriptedModel();
        Context ctx =
                summarizing()
                        .with(
                                AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL,
                                "small");
        ctx.register("small", small);
        ctx.unresolvable.add(MODEL);

        send(ctx, new ChatRequestEvent(MODEL, twoLongRounds()));

        // The summarizer of the policy resolves, but the request has no chat model to go to:
        // it fails, and the summarizer is not asked.
        assertThat(ctx.chatResponse().isFailed()).isTrue();
        assertThat(small.summaryRequests).isEmpty();
        assertThat(ctx.durableCallIds).containsExactly("estimate");
        assertThat(ctx.estimates().get(0).tokens).isEqualTo(-1L);
        assertThat(events(ctx)).isEmpty();
    }

    @Test
    void anEstimateThatWasNotMadeIsReusedAfterARecovery() throws Exception {
        // The first run recorded that no chat model could be asked for the request. By the
        // replay one can.
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("{\"amount\": 30}"))
                        .summaries("notes the replay must not ask for");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ContextManager.EstimateOutcome recorded = new ContextManager.EstimateOutcome();
        recorded.tokens = -1;
        ctx.durableStore.put(ContextManager.ESTIMATE_CALL_ID, recorded);

        send(ctx, new ChatRequestEvent(MODEL, twoLongRounds(), Map.class));

        // The replay makes the durable calls of the first run, in their order: nothing is
        // summarized, and the model is sent the rounds as they are.
        assertThat(ctx.chatResponse().isFailed()).isFalse();
        assertThat(model.summaryRequests).isEmpty();
        assertThat(model.requests.get(0)).hasSize(6);
        assertThat(ctx.durableCallIds).containsExactly("estimate", "chat");
        assertThat(events(ctx)).isEmpty();
    }

    @Test
    void whatIsNoProviderFailureIsNotTakenForASchemaThatCannotBeCarried() throws Exception {
        RuntimeException fault =
                UncapturableException.wrap(new IllegalStateException("the framework failed"));
        for (RuntimeException failure :
                List.of(fault, new CancellationException("the call was cancelled"))) {
            ScriptedModel model = new ScriptedModel(ChatFixtures.response("final"));
            model.withGateFailure(failure);
            Context ctx = summarizing();
            ctx.register(MODEL, model);

            assertThatThrownBy(
                            () ->
                                    send(
                                            ctx,
                                            new ChatRequestEvent(
                                                    MODEL, twoLongRounds(), Map.class)))
                    .isSameAs(failure);
            // Nothing is recorded in place of an estimate, and no call follows.
            assertThat(ctx.durableCallIds).isEmpty();
            assertThat(model.requests).isEmpty();
        }
    }

    @Test
    void aChatModelThatDoesNotResolveAtFirstIsStillSentManagedMessages() throws Exception {
        ScriptedModel model = new ScriptedModel(ChatFixtures.response("{\"amount\": 30}"));
        Context ctx =
                summarizing()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100);
        ctx.register(MODEL, model);
        ctx.failingLookups = 1;

        send(ctx, new ChatRequestEvent(MODEL, twoLongRounds(), Map.class));

        // The chat model does not resolve when it is first looked up, for the estimate, and
        // does after that. Whatever the lookups give, the results are cut: the model call is
        // made with the messages the policy asks for.
        assertThat(ctx.failingLookups).isZero();
        assertThat(ctx.chatResponse().isFailed()).isFalse();
        assertThat(toolText(model.requests.get(0).get(3))).contains("characters truncated");
        assertThat(toolText(model.requests.get(0).get(5))).contains("characters truncated");
        assertThat(ctx.durableCallIds).containsExactly("estimate", "chat");
        assertThat(events(ctx))
                .extracting(ContextManagementEvent::getStep)
                .containsExactly(ContextManagementEvent.STEP_TRUNCATE);
    }

    @Test
    void noChatModelIsLookedUpOrAskedForASchemaWhileNoEstimateIsDue() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("{\"amount\": 30}")) {
                    @Override
                    public boolean willApplyNativeStructuredOutput(Object outputSchema) {
                        asked.incrementAndGet();
                        return super.willApplyNativeStructuredOutput(outputSchema);
                    }
                };
        // Results are cut, and there is no budget: nothing is estimated.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100);
        ctx.register(MODEL, model);

        send(ctx, new ChatRequestEvent(MODEL, twoLongRounds(), Map.class));

        // The attempt looks its chat model up and asks it for the schema, once each.
        assertThat(ctx.chatResponse().isFailed()).isFalse();
        assertThat(toolText(model.requests.get(0).get(3))).contains("characters truncated");
        assertThat(ctx.chatModelLookups).containsExactly(MODEL);
        assertThat(asked).hasValue(1);
    }

    @Test
    void aResultThatAnotherMessageSeparatesFromItsCallKeepsItsCall() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final"))
                        .summaries("notes that leave the call out");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.system("You are terse."),
                        ChatMessage.user("go"),
                        callTool("c1").getMessage(),
                        ChatMessage.system("Keep the answer short."),
                        result("c1", repeat("1", 2000)),
                        callTool("c2").getMessage(),
                        result("c2", repeat("2", 2000)));

        send(ctx, new ChatRequestEvent(MODEL, messages));

        // The first round is due for its line, and its result follows a system message. A
        // provider that is given system messages apart from the conversation takes such a
        // history. The round stays, so the result still has its call: nothing is summarized,
        // and the model is sent the messages as they are.
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        assertThat(model.summaryRequests).isEmpty();
        assertThat(model.requests.get(0)).isEqualTo(messages);
        assertThat(ctx.durableCallIds).containsExactly("chat");
        assertThat(events(ctx)).isEmpty();
    }

    @Test
    void resultsThatFollowSeveralAssistantMessagesKeepTheirCalls() throws Exception {
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("final"))
                        .summaries("notes that part a call");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.system("You are terse."),
                        ChatMessage.user("go"),
                        callTool("c1").getMessage(),
                        callTool("c2").getMessage(),
                        result("c1", repeat("1", 2000)),
                        result("c2", repeat("2", 2000)),
                        callTool("c3").getMessage(),
                        result("c3", repeat("3", 2000)));

        send(ctx, new ChatRequestEvent(MODEL, messages));

        // The first two rounds are due for their lines. The result of the first call stands in
        // the round of the second, so each of the two rounds holds half of a call and its
        // result: both stay, nothing is summarized, and the model is sent the messages as they
        // are.
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        assertThat(model.summaryRequests).isEmpty();
        assertThat(model.requests.get(0)).isEqualTo(messages);
        assertThat(ctx.durableCallIds).containsExactly("chat");
        assertThat(events(ctx)).isEmpty();
    }

    @Test
    void withoutAPolicyAChatModelIsAskedForTheSchemaByItsAttemptOnly() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        ScriptedModel model =
                new ScriptedModel(ChatFixtures.response("{\"amount\": 30}")) {
                    @Override
                    public boolean willApplyNativeStructuredOutput(Object outputSchema) {
                        asked.incrementAndGet();
                        return super.willApplyNativeStructuredOutput(outputSchema);
                    }
                };
        Context ctx = new Context();
        ctx.register(MODEL, model);

        send(ctx, new ChatRequestEvent(MODEL, twoLongRounds(), Map.class));

        // The request has rounds and no policy: nothing asks whether it can be attempted.
        assertThat(ctx.chatResponse().isFailed()).isFalse();
        assertThat(asked).hasValue(1);
    }

    @Test
    void aRequestThatAFallbackCandidateCanAnswerIsStillSummarized() throws Exception {
        ScriptedModel small = new ScriptedModel().summaries("Account c1 holds 10.");
        small.withGateFailure(noNativeSchema());
        ScriptedModel big = new ScriptedModel(ChatFixtures.response("{\"amount\": 30}"));
        Context ctx =
                new Context(router(true))
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register("small", small);
        ctx.register("big", big);

        send(ctx, new ChatRequestEvent("router", twoLongRounds(), Map.class));

        // The selected model cannot be asked for the schema, the other candidate can: the policy
        // is applied for the selected model, which also writes the summary, and the candidate
        // that answers is sent the managed messages.
        assertThat(ctx.chatResponse().isFailed()).isFalse();
        assertThat(small.requests).isEmpty();
        assertThat(small.summaryRequests).hasSize(1);
        assertThat(big.requests).hasSize(1);
        assertThat(((TextBlock) big.requests.get(0).get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, "Account c1 holds 10."));
        assertThat(ctx.durableCallIds)
                .containsExactly("route:router", "estimate", "summarize", "chat:router:big");
    }

    /** Rejects an answer whose text is "weak", and keeps the messages it judged an answer by. */
    public static final class WeakAnswerSignal implements QualitySignal {
        static final List<List<ChatMessage>> JUDGED = new ArrayList<>();

        @Override
        public QualityVerdict evaluate(QualityContext context) {
            JUDGED.add(List.copyOf(context.getMessages()));
            return "weak".equals(context.getResponse().getText())
                    ? QualityVerdict.escalate("answer flagged weak")
                    : QualityVerdict.accept();
        }
    }

    /** A scripted model that has the lookup tool: an escalation policy checks each tool call. */
    private static final class ToolingModel extends ScriptedModel {
        ToolingModel(Object... outcomes) {
            super(outcomes);
        }

        @Override
        public List<Tool> getTools() {
            return List.of(new ChatModelActionEscalationTest.LookupTool());
        }
    }

    @Test
    void theTiersOfAnEscalationAreSentTheSameManagedMessages() throws Exception {
        WeakAnswerSignal.JUDGED.clear();
        // small calls three tools. Its answer after them is rejected, and big answers in its
        // place with another tool call. small reports three times its estimates, big what its
        // estimates say.
        ScriptedModel small =
                new ToolingModel(
                                callTool("c1"),
                                callTool("c2"),
                                callTool("c3"),
                                ChatFixtures.response("weak"))
                        .reportingUsageTimes(3);
        ScriptedModel big = new ToolingModel(callTool("c4"), callTool("c5")).reportingUsageTimes(1);
        Context ctx =
                new Context(
                                new ModelRouter(
                                        of("small", "big")
                                                .escalation(
                                                        Escalation.tiers("small", "big")
                                                                .when(
                                                                        QualitySignals.custom(
                                                                                WeakAnswerSignal
                                                                                        .class)))
                                                .build(),
                                        null))
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100)
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register("small", small);
        ctx.register("big", big);
        ChatRequestEvent request =
                new ChatRequestEvent(
                        "router",
                        List.of(ChatMessage.system("You are terse."), ChatMessage.user("go")));

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 1000));
        // The call with one round is not estimated; the next one is, for small.
        assertThat(state(ctx, request.getId())).isNull();
        toolResult(ctx, "c2", repeat("2", 1000));
        // What small reports for that call corrects the estimates of the request. The estimate
        // counts the tool of the model too, so the factor is under the three of the messages.
        assertThat((Double) state(ctx, request.getId()).get("calibration")).isBetween(2.0, 3.0);
        toolResult(ctx, "c3", repeat("3", 1000));

        // The policy is applied once for the round, for the model the round starts with. The
        // tier that answers after the escalation is sent the same messages, and the signal
        // judges each answer by the messages its model was sent.
        List<ChatMessage> managed = small.requests.get(3);
        assertThat(toolText(managed.get(3))).contains("characters truncated");
        assertThat(toolText(managed.get(7))).contains("characters truncated");
        assertThat(big.requests).containsExactly(managed);
        assertThat(WeakAnswerSignal.JUDGED).hasSizeGreaterThanOrEqualTo(4);
        assertThat(WeakAnswerSignal.JUDGED.subList(3, WeakAnswerSignal.JUDGED.size()))
                .allSatisfy(judged -> assertThat(judged).isEqualTo(managed));
        assertThat(ctx.toolRequestEvent()).isNotNull();
        // The estimate was made for small, so what big reports for the round does not correct
        // it. The tool loop goes on with big, and the factor that was learned from small does
        // not apply to it: nothing is kept.
        assertThat(state(ctx, request.getId())).isNull();

        // What big reports corrects the estimates from here on.
        toolResult(ctx, "c4", repeat("4", 1000));
        assertThat(big.requests).hasSize(2);
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 1.0);
    }

    @Test
    void withoutAPolicyTheRoundsOfARequestAreNotListed() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"));
        Context ctx = new Context();
        ctx.register(MODEL, model);

        try (MockedStatic<ContextCompaction> compaction =
                mockStatic(ContextCompaction.class, CALLS_REAL_METHODS)) {
            send(ctx, request());
            toolResult(ctx, "c1", repeat("1", 2000));
            toolResult(ctx, "c2", repeat("2", 2000));
            compaction.verify(() -> ContextCompaction.findRounds(any()), never());
        }

        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final");
        assertThat(toolText(model.requests.get(2).get(3))).isEqualTo(repeat("1", 2000));
    }

    @Test
    void aMessageThatIsNullIsLeftToTheModelCall() throws Exception {
        // Without context management the model call reports such a list, and the request
        // fails. Nothing raises before that call, with a policy or without one.
        List<ChatMessage> messages =
                Arrays.asList(
                        ChatMessage.user("go"),
                        callTool("c1").getMessage(),
                        ChatMessage.tool(
                                new ToolResultBlock(
                                        "c1", List.of(new TextBlock(repeat("1", 2000))), false)),
                        null);
        Context unmanaged = new Context();
        Context managed =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100);
        for (Context ctx : List.of(unmanaged, managed)) {
            ctx.register(MODEL, new ScriptedModel());
            ContextManager.Prepared prepared =
                    ContextManager.prepare(
                            UUID.randomUUID(),
                            MODEL,
                            messages,
                            Map.of(),
                            true,
                            ctx,
                            List.of(MODEL),
                            null);
            assertThat(prepared.messages).isSameAs(messages);
        }
        assertThat(ContextCompaction.hasRound(Arrays.asList(null, ChatMessage.user("go"))))
                .isFalse();
    }

    @Test
    void theToolRoundsOfARoutedRequestUseThePolicyToo() throws Exception {
        ScriptedModel small =
                new ScriptedModel(callTool("c1"), ChatFixtures.response("final from small"));
        ScriptedModel big = new ScriptedModel();
        Context ctx =
                new Context(router(false))
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100);
        ctx.register("small", small);
        ctx.register("big", big);

        send(ctx, new ChatRequestEvent("router", List.of(ChatMessage.user("go"))));
        toolResult(ctx, "c1", repeat("a", 1000));

        // The tool round stays on the model that answered; the policy applies there.
        assertThat(ctx.chatResponse().getResponse().getText()).isEqualTo("final from small");
        assertThat(toolText(small.requests.get(1).get(2))).contains("characters truncated");
        assertThat(big.requests).isEmpty();
        assertThat(events(ctx)).hasSize(1);
        assertThat(events(ctx).get(0).getModel()).isEqualTo("small");
    }
}
