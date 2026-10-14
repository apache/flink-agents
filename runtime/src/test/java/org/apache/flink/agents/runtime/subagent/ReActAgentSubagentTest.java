/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.ReActAgent;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.InternalSubagentResourceDescriptorBuilder;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for a real {@link ReActAgent} running as an internal sub-agent, over the
 * operator harness. Unlike {@link InternalSubagentCallTest}, whose children are hand-written echo
 * agents, the child here is the built-in agent itself: its start action, the framework's chat model
 * action and its stop action must all run inside the child scope, driven by a scripted connection
 * instead of a live model.
 *
 * <p>The second test drives the model path: the parent model issues a {@code _subagent_child} tool
 * call, the framework routes it to the child, and the child's answer flows back as the tool
 * observation that closes the loop. The child is registered with a single-field {@code input}
 * schema, so this also proves the declared schema survives compilation and the child's prompt-free
 * {@code {input}} map path accepts the model's arguments as-is.
 *
 * <p>The third test lets the child declare a tool of its own and scripts two model rounds for it:
 * the first reply asks for the tool, the tool executes inside the child scope, and the second round
 * sees the tool result, closing the reasoning/tool loop in isolation.
 *
 * <p>The last two tests configure the parent ReActAgent with a prompt and an output schema and
 * leave the child bare. A child scope's resource lookup falls back to the root agent's resources,
 * so they pin that the child's built-in prompts stay its own rather than resolving to the parent's
 * under the same names.
 *
 * <p>Requires JDK 21+ for the same mailbox-suspension reason as {@link InternalSubagentCallTest}.
 */
@EnabledForJreRange(min = JRE.JAVA_21)
public class ReActAgentSubagentTest {

    @BeforeEach
    void resetScripts() {
        ParentScriptedConnection.reset();
        ChildScriptedConnection.reset();
    }

    // --- scripted chat model ---

    /** Materialized from a descriptor; the connection carries the script. */
    public static class ScriptedModelSetup extends BaseChatModelSetup {

        public ScriptedModelSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    /** Serves the parent agent; replies come from a static script. */
    public static class ParentScriptedConnection extends BaseChatModelConnection {

        static final Deque<ChatMessage> REPLIES = new ArrayDeque<>();
        static final List<List<ChatMessage>> CONVERSATIONS = new ArrayList<>();

        public ParentScriptedConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        static void reset() {
            REPLIES.clear();
            CONVERSATIONS.clear();
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            CONVERSATIONS.add(new ArrayList<>(messages));
            return new ChatResult(
                    REPLIES.isEmpty() ? ChatMessage.assistant("no script left") : REPLIES.poll());
        }
    }

    /** Serves the child agent; replies come from a static script. */
    public static class ChildScriptedConnection extends BaseChatModelConnection {

        static final Deque<ChatMessage> REPLIES = new ArrayDeque<>();
        static final List<List<ChatMessage>> CONVERSATIONS = new ArrayList<>();
        static final List<List<String>> OFFERED_TOOLS = new ArrayList<>();

        public ChildScriptedConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        static void reset() {
            REPLIES.clear();
            CONVERSATIONS.clear();
            OFFERED_TOOLS.clear();
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            CONVERSATIONS.add(new ArrayList<>(messages));
            OFFERED_TOOLS.add(
                    tools == null
                            ? List.of()
                            : tools.stream().map(Tool::getName).collect(Collectors.toList()));
            return new ChatResult(
                    REPLIES.isEmpty() ? ChatMessage.assistant("no script left") : REPLIES.poll());
        }
    }

    // --- caller actions ---

    @SuppressWarnings("unused")
    public static void callReActChild(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup child = (SubagentSetup) ctx.getResource("child", ResourceType.AGENT);
        SubagentResult result = child.submit(ctx, "review the diff").await();
        ctx.sendEvent(
                new OutputEvent(
                        result.isSuccess()
                                ? firstOutput(result)
                                : "unexpected: " + result.getErrorMessage()));
    }

    // --- tests ---

    @Test
    @Timeout(60)
    void callerDrivenCallRunsTheReActLoopInsideTheChildScope() throws Exception {
        ChildScriptedConnection.REPLIES.add(new ChatMessage(MessageRole.ASSISTANT, "reviewed: ok"));

        Agent parent = new Agent();
        registerConnections(parent);
        parent.addResource("child", ResourceType.AGENT, bareReactAgent("child_conn"));
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ReActAgentSubagentTest.class.getMethod(
                        "callReActChild", Event.class, RunnerContext.class));

        List<Object> output = run(parent, 7L);

        // The child's stop action output is the caller-visible result, and the submitted string
        // became the child's user message through the prompt-free primitive path.
        assertThat(output).containsExactly("reviewed: ok");
        assertThat(ChildScriptedConnection.CONVERSATIONS).hasSize(1);
        assertThat(latestUserText(ChildScriptedConnection.CONVERSATIONS.get(0)))
                .isEqualTo("review the diff");
    }

    @Test
    @Timeout(60)
    void modelDrivenDelegationRoutesToTheDeclaredChildAndClosesTheLoop() throws Exception {
        ToolCallBlock delegation = delegation();
        ParentScriptedConnection.REPLIES.add(assistantWithToolCall("delegating", delegation));
        ParentScriptedConnection.REPLIES.add(
                new ChatMessage(MessageRole.ASSISTANT, "delegated and reviewed"));
        ChildScriptedConnection.REPLIES.add(new ChatMessage(MessageRole.ASSISTANT, "reviewed: ok"));

        // The registration declares the child's schema, which carries it through open().
        ReActAgent parent =
                new ReActAgent(
                        ResourceDescriptor.Builder.newBuilder(ScriptedModelSetup.class.getName())
                                .addInitialArgument("connection", "parent_conn")
                                .addInitialArgument("subagents", List.of("child"))
                                .build(),
                        null,
                        null);
        registerConnections(parent);
        parent.addResource(
                "child", ResourceType.AGENT, declaredChild(bareReactAgent("child_conn")));

        List<Object> output = run(parent, 7L);

        assertThat(output).containsExactly("delegated and reviewed");
        // The model's arguments reached the child as its user message through the prompt-free
        // {input} map path, and the child's answer came back as the closing observation.
        assertThat(ChildScriptedConnection.CONVERSATIONS).hasSize(1);
        assertThat(latestUserText(ChildScriptedConnection.CONVERSATIONS.get(0)))
                .isEqualTo("review the diff");
        assertThat(ParentScriptedConnection.CONVERSATIONS).hasSize(2);
        assertThat(allTexts(ParentScriptedConnection.CONVERSATIONS.get(1)))
                .anySatisfy(text -> assertThat(text).contains("reviewed: ok"));
    }

    @Test
    @Timeout(60)
    void toolCallLoopRunsInsideTheChildScope() throws Exception {
        ToolCallBlock lookup = new ToolCallBlock("call-1", "shout", Map.of("text", "hello world"));
        ChildScriptedConnection.REPLIES.add(assistantWithToolCall("let me check", lookup));
        ChildScriptedConnection.REPLIES.add(
                new ChatMessage(MessageRole.ASSISTANT, "tool says: HELLO WORLD!"));

        Agent parent = new Agent();
        registerConnections(parent);
        parent.addResource("child", ResourceType.AGENT, toolCallingReactAgent());
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ReActAgentSubagentTest.class.getMethod(
                        "callReActChild", Event.class, RunnerContext.class));

        List<Object> output = run(parent, 7L);

        // The tool schema was offered on the first round, the tool executed inside the child
        // scope, and the second round saw the tool result before answering.
        assertThat(output).containsExactly("tool says: HELLO WORLD!");
        assertThat(ChildScriptedConnection.OFFERED_TOOLS.get(0)).containsExactly("shout");
        assertThat(ChildScriptedConnection.CONVERSATIONS).hasSize(2);
        assertThat(ChildScriptedConnection.CONVERSATIONS.get(1))
                .anySatisfy(
                        message -> {
                            assertThat(message.getRole()).isEqualTo(MessageRole.TOOL);
                            ToolResultBlock result = (ToolResultBlock) message.getBlocks().get(0);
                            assertThat(result.getText()).isEqualTo("HELLO WORLD!");
                        });
    }

    @Test
    @Timeout(60)
    void bareChildDoesNotInheritTheParentPrompt() throws Exception {
        ParentScriptedConnection.REPLIES.add(assistantWithToolCall("delegating", delegation()));
        ParentScriptedConnection.REPLIES.add(
                new ChatMessage(MessageRole.ASSISTANT, "delegated and reviewed"));
        ChildScriptedConnection.REPLIES.add(new ChatMessage(MessageRole.ASSISTANT, "reviewed: ok"));

        ReActAgent parent =
                delegatingParent(Prompt.fromText("PARENT ONLY: {input}"), /* outputSchema= */ null);

        List<Object> output = run(parent, 7L);

        assertThat(output).containsExactly("delegated and reviewed");
        // The parent prompt applies to the parent's own input only; the bare child takes the
        // delegated value through its prompt-free {input} map path.
        assertThat(latestUserText(ParentScriptedConnection.CONVERSATIONS.get(0)))
                .isEqualTo("PARENT ONLY: 7");
        assertThat(ChildScriptedConnection.CONVERSATIONS).hasSize(1);
        assertThat(latestUserText(ChildScriptedConnection.CONVERSATIONS.get(0)))
                .isEqualTo("review the diff");
    }

    @Test
    @Timeout(60)
    void bareChildDoesNotInheritTheParentOutputSchema() throws Exception {
        ParentScriptedConnection.REPLIES.add(assistantWithToolCall("delegating", delegation()));
        ParentScriptedConnection.REPLIES.add(
                new ChatMessage(MessageRole.ASSISTANT, "{\"verdict\":\"ok\"}"));
        ChildScriptedConnection.REPLIES.add(new ChatMessage(MessageRole.ASSISTANT, "reviewed: ok"));

        ReActAgent parent = delegatingParent(/* prompt= */ null, Verdict.class);

        run(parent, 7L);

        // The parent's own request carries its schema instruction, which proves the instruction
        // is reachable by name; the bare child's request must not.
        assertThat(allTexts(ParentScriptedConnection.CONVERSATIONS.get(0)))
                .anySatisfy(text -> assertThat(text).contains(SCHEMA_INSTRUCTION));
        assertThat(ChildScriptedConnection.CONVERSATIONS).hasSize(1);
        List<ChatMessage> childConversation = ChildScriptedConnection.CONVERSATIONS.get(0);
        assertThat(allTexts(childConversation))
                .noneSatisfy(text -> assertThat(text).contains(SCHEMA_INSTRUCTION));
        assertThat(childConversation)
                .extracting(ChatMessage::getRole)
                .containsExactly(MessageRole.USER);
    }

    // --- helpers ---

    private static final String SCHEMA_INSTRUCTION = "The final response should be json format";

    /** The parent's structured answer, giving it an output schema the bare child must not see. */
    public static class Verdict {
        public String verdict;
    }

    private static ToolCallBlock delegation() {
        return new ToolCallBlock("call-1", "_subagent_child", Map.of("input", "review the diff"));
    }

    private static ChatMessage assistantWithToolCall(String text, ToolCallBlock toolCall) {
        return ChatMessage.assistant(List.of(new TextBlock(text), toolCall));
    }

    /** A ReActAgent parent configured as given, declaring a bare ReActAgent child to its model. */
    private static ReActAgent delegatingParent(
            @Nullable Prompt prompt, @Nullable Class<?> outputSchema) {
        ReActAgent parent =
                new ReActAgent(
                        ResourceDescriptor.Builder.newBuilder(ScriptedModelSetup.class.getName())
                                .addInitialArgument("connection", "parent_conn")
                                .addInitialArgument("subagents", List.of("child"))
                                .build(),
                        prompt,
                        outputSchema);
        registerConnections(parent);
        parent.addResource(
                "child", ResourceType.AGENT, declaredChild(bareReactAgent("child_conn")));
        return parent;
    }

    /**
     * Declares {@code child} with a single {@code input} field, the arguments shape the prompt-free
     * map path of a ReActAgent passes through as the user message.
     */
    private static ResourceDescriptor declaredChild(Agent child) {
        return new InternalSubagentResourceDescriptorBuilder()
                .agent(child)
                .description("Reviews a code diff.")
                .inputSchema(
                        "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},"
                                + "\"required\":[\"input\"]}")
                .build();
    }

    private static ReActAgent toolCallingReactAgent() throws Exception {
        ReActAgent child =
                new ReActAgent(
                        ResourceDescriptor.Builder.newBuilder(ScriptedModelSetup.class.getName())
                                .addInitialArgument("connection", "child_conn")
                                .addInitialArgument("tools", List.of("shout"))
                                .build(),
                        null,
                        null);
        child.addResource(
                "shout",
                ResourceType.TOOL,
                Tool.fromMethod(ReActAgentSubagentTest.class.getMethod("shout", String.class)));
        return child;
    }

    @SuppressWarnings("unused")
    public static String shout(@ToolParam(name = "text") String text) {
        return text.toUpperCase(Locale.ROOT) + "!";
    }

    private static ReActAgent bareReactAgent(String connectionName) {
        return new ReActAgent(
                ResourceDescriptor.Builder.newBuilder(ScriptedModelSetup.class.getName())
                        .addInitialArgument("connection", connectionName)
                        .build(),
                null,
                null);
    }

    private static String firstOutput(SubagentResult result) {
        return String.valueOf(((List<?>) result.getResult()).get(0));
    }

    private static String latestUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getRole() == MessageRole.USER) {
                return message.getText();
            }
        }
        throw new AssertionError("no user message in " + messages);
    }

    private static List<String> allTexts(List<ChatMessage> messages) {
        return messages.stream()
                .flatMap(message -> message.getBlocks().stream())
                .filter(block -> block instanceof TextBlock || block instanceof ToolResultBlock)
                .map(
                        block ->
                                block instanceof TextBlock
                                        ? ((TextBlock) block).getText()
                                        : ((ToolResultBlock) block).getText())
                .collect(Collectors.toList());
    }

    private static void registerConnections(Agent agent) {
        agent.addResource(
                "parent_conn",
                ResourceType.CHAT_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(ParentScriptedConnection.class.getName())
                        .build());
        agent.addResource(
                "child_conn",
                ResourceType.CHAT_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(ChildScriptedConnection.class.getName())
                        .build());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> run(Agent root, long input) throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(new AgentPlan(root), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            harness.open();
            harness.processElement(new StreamRecord<>(input));
            ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                    .waitInFlightEventsFinished();
            return ((List<StreamRecord<Object>>) harness.getRecordOutput())
                    .stream().map(StreamRecord::getValue).collect(Collectors.toList());
        }
    }
}
