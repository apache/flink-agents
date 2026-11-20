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

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.contextmanagement.ContextManagementPolicy;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.model.ApproximateTokenEstimator;
import org.apache.flink.agents.api.chat.model.TokenEstimate;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.configuration.ConfigOption;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ContextManagementEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.ChatFixtures;
import org.apache.flink.agents.plan.contextmanagement.ContextCompaction;
import org.apache.flink.agents.plan.contextmanagement.ContextManager;
import org.apache.flink.agents.plan.resource.python.PythonChatModelSetup;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(Context ctx, UUID requestId) throws Exception {
        if (!ctx.getSensoryMemory().isExist(ContextManager.STATE_KEY)) {
            return null;
        }
        Map<UUID, Object> states =
                (Map<UUID, Object>) ctx.getSensoryMemory().get(ContextManager.STATE_KEY).getValue();
        return (Map<String, Object>) states.get(requestId);
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
        return ContextCompaction.SUMMARY_SEPARATOR
                + ContextCompaction.summaryText(rounds, notes(lines));
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
                .containsExactly(Map.of("call_id", "c1", "tool", "lookup", "omitted_chars", 300));
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
                .containsExactly(Map.of("call_id", "c2", "tool", "lookup", "omitted_chars", 300));

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
        assertThat(results(events(ctx).get(0)))
                .extracting(result -> result.get("call_id"))
                .containsExactly("c1", "c2");
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
        // A summary is one durable call of its own, made before the round's model call.
        assertThat(ctx.durableCallIds)
                .containsExactly("chat", "chat", "summarize", "chat", "summarize", "chat");
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
    void anEmptySummaryIsNotUsed() throws Exception {
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                        .summaries("   ");
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(model.requests.get(2)).hasSize(6);
        assertThat((String) events(ctx).get(0).getDetails().get("error"))
                .contains("returned no text");
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
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "summarize", "chat");
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
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "summarize", "chat");
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
                                ChatFixtures.response("final"))
                        .summaries("notes on round 1", "notes on round 2");
        // Results are cut to 1000 characters, only the last round keeps its result, and older
        // rounds are summarized above 341 estimated tokens.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 1000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                1)
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 620)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO,
                                0.55)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);

        send(ctx, request());
        // One round, cut: 275 estimated tokens.
        toolResult(ctx, "c1", repeat("1", 4000));
        assertThat(toolText(model.requests.get(1).get(3))).hasSize(1000);

        // Two rounds, the first masked and the second cut: 309 tokens, still under the trigger.
        toolResult(ctx, "c2", repeat("2", 4000));
        assertThat(toolText(model.requests.get(2).get(3))).startsWith("[Tool result omitted");
        assertThat(toolText(model.requests.get(2).get(5))).hasSize(1000);
        assertThat(model.summaryRequests).isEmpty();

        // Three rounds, two masked: 344 tokens. Rounds 1 and 2 are summarized, one per request
        // because both do not fit the budget of one.
        toolResult(ctx, "c3", repeat("3", 4000));
        assertThat(model.summaryRequests).hasSize(2);
        // The summarizer reads the stored result, cut to the same limit, not the placeholder.
        String asked = model.summaryRequests.get(0).get(1).getText();
        assertThat(asked)
                .contains("Round 1:\nlookup({\"q\":\"c1\"}) returned: 1111")
                .contains("[3035 characters truncated]")
                .doesNotContain("Tool result omitted");
        List<ChatMessage> fourth = model.requests.get(3);
        assertThat(fourth).hasSize(4);
        assertThat(((TextBlock) fourth.get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(2, "notes on round 1", "notes on round 2"));
        assertThat(toolText(fourth.get(3))).hasSize(1000).contains("characters truncated");

        List<String> steps =
                events(ctx).stream()
                        .map(ContextManagementEvent::getStep)
                        .collect(Collectors.toList());
        assertThat(steps).containsExactly("truncate", "truncate", "mask", "summarize", "truncate");
    }

    @Test
    void aSummaryTheProviderDidNotFinishIsNotUsed() throws Exception {
        ChatResult cutOff =
                new ChatResult(
                        ChatMessage.assistant("Account c1 ho"),
                        null,
                        null,
                        null,
                        "length",
                        Map.of());
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                        .summaries(cutOff);
        Context ctx = summarizing();
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("error", "the summary is incomplete (finish_reason='length')")
                .containsEntry("rounds", 0);
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
        // is halved until it does, here three times, down to 2504 characters.
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(approximate(model.summaryRequests.get(0))).isEqualTo(956L);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .contains("Round 1:\n")
                .contains("[17532 characters truncated]");
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
        assertThat(ctx.durableAsyncCallIds).containsExactly("chat", "chat", "summarize", "chat");
        assertThat(ctx.durableCallIds).isEqualTo(ctx.durableAsyncCallIds);
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
        // Masking moves two rounds at a time, and a summary leaves the last two rounds.
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
                                2);
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 2000));
        toolResult(ctx, "c2", repeat("2", 2000));
        assertThat(events(ctx)).isEmpty();
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
        List<ChatMessage> fourth = model.requests.get(3);
        assertThat(fourth.get(2).getToolCalls().get(0).getName()).isEqualTo("load_skill");
        assertThat(toolText(fourth.get(3))).isEqualTo(repeat("i", 1500));
        assertThat(fourth.get(4).getText())
                .isEqualTo(
                        "[Summary of the earlier tool-call rounds of this request (rounds 1 to"
                                + " 2), which are no longer shown except those that load a"
                                + " skill]\nRound 1: notes on round 1");
        assertThat(toolText(fourth.get(6))).isEqualTo(repeat("3", 1500));
        // One round was added to the summary, which now stands for rounds 1 to 2.
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 2);
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

        // Round 1 is due, and it loads a skill: there is nothing to ask the summarizer and
        // nothing to report. The model is sent both rounds as they are.
        assertThat(model.summaryRequests).isEmpty();
        assertThat(events(ctx)).isEmpty();
        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "chat");

        // Round 2 is due next. Its summary stands for rounds 1 to 2, and round 1 stays.
        toolResult(ctx, "c3", repeat("3", 2000));
        assertThat(model.summaryRequests).hasSize(1);
        assertThat(model.summaryRequests.get(0).get(1).getText())
                .contains("Round 2:\n")
                .doesNotContain("Round 1:");
        List<ChatMessage> fourth = model.requests.get(3);
        assertThat(fourth).hasSize(7);
        assertThat(toolText(fourth.get(3))).isEqualTo(repeat("i", 2500));
        assertThat(fourth.get(4).getText())
                .isEqualTo(ContextCompaction.summaryText(2, "Round 2: notes on round 2", true));
        assertThat(fourth.get(5).getToolCalls().get(0).getCallId()).isEqualTo("c3");
        assertThat(events(ctx)).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 2);
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
        assertThat(ctx.durableCallIds).containsExactly("summarize", "summarize", "chat");
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
        // The summarizer's instructions alone are about 250 tokens.
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 200)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1);
        ctx.register(MODEL, model);

        send(ctx, request());
        toolResult(ctx, "c1", repeat("1", 500));
        toolResult(ctx, "c2", repeat("2", 500));

        assertThat(model.summaryRequests).isEmpty();
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry(
                        "error",
                        "round 1 does not fit a summarizer request within max_input_tokens=200")
                .containsEntry("rounds", 0);
        assertThat(model.requests.get(2)).hasSize(6);
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "chat");
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
        assertThat(state(ctx, request.getId())).isNull();

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
                .containsExactly("summarize", "summarize", "summarize", "chat");
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
        String line = repeat("n", 163) + "\n...[1675 characters truncated]...\n" + repeat("n", 162);
        assertThat(((TextBlock) model.requests.get(2).get(1).getBlocks().get(1)).getText())
                .isEqualTo(summaryBlock(1, line));
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("summary_chars", "Round 1: ".length() + 360);
    }

    @Test
    void notesThatAreFullTakeNoMoreRounds() throws Exception {
        // Every line is as long as a line may be, and the notes may take half of the budget of
        // 1000 tokens: five lines of 92 tokens fit.
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

        // Rounds 1 to 5 are in the notes. A sixth line of the most tokens a line can take would
        // not fit, so the summarizer is not asked about round 6, which stays in the input.
        List<Map<String, Object>> summaries =
                events(ctx).stream()
                        .map(ContextManagementEvent::getDetails)
                        .collect(Collectors.toList());
        assertThat(summaries)
                .extracting(details -> details.get("summarized_rounds"))
                .containsExactly(1, 2, 3, 4, 5, 5);
        assertThat(summaries)
                .extracting(details -> details.get("rounds"))
                .containsExactly(1, 1, 1, 1, 1, 0);
        assertThat(model.summaryRequests).hasSize(5);
        assertThat(summaries.get(5))
                .containsEntry(
                        "error",
                        "the summary is full: it takes 461 of the 500 estimated tokens it may"
                                + " take with max_input_tokens=1000, which leaves no room for the"
                                + " line of another round");
        assertThat(state(ctx, request.getId())).containsEntry("summarized_rounds", 5);
        assertThat(model.requests.get(7)).hasSize(6);
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
        // The provider reports three times the approximate estimate, so the trigger of 1600 is
        // crossed at about 1040 estimated tokens.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3"))
                        .reportingUsageTimes(3);
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
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 3.0);
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(model.summaryRequests).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry(
                        "estimated_input_tokens_before",
                        3 * approximate(stored(ctx, request.getId()).subList(0, 6)))
                .containsEntry("estimate_approximate", true);
    }

    @Test
    void aReportedInputSizeBelowTheEstimateDoesNotLowerIt() throws Exception {
        // A provider that reports cached input tokens separately can report a small input size
        // for a large request. Taken at face value it would hold the estimate at a quarter of
        // the 1037 tokens and the trigger of 800 would not be reached.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), ChatFixtures.response("final"))
                        .reportingUsageOf(10L)
                        .summaries("notes on round 1");
        Context ctx = summarizing();
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 2000));
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 1.0);
        toolResult(ctx, "c2", repeat("2", 2000));

        assertThat(model.summaryRequests).hasSize(1);
        assertThat(events(ctx).get(0).getDetails())
                .containsEntry("estimated_input_tokens_before", 1037L);
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
                        ChatFixtures.response("final")) {
                    @Override
                    public ChatResult chat(
                            List<ChatMessage> messages,
                            Map<String, Object> promptArgs,
                            Map<String, Object> modelParams) {
                        // The first call with a round is billed in full, three times the
                        // estimate. The later ones report 10 input tokens, as a provider does
                        // that counts the input it served from its cache apart.
                        if (requests.size() >= 2) {
                            reportingUsageOf(10L);
                        }
                        return super.chat(messages, promptArgs, modelParams);
                    }
                };
        model.reportingUsageTimes(3).summaries("notes on round 1", "notes on round 2");
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
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 3.0);
        toolResult(ctx, "c2", repeat("2", 2000));
        assertThat(model.summaryRequests).hasSize(1);
        // The call that followed reported 10 tokens. The factor of 3 stays.
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 3.0);

        // So the summary and two rounds, about 1070 estimated tokens, still count three times
        // and cross the trigger of 1600.
        toolResult(ctx, "c3", repeat("3", 2000));
        assertThat(model.summaryRequests).hasSize(2);
    }

    @Test
    void theCorrectionComesFromTheCallWithTheLargestReportedInput() throws Exception {
        // The provider counts 2000 tokens the estimate does not cover, a prompt and tools bound
        // on the other language's side for example.
        ScriptedModel model =
                new ScriptedModel(callTool("c1"), callTool("c2"), callTool("c3"))
                        .reportingUsagePlus(2000);
        Context ctx =
                new Context()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100_000);
        ctx.register(MODEL, model);
        ChatRequestEvent request = request();

        send(ctx, request);
        toolResult(ctx, "c1", repeat("1", 2000));
        // One round: about 520 estimated and 2520 reported tokens, which is the most the
        // factor can be.
        assertThat(state(ctx, request.getId())).containsEntry("calibration", 4.0);

        // Two rounds: the fixed part weighs less in a larger request, and the factor follows
        // it down.
        toolResult(ctx, "c2", repeat("2", 2000));
        long estimated = approximate(model.requests.get(2));
        Map<String, Object> state = state(ctx, request.getId());
        assertThat(state)
                .containsEntry("calibration", (double) (estimated + 2000) / estimated)
                .containsEntry("calibration_tokens", estimated + 2000);
        assertThat((Double) state.get("calibration")).isLessThan(3.0);
    }

    @Test
    void theInputSizeAFallbackCandidateReportsDoesNotCorrectTheSelectedModelsEstimate()
            throws Exception {
        ScriptedModel small = new ScriptedModel(new IllegalStateException("small is down"));
        ScriptedModel big =
                new ScriptedModel(callTool("c2"), callTool("c3")).reportingUsageTimes(3);
        Context ctx =
                new Context(router(true))
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100_000);
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
                                                "h1", List.of(new TextBlock("result")), false))));

        send(ctx, request);
        // The estimate was made for small. What big reports for the same messages is no
        // measure of it, so nothing is kept.
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
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "summarize", "chat");
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
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "summarize", "chat");
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
        assertThat(ctx.durableCallIds).containsExactly("chat", "chat", "summarize", "chat");
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

    /** A router over small and big that starts on small. */
    private static ModelRouter router(boolean fallback) throws Exception {
        return new ModelRouter(
                ModelRouter.of("small", "big")
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
