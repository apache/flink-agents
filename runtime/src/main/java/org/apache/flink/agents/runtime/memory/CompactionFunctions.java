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
package org.apache.flink.agents.runtime.memory;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.memory.BaseLongTermMemory;
import org.apache.flink.agents.api.memory.MemorySet;
import org.apache.flink.agents.api.memory.MemorySetItem;
import org.apache.flink.agents.api.memory.compaction.CompactionConfig;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.flink.agents.runtime.memory.VectorStoreLongTermMemory.formatter;
import static org.apache.flink.agents.runtime.memory.VectorStoreLongTermMemory.mapper;

public class CompactionFunctions {
    private static final Logger LOG = LoggerFactory.getLogger(CompactionFunctions.class);

    private static Prompt DEFAULT_ANALYSIS_PROMPT =
            Prompt.fromText(
                    "<role>\n"
                            + "Context Summarize Assistant\n"
                            + "</role>\n"
                            + "\n"
                            + "<primary_objective>\n"
                            + "Your sole objective in this task is to summarize the context above.\n"
                            + "</primary_objective>\n"
                            + "\n"
                            + "<objective_information>\n"
                            + "You're nearing the total number of input tokens you can accept, so you need compact the context. To achieve this objective, you should extract important topics. Notice,\n"
                            + "**The topics must no more than {limit}**. Afterwards, you should generate summarization for each topic, and record indices of the messages the summary was derived from. "
                            + "**There are {count} messages totally, indexed from 0 to {end}, DO NOT omit any message, even if irrelevant**. The messages involved in each topic must not overlap, and their union must equal the entire set of messages.\n"
                            + "</objective_information>\n"
                            + "\n"
                            + "<output_example>\n"
                            + "You must always respond with valid json format in this format:\n"
                            + "{\"topic1\": {\"summarization\": \"User ask what is 1 * 2, and the result is 3.\", \"messages\": [0,1,2,3]},\n"
                            + " ...\n"
                            + " \"topic4\": {\"summarization\": \"User ask what's the weather tomorrow, llm use the search_weather, and the answer is snow.\", \"messages\": [9,10,11,12]}\n"
                            + "}\n"
                            + "</output_example>");

    /**
     * Generate summarization of the items in the memory set.
     *
     * <p>This method will add the summarization to memory set, and delete original items involved
     * in summarization.
     *
     * @param ltm The long term memory the memory set belongs to.
     * @param memorySet The memory set to be summarized.
     * @param ctx The runner context used to retrieve needed resources.
     * @param ids The ids of items to be summarized. If not provided, all items will be involved in
     *     summarization. Optional.
     */
    @SuppressWarnings("unchecked")
    public static void summarize(
            BaseLongTermMemory ltm,
            MemorySet memorySet,
            RunnerContext ctx,
            @Nullable List<String> ids)
            throws Exception {
        CompactionConfig compactionConfig = memorySet.getCompactionConfig();

        List<MemorySetItem> items = ltm.get(memorySet, ids);
        ChatMessage response =
                generateSummarization(items, memorySet.getItemType(), compactionConfig, ctx);

        LOG.debug("Items to be summarized: {}\n, Summarization: {}", items, response.getContent());

        Map<String, Map<String, Object>> topics =
                mapper.readValue(response.getContent(), Map.class);

        for (Map<String, Object> topic : topics.values()) {
            String summarization = (String) topic.get("summarization");
            List<Integer> indices = (List<Integer>) topic.get("messages");

            if (compactionConfig.getLimit() == 1) {
                indices = IntStream.range(0, items.size()).boxed().collect(Collectors.toList());
            }

            Object item;
            if (memorySet.getItemType() == ChatMessage.class) {
                item = new ChatMessage(MessageRole.USER, summarization);
            } else {
                item = summarization;
            }

            List<LocalDateTime> created_times = new ArrayList<>();
            List<LocalDateTime> lastAccessedTimes = new ArrayList<>();
            List<String> itemIds = new ArrayList<>();
            LocalDateTime start = LocalDateTime.MAX;
            LocalDateTime end = LocalDateTime.MAX;
            LocalDateTime lastAccessed = LocalDateTime.MIN;
            for (int index : indices) {
                if (items.get(index).isCompacted()) {
                    MemorySetItem.DateTimeRange range =
                            ((MemorySetItem.DateTimeRange) items.get(index).getCreatedTime());
                    start = start.isBefore(range.getStart()) ? start : range.getStart();
                    end = end.isAfter(range.getEnd()) ? end : range.getEnd();
                } else {
                    LocalDateTime point = (LocalDateTime) items.get(index).getCreatedTime();
                    start = start.isBefore(point) ? start : point;
                    end = end.isAfter(point) ? end : point;
                }

                LocalDateTime point = items.get(index).getLastAccessedTime();
                lastAccessed = lastAccessed.isAfter(point) ? lastAccessed : point;

                itemIds.add(items.get(index).getId());
            }

            ltm.delete(memorySet, itemIds);

            ltm.add(
                    memorySet,
                    Collections.singletonList(item),
                    null,
                    Collections.singletonList(
                            Map.of(
                                    "compacted",
                                    true,
                                    "created_time_start",
                                    start.format(formatter),
                                    "created_time_end",
                                    end.format(formatter),
                                    "last_accessed_time",
                                    lastAccessed.format(formatter))));
        }
    }

    // TODO: support batched summarize.
    private static ChatMessage generateSummarization(
            List<MemorySetItem> items,
            Class<?> itemType,
            CompactionConfig compactionConfig,
            RunnerContext ctx)
            throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        if (itemType == ChatMessage.class) {
            for (MemorySetItem item : items) {
                messages.add((ChatMessage) item.getValue());
            }
        } else {
            for (MemorySetItem item : items) {
                messages.add(new ChatMessage(MessageRole.USER, String.valueOf(item.getValue())));
            }
        }

        BaseChatModelSetup model =
                (BaseChatModelSetup)
                        ctx.getResource(compactionConfig.getModel(), ResourceType.CHAT_MODEL);

        Object prompt = compactionConfig.getPrompt();
        if (prompt != null) {
            if (prompt instanceof String) {
                prompt = ctx.getResource((String) prompt, ResourceType.PROMPT);
            }

            Map<String, String> variables = new HashMap<>();
            for (ChatMessage msg : messages) {
                for (Map.Entry<String, Object> pair : msg.getExtraArgs().entrySet()) {
                    variables.put(pair.getKey(), String.valueOf(pair.getValue()));
                }
            }

            List<ChatMessage> promptMsg =
                    ((Prompt) prompt).formatMessages(MessageRole.USER, variables);
            messages.addAll(promptMsg);
        } else {
            messages.addAll(
                    DEFAULT_ANALYSIS_PROMPT.formatMessages(
                            MessageRole.SYSTEM,
                            Map.of(
                                    "limit",
                                    String.valueOf(compactionConfig.getLimit()),
                                    "count",
                                    String.valueOf(items.size()),
                                    "end",
                                    String.valueOf(items.size() - 1))));
        }

        return model.chat(messages);
    }
}
