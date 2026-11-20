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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.contextmanagement.ContextManagementPolicy;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextManagementEvent;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An agent whose chat model looks up accounts one tool call per round, each lookup returning a
 * large result, and then answers with the sum of the amounts it can see. No credentials: the chat
 * connection is scripted from the messages it receives.
 *
 * <p>The connection also writes the notes when it is asked for a summary: one line for each round
 * it is shown, with the amount that round found. The sum is therefore only complete when what
 * context management removed from the model input is still covered by a summary.
 *
 * <p>The job's configuration declares the default policy. A request whose text starts with {@code
 * masked} carries its own policy, which only keeps the tool results of the last round; one whose
 * text starts with {@code unmanaged} carries a disabled policy; and one whose text starts with
 * {@code called} is made with {@code ctx.chat} and the masking policy.
 */
public class ContextManagementAgent extends Agent {
    private static final Pattern AMOUNT = Pattern.compile("account=(\\d+) amount=(\\d+)");
    private static final Pattern WANTED = Pattern.compile("sum (\\d+) accounts");
    private static final Pattern SUMMARIZED = Pattern.compile("\\(rounds 1 to (\\d+)\\)");
    private static final Pattern ROUND = Pattern.compile("(?m)^Round (\\d+):\\n(.*)$");
    private static final String SUMMARIZER_OPENING = "You maintain the working notes";

    private static ContextManagementPolicy masking() {
        return ContextManagementPolicy.builder().toolResultKeepLastRounds(1).build();
    }

    /** Characters of tool result text in each model request, by request text, in call order. */
    private static final Map<String, List<Integer>> TOOL_RESULT_CHARS = new ConcurrentHashMap<>();

    /** The steps reported for each request, by request text, in the order they arrived. */
    private static final Map<String, List<String>> STEPS = new ConcurrentHashMap<>();

    private static final Map<UUID, String> REQUEST_TEXTS = new ConcurrentHashMap<>();
    private static final List<String> SUMMARY_REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<Map<String, Object>> SUMMARY_DETAILS = new CopyOnWriteArrayList<>();

    public static List<Integer> toolResultChars(String requestText) {
        return List.copyOf(TOOL_RESULT_CHARS.getOrDefault(requestText, List.of()));
    }

    public static List<String> steps(String requestText) {
        return List.copyOf(STEPS.getOrDefault(requestText, List.of()));
    }

    /** The text of every summary request, in call order. */
    public static List<String> summaryRequests() {
        return List.copyOf(SUMMARY_REQUESTS);
    }

    /** The details of every reported summarize step, in the order they arrived. */
    public static List<Map<String, Object>> summaryDetails() {
        return List.copyOf(SUMMARY_DETAILS);
    }

    public static void reset() {
        TOOL_RESULT_CHARS.clear();
        STEPS.clear();
        REQUEST_TEXTS.clear();
        SUMMARY_REQUESTS.clear();
        SUMMARY_DETAILS.clear();
    }

    /** The result of one lookup: the amount first, then 3000 characters the answer never needs. */
    public static String lookupResult(int account) {
        return "account=" + account + " amount=" + account * 10 + " audit=" + "x".repeat(3000);
    }

    @Tool(description = "Looks up the amount held by one account")
    public static String lookup(
            @ToolParam(name = "account", description = "the account number") Integer account) {
        return lookupResult(account);
    }

    /** The scripted provider, see the class comment. */
    public static class ScriptedChatConnection extends BaseChatModelConnection {
        public ScriptedChatConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages,
                List<org.apache.flink.agents.api.tools.Tool> tools,
                Map<String, Object> modelParams) {
            if (messages.get(0).getRole() == MessageRole.SYSTEM
                    && messages.get(0).getText().startsWith(SUMMARIZER_OPENING)) {
                return writeNotes(messages.get(1).getText(), tools);
            }

            String requestText = ((TextBlock) messages.get(0).getBlocks().get(0)).getText();
            StringBuilder visible = new StringBuilder();
            int toolResultChars = 0;
            int rounds = 0;
            for (ChatMessage message : messages) {
                visible.append(message.getText()).append('\n');
                if (!message.getToolCalls().isEmpty()) {
                    rounds++;
                }
                for (ContentBlock block : message.getBlocks()) {
                    if (block instanceof ToolResultBlock) {
                        String text = ((ToolResultBlock) block).getText();
                        toolResultChars += text.length();
                        visible.append(text).append('\n');
                    }
                }
            }
            TOOL_RESULT_CHARS
                    .computeIfAbsent(requestText, key -> new CopyOnWriteArrayList<>())
                    .add(toolResultChars);
            // Rounds a summary stands for are no longer in the messages, but they were made.
            Matcher summarized = SUMMARIZED.matcher(visible);
            if (summarized.find()) {
                rounds += Integer.parseInt(summarized.group(1));
            }

            Matcher wanted = WANTED.matcher(requestText);
            if (!wanted.find()) {
                throw new IllegalArgumentException("unexpected request: " + requestText);
            }
            if (rounds < Integer.parseInt(wanted.group(1))) {
                int account = rounds + 1;
                return new ChatResult(
                        ChatMessage.assistant(
                                List.of(
                                        new ToolCallBlock(
                                                "call-" + account,
                                                "lookup",
                                                Map.of("account", account)))));
            }
            Map<String, Integer> amounts = amounts(visible);
            int sum = amounts.values().stream().mapToInt(Integer::intValue).sum();
            return new ChatResult(
                    ChatMessage.assistant("sum=" + sum + " accounts=" + amounts.size()));
        }

        private static ChatResult writeNotes(
                String request, List<org.apache.flink.agents.api.tools.Tool> tools) {
            if (!tools.isEmpty()) {
                throw new IllegalStateException("a summary request must not bind tools");
            }
            SUMMARY_REQUESTS.add(request);
            // One line for each round the request shows: the amount that round found.
            StringBuilder notes = new StringBuilder();
            Matcher round = ROUND.matcher(request);
            while (round.find()) {
                Matcher amount = AMOUNT.matcher(round.group(2));
                if (amount.find()) {
                    notes.append("Round ")
                            .append(round.group(1))
                            .append(": account=")
                            .append(amount.group(1))
                            .append(" amount=")
                            .append(amount.group(2))
                            .append('\n');
                }
            }
            return new ChatResult(ChatMessage.assistant(notes.toString()));
        }

        /** Every amount named in a text, by account, in the order of first mention. */
        private static Map<String, Integer> amounts(CharSequence text) {
            Map<String, Integer> amounts = new LinkedHashMap<>();
            Matcher matcher = AMOUNT.matcher(text);
            while (matcher.find()) {
                amounts.putIfAbsent(matcher.group(1), Integer.parseInt(matcher.group(2)));
            }
            return amounts;
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
    public static ResourceDescriptor chatConnection() {
        return ResourceDescriptor.Builder.newBuilder(ScriptedChatConnection.class.getName())
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor chat() {
        return ResourceDescriptor.Builder.newBuilder(ScriptedChatModel.class.getName())
                .addInitialArgument("connection", "chatConnection")
                .addInitialArgument("model", "scripted")
                .addInitialArgument("tools", List.of("lookup"))
                .build();
    }

    @Action(EventType.InputEvent)
    public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
        String text = (String) event.getInput();
        ChatRequestEvent request = new ChatRequestEvent("chat", List.of(ChatMessage.user(text)));
        if (text.startsWith("called")) {
            ChatResult result = ctx.chat(request.withContextManagement(masking())).await();
            ctx.sendEvent(new OutputEvent(text + " -> " + result.getText()));
            return;
        }
        if (text.startsWith("unmanaged")) {
            request.withContextManagement(ContextManagementPolicy.disabled());
        } else if (text.startsWith("masked")) {
            request.withContextManagement(masking());
        }
        REQUEST_TEXTS.put(request.getId(), text);
        ctx.sendEvent(request);
    }

    /** Context management steps are ordinary events: an action can listen to them. */
    @Action(EventType.ContextManagementEvent)
    public static void recordStep(Event event, RunnerContext ctx) {
        ContextManagementEvent step = ContextManagementEvent.fromEvent(event);
        String requestText = REQUEST_TEXTS.get(step.getRequestId());
        STEPS.computeIfAbsent(requestText, key -> new CopyOnWriteArrayList<>()).add(step.getStep());
        if (ContextManagementEvent.STEP_SUMMARIZE.equals(step.getStep())) {
            SUMMARY_DETAILS.add(new HashMap<>(step.getDetails()));
        }
    }

    @Action(EventType.ChatResponseEvent)
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
        String requestText = REQUEST_TEXTS.get(event.getRequestId());
        if (event.isFailed()) {
            ctx.sendEvent(new OutputEvent(requestText + " -> FAILED: " + event.getError()));
            return;
        }
        ctx.sendEvent(new OutputEvent(requestText + " -> " + event.getResponse().getText()));
    }
}
