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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.utils.CancellationUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The request view a model-backed routing strategy works from, shared by the LLM judge and the
 * decision-model strategy so neither depends on the other's internals: the effective message list
 * (the anchor candidate's bound prompt rendered, as the selected model will receive it), the opt-in
 * context budget, and the projection of a message into text-and-media content with tool
 * interactions framed as data.
 */
final class RoutingInput {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RoutingInput() {}

    /**
     * A routing strategy judges what the selected model will actually receive. When the target
     * setup binds a {@code Prompt}, this mirrors {@code BaseChatModelSetup#chat}: the template is
     * rendered with the request's prompt args and prepended to the non-empty conversation messages.
     * The rendering anchor is the router's default candidate (or the first candidate) — where
     * abstains resolve, and in practice the workload-level prompt shared by the candidates. If the
     * anchor can't be resolved or binds no prompt, the raw message list is used unchanged.
     * Cancellation and interruption are not failures of the anchor: they propagate.
     */
    static List<ChatMessage> effectiveMessages(RoutingContext context, RunnerContext ctx)
            throws Exception {
        List<ChatMessage> messages = context.getMessages();
        String anchor =
                context.getDefaultModel().orElseGet(() -> context.getCandidates().get(0).getName());
        try {
            BaseChatModelSetup setup =
                    (BaseChatModelSetup) ctx.getResource(anchor, ResourceType.CHAT_MODEL);
            // One shared implementation with the chat path (prepareRequestMessages), so the
            // strategy's view cannot drift from what the selected model receives. Candidates that
            // bind DIFFERENT prompts see their own rendering only at answer time; the anchor (the
            // default candidate, where abstains resolve) is a documented approximation.
            return setup.prepareRequestMessages(messages, context.getPromptArgs());
        } catch (Exception unresolvable) {
            CancellationUtils.rethrowIfCancellation(unresolvable);
            // An unresolvable candidate surfaces on the real chat path with its normal policy.
            return messages;
        }
    }

    /**
     * Indices of effective messages that were <i>generated</i> by the anchor's request shaping
     * (rendered template, skill-discovery prompt) rather than taken from the conversation —
     * identified by object identity, since {@code prepareRequestMessages} appends the original
     * message instances unchanged. They carry the task definition, so the context cap pins them.
     */
    static Set<Integer> pinnedRenderedIndices(
            List<ChatMessage> original, List<ChatMessage> effective) {
        if (effective == original) {
            return Set.of();
        }
        Set<ChatMessage> originals = Collections.newSetFromMap(new IdentityHashMap<>());
        originals.addAll(original);
        Set<Integer> pinned = new LinkedHashSet<>();
        for (int i = 0; i < effective.size(); i++) {
            if (!originals.contains(effective.get(i))) {
                pinned.add(i);
            }
        }
        return pinned;
    }

    static int maxContextChars(RoutingStrategy strategy) {
        Object cap = strategy.getArguments().get(RoutingStrategy.ARG_MAX_CONTEXT_CHARS);
        return cap instanceof Number ? ((Number) cap).intValue() : Integer.MAX_VALUE;
    }

    /**
     * Applies the opt-in context budget: SYSTEM messages and the newest message are pinned, the
     * remaining messages fill newest-first, and dropped messages set {@code truncatedOut[0]}.
     * Without a cap this returns the input unchanged.
     */
    static List<ChatMessage> selectWithinBudget(
            List<ChatMessage> messages,
            int maxChars,
            Set<Integer> pinnedIndices,
            boolean[] truncatedOut) {
        if (messages == null) {
            return List.of();
        }
        if (maxChars == Integer.MAX_VALUE) {
            return messages;
        }
        long total = 0;
        for (ChatMessage message : messages) {
            total += length(message);
        }
        if (total <= maxChars) {
            return messages;
        }
        Set<Integer> kept = new LinkedHashSet<>();
        long budget = maxChars;
        // Pinned first — even if they alone exceed the budget: SYSTEM messages (the task
        // framing), messages generated by the anchor's request shaping (the rendered template
        // carries the task definition), and the newest message (the request being routed).
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getRole() == MessageRole.SYSTEM || pinnedIndices.contains(i)) {
                kept.add(i);
                budget -= length(messages.get(i));
            }
        }
        int newest = messages.size() - 1;
        if (!kept.contains(newest)) {
            kept.add(newest);
            budget -= length(messages.get(newest));
        }
        // Then newest-first within what remains.
        for (int i = newest - 1; i >= 0; i--) {
            if (kept.contains(i)) {
                continue;
            }
            long len = length(messages.get(i));
            if (len <= budget) {
                kept.add(i);
                budget -= len;
            }
        }
        truncatedOut[0] = kept.size() < messages.size();
        List<ChatMessage> selected = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (kept.contains(i)) {
                selected.add(messages.get(i));
            }
        }
        return selected;
    }

    /** Counts rendered content text; media stays attached to its message, without a byte budget. */
    private static long length(ChatMessage message) {
        return content(message).stream()
                .filter(block -> block instanceof TextBlock)
                .mapToLong(block -> ((TextBlock) block).getText().length())
                .sum();
    }

    /** Frames conversation roles as data while preserving the order and payload of media blocks. */
    static List<DataContentBlock> renderConversation(List<ChatMessage> messages) {
        List<DataContentBlock> rendered = new ArrayList<>();
        for (ChatMessage message : messages) {
            List<DataContentBlock> content = content(message);
            if (content.isEmpty()) {
                continue;
            }
            String prefix = rendered.isEmpty() ? "" : "\n";
            rendered.add(new TextBlock(prefix + message.getRole().name() + ": "));
            rendered.addAll(content);
        }
        return rendered;
    }

    /**
     * Projects one message into strategy input (judge prompt or decision-model text). Tool
     * interactions are conversation data, not executable calls to the strategy's model. Reasoning
     * and provider continuation metadata are not forwarded.
     */
    static List<DataContentBlock> content(ChatMessage message) {
        List<DataContentBlock> content = new ArrayList<>();
        for (ContentBlock block : message.getBlocks()) {
            if (block instanceof TextBlock) {
                if (!((TextBlock) block).getText().isEmpty()) {
                    content.add((TextBlock) block);
                }
            } else if (block instanceof DataContentBlock) {
                content.add((DataContentBlock) block);
            } else if (block instanceof ToolCallBlock) {
                ToolCallBlock call = (ToolCallBlock) block;
                content.add(
                        new TextBlock(
                                "\nTool call "
                                        + toolDescription(
                                                Map.of(
                                                        "call_id", call.getCallId(),
                                                        "name", call.getName(),
                                                        "input", call.getInput()))
                                        + "\n"));
            } else if (block instanceof ToolResultBlock) {
                ToolResultBlock result = (ToolResultBlock) block;
                content.add(
                        new TextBlock(
                                "Tool result "
                                        + toolDescription(
                                                Map.of(
                                                        "call_id", result.getCallId(),
                                                        "is_error", result.isError()))
                                        + ":\n"));
                content.addAll(result.getBlocks());
            }
        }
        return content;
    }

    private static String toolDescription(Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot render tool interaction for routing", e);
        }
    }
}
