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
package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.contextmanagement.ContextManagementPolicy;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.configuration.ConfigOption;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextManagementEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.ChatModelAction;
import org.apache.flink.agents.plan.contextmanagement.ContextCompaction;
import org.apache.flink.agents.plan.contextmanagement.ContextManager;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.CallResult;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.memory.CachedMemoryStore;
import org.apache.flink.agents.runtime.memory.ForTestMemoryMapState;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.utils.RuntimeIdentity;
import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.memory.DataInputDeserializer;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies context management against the runtime's durable journal and recovery serializer. A
 * request runs two tool rounds; the model call after the second round is the one under test.
 *
 * <p>Truncation and masking must leave the journal as it is without them. With an input budget the
 * estimate of the round's input is journaled first, so a replay decides from the recorded number,
 * and a summary must be journaled as one call of its own before the round's chat call and be reused
 * on replay.
 */
class ContextManagementRecoveryTest {
    private static final String FAILURE = "simulated worker failure after persistence";
    private static final String NOTES = "Round 1: account c1 holds 10.";

    /** The memory the operator persisted: written only when an action completes. */
    private final ForTestMemoryMapState<MemoryObjectImpl.MemoryItem> sensoryState =
            new ForTestMemoryMapState<>();

    private final ForTestMemoryMapState<MemoryObjectImpl.MemoryItem> shortTermState =
            new ForTestMemoryMapState<>();

    /** The serializer the operator's memory state uses for its values. */
    private final TypeSerializer<MemoryObjectImpl.MemoryItem> memorySerializer =
            TypeInformation.of(MemoryObjectImpl.MemoryItem.class)
                    .createSerializer(new SerializerConfigImpl());

    /** The sensory memory as checkpointed after the second tool round, by state key. */
    private final Map<String, byte[]> checkpoint = new HashMap<>();

    private final AtomicReference<byte[]> persisted = new AtomicReference<>();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final AtomicInteger summaryCalls = new AtomicInteger();
    private final List<List<ChatMessage>> chatRequests = new ArrayList<>();
    private final Map<ConfigOption<?>, Object> options = new LinkedHashMap<>();

    /** Whether the chat model named "small" can be resolved. */
    private boolean smallAvailable = true;

    /** The size of the system message the chat model's bound prompt renders; 0 for no prompt. */
    private int promptChars;

    private final AtomicInteger promptRenders = new AtomicInteger();

    private CachedMemoryStore sensoryStore;
    private ContinuationActionExecutor executor;
    private final ChatRequestEvent request =
            new ChatRequestEvent("model", List.of(new ChatMessage(MessageRole.USER, "go")));

    @AfterEach
    void closeExecutor() {
        assertThat(Thread.interrupted()).as("interrupt leaked by the action").isFalse();
        if (executor != null) {
            executor.close();
        }
    }

    /**
     * The worker dies right after the summary is journaled and before the round's chat call.
     * Recovery reuses the journaled summary without asking the summarizer again, and the model is
     * sent the summary in place of the round it covers, as in an uninterrupted run.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoreAfterTheSummaryRecordReplaysItWithoutAskingTheSummarizerAgain(boolean async)
            throws Exception {
        summarizing();
        ToolResponseEvent secondResult = runTwoRounds(async);

        restoreCheckpoint();
        RunnerContextImpl failing = context(new ActionState(secondResult), async, 2);
        assertThatThrownBy(() -> runAction(secondResult, failing)).hasMessageContaining(FAILURE);
        assertThat(summaryCalls).hasValue(1);
        assertThat(chatCalls).hasValue(2);
        assertThat(failing.getPendingEvents()).isEmpty();

        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(functionIds(restored)).containsExactly("estimate", "summarize");
        restoreCheckpoint();
        RunnerContextImpl replay = context(restored, async, Integer.MAX_VALUE);
        runAction(restored.getTaskEvent(), replay);

        assertThat(summaryCalls).hasValue(1);
        assertThat(chatCalls).hasValue(3);
        assertSummarized(replay);
        assertThat(functionIds(ActionStateSerde.deserialize(persisted.get())))
                .containsExactly("estimate", "summarize", "chat");
    }

    /** A completed round with a summary replays without a summarizer or a chat call. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replaysACompletedSummarizedRoundWithoutAnyModelCall(boolean async) throws Exception {
        summarizing();
        ToolResponseEvent secondResult = runTwoRounds(async);

        restoreCheckpoint();
        RunnerContextImpl first = context(new ActionState(secondResult), async, Integer.MAX_VALUE);
        runAction(secondResult, first);
        assertThat(summaryCalls).hasValue(1);
        assertThat(chatCalls).hasValue(3);
        assertSummarized(first);

        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(functionIds(restored)).containsExactly("estimate", "summarize", "chat");
        restoreCheckpoint();
        RunnerContextImpl replay = context(restored, async, Integer.MAX_VALUE);
        runAction(restored.getTaskEvent(), replay);

        assertThat(summaryCalls).hasValue(1);
        assertThat(chatCalls).hasValue(3);
        assertSummarized(replay);
    }

    /**
     * The summarizer could not be resolved when the round first ran, which was journaled as the
     * outcome of the summary's call, and the worker died right after. By the replay the summarizer
     * can be resolved. The replay reuses the journaled outcome: it does not ask for a summary, and
     * the model is sent the whole conversation, as it would have been in the first run.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoreAfterAJournaledSummaryFailureDoesNotAskTheSummarizerThatIsBack(boolean async)
            throws Exception {
        summarizing();
        options.put(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL, "small");
        smallAvailable = false;
        ToolResponseEvent secondResult = runTwoRounds(async);

        restoreCheckpoint();
        RunnerContextImpl failing = context(new ActionState(secondResult), async, 2);
        assertThatThrownBy(() -> runAction(secondResult, failing)).hasMessageContaining(FAILURE);
        assertThat(summaryCalls).hasValue(0);
        assertThat(chatCalls).hasValue(2);

        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(functionIds(restored)).containsExactly("estimate", "summarize");
        smallAvailable = true;
        restoreCheckpoint();
        RunnerContextImpl replay = context(restored, async, Integer.MAX_VALUE);
        runAction(restored.getTaskEvent(), replay);

        assertThat(summaryCalls).hasValue(0);
        assertThat(chatCalls).hasValue(3);
        // The model was sent the user message and both rounds, as they are stored.
        assertThat(chatRequests.get(2)).hasSize(5);
        assertThat(resultText(chatRequests.get(2).get(2))).isEqualTo("1".repeat(2000));
        ContextManagementEvent event =
                event(replay, ContextManagementEvent.EVENT_TYPE, ContextManagementEvent::fromEvent);
        assertThat(event.getDetails())
                .containsEntry("summarizer", "small")
                .containsEntry("rounds", 0);
        assertThat((String) event.getDetails().get("error"))
                .startsWith("chat model 'small' cannot be used: ")
                .contains("Resource not found: small");
        assertThat(finalText(replay)).isEqualTo("final answer");
        assertThat(functionIds(ActionStateSerde.deserialize(persisted.get())))
                .containsExactly("estimate", "summarize", "chat");
    }

    /**
     * The chat model's bound prompt renders shorter after the restore than it did in the first run,
     * as a prompt that a server returns can. By its own size the input of the replay is under the
     * trigger. The replay decides from the journaled estimate, so it reuses the journaled summary:
     * a fresh estimate would skip the summary, find the summary's record where it makes the chat
     * call, and lose it.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoreDecidesFromTheJournaledEstimateWhenThePromptRendersShorter(boolean async)
            throws Exception {
        // Two rounds are about 1030 tokens. Only with a prompt of 600 tokens are they over the
        // trigger of 1600.
        options.put(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 2000);
        options.put(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1);
        promptChars = 2400;
        ToolResponseEvent secondResult = runTwoRounds(async);

        restoreCheckpoint();
        RunnerContextImpl failing = context(new ActionState(secondResult), async, 2);
        assertThatThrownBy(() -> runAction(secondResult, failing)).hasMessageContaining(FAILURE);
        assertThat(summaryCalls).hasValue(1);
        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(functionIds(restored)).containsExactly("estimate", "summarize");

        promptChars = 0;
        restoreCheckpoint();
        RunnerContextImpl replay = context(restored, async, Integer.MAX_VALUE);
        runAction(restored.getTaskEvent(), replay);

        assertThat(summaryCalls).hasValue(1);
        assertThat(chatCalls).hasValue(3);
        assertSummarized(replay);
        assertThat(functionIds(ActionStateSerde.deserialize(persisted.get())))
                .containsExactly("estimate", "summarize", "chat");
    }

    /**
     * The other direction: the prompt renders longer after the restore, so that by its own size the
     * input of the replay is over the trigger. The round completed without a summary. Its replay
     * reuses the journaled estimate and the journaled reply: it renders no prompt and calls no
     * model, where a fresh estimate would ask for a summary and call the model a second time.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replayOfARoundWithoutASummaryStaysWithoutOneWhenThePromptRendersLonger(boolean async)
            throws Exception {
        options.put(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 2000);
        options.put(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1);
        ToolResponseEvent secondResult = runTwoRounds(async);

        restoreCheckpoint();
        RunnerContextImpl first = context(new ActionState(secondResult), async, Integer.MAX_VALUE);
        runAction(secondResult, first);
        assertThat(summaryCalls).hasValue(0);
        assertThat(chatCalls).hasValue(3);
        assertThat(chatRequests.get(2)).hasSize(5);
        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(functionIds(restored)).containsExactly("estimate", "chat");

        promptChars = 2400;
        int rendersBefore = promptRenders.get();
        restoreCheckpoint();
        RunnerContextImpl replay = context(restored, async, Integer.MAX_VALUE);
        runAction(restored.getTaskEvent(), replay);

        assertThat(summaryCalls).hasValue(0);
        assertThat(chatCalls).hasValue(3);
        assertThat(promptRenders).hasValue(rendersBefore);
        assertThat(steps(replay)).isEmpty();
        assertThat(finalText(replay)).isEqualTo("final answer");
        assertThat(functionIds(ActionStateSerde.deserialize(persisted.get())))
                .containsExactly("estimate", "chat");
    }

    /**
     * Two chat requests of one key run at the same time, each action over its own cache of the
     * persisted memory. The request that completes last clears what it kept, from a cache that
     * never saw what the other request wrote. Each request's state has a memory path of its own, so
     * the other request still has its policy.
     */
    @Test
    void requestsOfOneKeyThatOverlapKeepTheirOwnState() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ContextManagementPolicy policyOfA =
                ContextManagementPolicy.builder().toolResultMaxChars(100).build();
        CachedMemoryStore storeOfA = new CachedMemoryStore(sensoryState);
        CachedMemoryStore storeOfB = new CachedMemoryStore(sensoryState);

        ContextManager.setRequestPolicy(memory(storeOfA), a, policyOfA);
        ContextManager.setRequestPolicy(
                memory(storeOfB),
                b,
                ContextManagementPolicy.builder().toolResultMaxChars(200).build());
        // The action of request a returns a tool call and is persisted. The action of request
        // b, which started before that, answers and clears its request.
        storeOfA.persistCache();
        ContextManager.clearRequest(memory(storeOfB), b);
        storeOfB.persistCache();

        MemoryObject persistedMemory = memory(new CachedMemoryStore(sensoryState));
        assertThat(persistedMemory.get(ContextManager.STATE_KEY + "." + a).getValue())
                .isEqualTo(Map.of("policy", policyOfA.toMap()));
        assertThat(persistedMemory.get(ContextManager.STATE_KEY + "." + b).getValue())
                .isEqualTo(Map.of());
    }

    private static MemoryObject memory(CachedMemoryStore store) throws Exception {
        return new MemoryObjectImpl(
                MemoryObject.MemoryType.SENSORY, store, "", () -> {}, new ArrayList<>(), null);
    }

    /**
     * Truncation and masking add no call to the journal: the round's journal holds the chat call
     * alone, as without a policy, and a replay reuses it although the stored messages and the
     * messages that were sent differ.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void truncationAndMaskingLeaveTheJournalAsItIsWithoutThem(boolean async) throws Exception {
        options.put(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 500);
        options.put(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 1);
        ToolResponseEvent secondResult = runTwoRounds(async);

        restoreCheckpoint();
        RunnerContextImpl first = context(new ActionState(secondResult), async, Integer.MAX_VALUE);
        runAction(secondResult, first);
        assertThat(chatCalls).hasValue(3);
        // The model was sent a placeholder for round 1 and round 2 cut to the limit.
        List<ChatMessage> third = chatRequests.get(2);
        assertThat(resultText(third.get(2))).startsWith("[Tool result omitted");
        assertThat(resultText(third.get(4))).hasSize(500).contains("characters truncated");
        assertThat(steps(first)).containsExactly("truncate", "mask");

        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(functionIds(restored)).containsExactly("chat");
        restoreCheckpoint();
        RunnerContextImpl replay = context(restored, async, Integer.MAX_VALUE);
        runAction(restored.getTaskEvent(), replay);

        // The journaled reply is reused, and the same steps are reported again.
        assertThat(chatCalls).hasValue(3);
        assertThat(steps(replay)).containsExactly("truncate", "mask");
        assertThat(finalText(replay)).isEqualTo("final answer");
        assertThat(summaryCalls).hasValue(0);
    }

    // ── Assertions ─────────────────────────────────────────────────────────

    private void assertSummarized(RunnerContextImpl context) {
        // The third model call was sent the user message with the notes, then round 2.
        List<ChatMessage> third = chatRequests.get(2);
        assertThat(third).hasSize(3);
        assertThat(((TextBlock) third.get(0).getBlocks().get(1)).getText())
                .isEqualTo(
                        ContextCompaction.SUMMARY_SEPARATOR + ContextCompaction.summaryText(NOTES));
        assertThat(third.get(1).getToolCalls().get(0).getCallId()).isEqualTo("c2");
        assertThat(resultText(third.get(2))).isEqualTo("2".repeat(2000));

        ContextManagementEvent event =
                event(
                        context,
                        ContextManagementEvent.EVENT_TYPE,
                        ContextManagementEvent::fromEvent);
        assertThat(event.getRequestId()).isEqualTo(request.getId());
        assertThat(event.getStep()).isEqualTo(ContextManagementEvent.STEP_SUMMARIZE);
        assertThat(event.getDetails())
                .containsEntry("summarizer", "model")
                .containsEntry("rounds", 1)
                .containsEntry("summarized_rounds", 1)
                .containsEntry("summary_chars", NOTES.length())
                .doesNotContainKey("error");
        assertThat(finalText(context)).isEqualTo("final answer");
    }

    private static String finalText(RunnerContextImpl context) {
        ChatResponseEvent response =
                event(context, ChatResponseEvent.EVENT_TYPE, ChatResponseEvent::fromEvent);
        assertThat(response.isFailed()).isFalse();
        return response.getResponse().getText();
    }

    private static List<String> steps(RunnerContextImpl context) {
        return context.getPendingEvents().stream()
                .filter(e -> ContextManagementEvent.EVENT_TYPE.equals(e.getType()))
                .map(e -> ContextManagementEvent.fromEvent(e).getStep())
                .collect(Collectors.toList());
    }

    private static List<String> functionIds(ActionState state) {
        return state.getCallResults().stream()
                .map(CallResult::getFunctionId)
                .collect(Collectors.toList());
    }

    private static String resultText(ChatMessage toolMessage) {
        return ((ToolResultBlock) toolMessage.getBlocks().get(0)).getText();
    }

    private static <T> T event(RunnerContextImpl context, String type, Function<Event, T> read) {
        return context.getPendingEvents().stream()
                .filter(e -> type.equals(e.getType()))
                .map(read)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no event of type " + type));
    }

    // ── The request ────────────────────────────────────────────────────────

    /** Summarize above 800 estimated tokens, keeping the last round as it is. */
    private void summarizing() {
        options.put(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1000);
        options.put(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1);
    }

    /**
     * Runs the chat request and its first tool round to completion, persisting memory after each
     * action as the operator does, and returns the result of the second tool round. Each round's
     * result is about 500 estimated tokens.
     */
    private ToolResponseEvent runTwoRounds(boolean async) throws Exception {
        RunnerContextImpl first = context(new ActionState(request), async, Integer.MAX_VALUE);
        runAction(request, first);
        sensoryStore.persistCache();
        ToolResponseEvent firstResult = toolResult(first, "c1", "1".repeat(2000));

        RunnerContextImpl second = context(new ActionState(firstResult), async, Integer.MAX_VALUE);
        runAction(firstResult, second);
        sensoryStore.persistCache();
        assertThat(chatCalls).hasValue(2);
        assertThat(summaryCalls).hasValue(0);
        takeCheckpoint();
        return toolResult(second, "c2", "2".repeat(2000));
    }

    /** Serializes the persisted sensory memory, as a checkpoint does. */
    private void takeCheckpoint() throws Exception {
        checkpoint.clear();
        for (Map.Entry<String, MemoryObjectImpl.MemoryItem> entry : sensoryState.entries()) {
            DataOutputSerializer out = new DataOutputSerializer(1024);
            memorySerializer.serialize(entry.getValue(), out);
            checkpoint.put(entry.getKey(), out.getCopyOfBuffer());
        }
    }

    /**
     * Puts the sensory memory back to the checkpoint, as a restart does: what an interrupted action
     * changed is gone, and every value is a fresh object read from its serialized form.
     */
    private void restoreCheckpoint() throws Exception {
        List<String> keys = new ArrayList<>();
        sensoryState.keys().forEach(keys::add);
        for (String key : keys) {
            sensoryState.remove(key);
        }
        for (Map.Entry<String, byte[]> entry : checkpoint.entrySet()) {
            sensoryState.put(
                    entry.getKey(),
                    memorySerializer.deserialize(new DataInputDeserializer(entry.getValue())));
        }
    }

    private static ToolResponseEvent toolResult(
            RunnerContextImpl context, String callId, String text) {
        ToolRequestEvent toolRequest =
                event(context, ToolRequestEvent.EVENT_TYPE, ToolRequestEvent::fromEvent);
        return new ToolResponseEvent(
                toolRequest.getId(),
                Map.of(callId, ToolResponse.text(text)),
                Map.of(callId, true),
                Map.of());
    }

    /** Calls the lookup tool on its first two calls, then answers; writes notes when asked. */
    private BaseChatModelSetup chatModel() {
        return new BaseChatModelSetup(new ResourceDescriptor("fake", Map.of()), null) {
            @Override
            public Map<String, Object> getParameters() {
                return Map.of();
            }

            @Override
            public boolean willApplyNativeStructuredOutput(Object outputSchema) {
                return false;
            }

            @Override
            public List<ChatMessage> prepareRequestMessages(
                    List<ChatMessage> messages, Map<String, Object> promptArgs) {
                promptRenders.incrementAndGet();
                if (promptChars == 0) {
                    return messages;
                }
                List<ChatMessage> withPrompt = new ArrayList<>();
                withPrompt.add(new ChatMessage(MessageRole.SYSTEM, "p".repeat(promptChars)));
                withPrompt.addAll(messages);
                return withPrompt;
            }

            @Override
            public ChatResult chat(
                    List<ChatMessage> messages,
                    Map<String, Object> promptArgs,
                    Map<String, Object> modelParams) {
                int call = chatCalls.incrementAndGet();
                chatRequests.add(List.copyOf(messages));
                if (call <= 2) {
                    return new ChatResult(
                            ChatMessage.assistant(
                                    List.of(
                                            new ToolCallBlock(
                                                    "c" + call, "lookup", Map.of("q", call)))));
                }
                return new ChatResult(new ChatMessage(MessageRole.ASSISTANT, "final answer"));
            }

            @Override
            public ChatResult chatWithoutTools(List<ChatMessage> messages) {
                summaryCalls.incrementAndGet();
                return new ChatResult(new ChatMessage(MessageRole.ASSISTANT, NOTES));
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RunnerContextImpl context(ActionState state, boolean async, int failAfterCallResults)
            throws Exception {
        AgentPlan plan = new AgentPlan(Map.of());
        plan.getConfig().set(AgentExecutionOptions.CHAT_ASYNC, async);
        for (Map.Entry<ConfigOption<?>, Object> option : options.entrySet()) {
            plan.getConfig().set((ConfigOption) option.getKey(), option.getValue());
        }
        BaseChatModelSetup model = chatModel();
        ResourceCache resources = mock(ResourceCache.class);
        when(resources.getResource("model", ResourceType.CHAT_MODEL)).thenReturn(model);
        if (smallAvailable) {
            when(resources.getResource("small", ResourceType.CHAT_MODEL)).thenReturn(model);
        } else {
            when(resources.getResource("small", ResourceType.CHAT_MODEL))
                    .thenThrow(new IllegalArgumentException("Resource not found: small"));
        }
        FlinkAgentsMetricGroupImpl metrics =
                new FlinkAgentsMetricGroupImpl(
                        UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup());
        RunnerContextImpl context;
        if (async) {
            assumeTrue(Runtime.version().feature() >= 21, "async recovery needs JDK 21+");
            assertThat(ContinuationActionExecutor.isContinuationSupported()).isTrue();
            if (executor == null) {
                executor = new ContinuationActionExecutor(1);
            }
            JavaRunnerContextImpl asyncContext =
                    new JavaRunnerContextImpl(
                            metrics, () -> {}, plan, resources, "test-job", executor);
            asyncContext.setContinuationContext(new ContinuationContext());
            context = asyncContext;
        } else {
            context = new RunnerContextImpl(metrics, () -> {}, plan, resources, "test-job");
        }
        context.setOutputIdentities(new RuntimeIdentity.OutputIdentityAllocator("test-execution"));
        context.actionName = "chat_model_action";
        // A fresh cache over the persisted memory: what an action wrote is visible to the next
        // one only after it was persisted.
        sensoryStore = new CachedMemoryStore(sensoryState);
        context.memoryContext =
                new RunnerContextImpl.MemoryContext(
                        sensoryStore, new CachedMemoryStore(shortTermState));
        context.durableExecutionContext =
                new RunnerContextImpl.DurableExecutionContext(
                        "test-key",
                        1L,
                        ChatModelAction.getChatModelAction(),
                        state.getTaskEvent(),
                        state,
                        (key, sequence, action, event, current) -> {
                            persisted.set(ActionStateSerde.serialize(current));
                            if (current.getCallResultCount() == failAfterCallResults) {
                                throw new IllegalStateException(FAILURE);
                            }
                        });
        return context;
    }

    private void runAction(Event event, RunnerContextImpl context) throws Exception {
        if (!(context instanceof JavaRunnerContextImpl)) {
            ChatModelAction.processChatRequestOrToolResponse(event, context);
            return;
        }
        JavaRunnerContextImpl asyncContext = (JavaRunnerContextImpl) context;
        AtomicReference<Exception> failure = new AtomicReference<>();
        Runnable action =
                () -> {
                    try {
                        ChatModelAction.processChatRequestOrToolResponse(event, context);
                    } catch (Exception error) {
                        failure.set(error);
                    }
                };
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!executor.executeAction(asyncContext.getContinuationContext(), action)) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.yield();
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
