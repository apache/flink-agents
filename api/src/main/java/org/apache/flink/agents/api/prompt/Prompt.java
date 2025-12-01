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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;

import java.util.List;
import java.util.Map;

/**
 * Abstract base class for prompts.
 *
 * <p>This is the base class for all prompt implementations in Flink Agents. Subclasses must
 * implement the formatting methods to generate text or messages from templates.
 *
 * <p>Common implementations:
 *
 * <ul>
 *   <li>{@link LocalPrompt} - Template-based prompts with placeholders
 *   <li>{@link org.apache.flink.agents.api.mcp.MCPPrompt} - Dynamic prompts from MCP servers
 * </ul>
 *
 * @see LocalPrompt
 * @see org.apache.flink.agents.api.mcp.MCPPrompt
 */
public abstract class Prompt extends SerializableResource {

    /**
     * Create a prompt from a text string template.
     *
     * @param text The text template with placeholders like {variable}
     * @return A LocalPrompt instance
     */
    public static Prompt fromText(String text) {
        return new LocalPrompt(text);
    }

    /**
     * Create a prompt from a sequence of chat messages.
     *
     * @param messages The list of chat messages forming the prompt template
     * @return A LocalPrompt instance
     */
    public static Prompt fromMessages(List<ChatMessage> messages) {
        return new LocalPrompt(messages);
    }

    /**
     * Generate a text string from the prompt template with additional arguments.
     *
     * @param kwargs Key-value pairs to substitute in the template
     * @return The formatted prompt as a string
     */
    public abstract String formatString(Map<String, String> kwargs);

    /**
     * Generate a list of ChatMessage from the prompt template with additional arguments.
     *
     * @param defaultRole The default message role (usually SYSTEM)
     * @param kwargs Key-value pairs to substitute in the template
     * @return List of formatted chat messages
     */
    public abstract List<ChatMessage> formatMessages(
            MessageRole defaultRole, Map<String, String> kwargs);

    @JsonIgnore
    @Override
    public ResourceType getResourceType() {
        return ResourceType.PROMPT;
    }
}
