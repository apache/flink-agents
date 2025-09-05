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

package org.apache.flink.agents.api.prompt;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Prompt for a language model
 *
 * <p>The template can be either a string or a sequence of ChatMessage objects.
 */
public class Prompt extends SerializableResource {

    private final PromptTemplate template;
    private final String name;

    public Prompt(String name, String template) {
        this.template = PromptTemplate.fromString(template);
        this.name = name;
    }

    public Prompt(String name, List<ChatMessage> template) {
        this.template = PromptTemplate.fromMessages(template);
        this.name = name;
    }

    public String formatString(Map<String, String> kwargs) {
        return template.match(
                // Handle string template
                content -> PromptUtils.format(content, kwargs),
                // Handle messages template
                messages -> {
                    List<String> formattedMessages = new ArrayList<>();
                    for (ChatMessage message : messages) {
                        String formattedContent = PromptUtils.format(message.getContent(), kwargs);
                        String formatted = message.getRole().getValue() + ": " + formattedContent;
                        formattedMessages.add(formatted);
                    }
                    return String.join("\n", formattedMessages);
                });
    }

    public List<ChatMessage> formatMessages(MessageRole defaultRole, Map<String, String> kwargs) {
        return template.match(
                // Handle string template
                content ->
                        Collections.singletonList(
                                new ChatMessage(defaultRole, PromptUtils.format(content, kwargs))),
                // Handle messages template
                messages ->
                        messages.stream()
                                .map(
                                        message ->
                                                new ChatMessage(
                                                        message.getRole(),
                                                        PromptUtils.format(
                                                                message.getContent(), kwargs)))
                                .collect(Collectors.toList()));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.PROMPT;
    }
}
