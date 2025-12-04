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

package org.apache.flink.agents.api.chat.messages;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Chat message class that represents all message types (user, system, assistant, tool) with
 * different roles
 */
public class ChatMessage {

    private MessageRole role;
    private String content;
    private List<Map<String, Object>> toolCalls;
    private Map<String, Object> extraArgs;

    /** Default constructor with SYSTEM role */
    public ChatMessage() {
        this(MessageRole.SYSTEM, null, null, null);
    }

    /** Constructor with role and content */
    public ChatMessage(MessageRole role, String content) {
        this(role, content, null, null);
    }

    public ChatMessage(MessageRole role, String content, Map<String, Object> extraArgs) {
        this(role, content, null, extraArgs);
    }

    public ChatMessage(MessageRole role, String content, List<Map<String, Object>> toolCalls) {
        this(role, content, toolCalls, null);
    }

    /** Full constructor */
    public ChatMessage(
            MessageRole role,
            String content,
            List<Map<String, Object>> toolCalls,
            Map<String, Object> extraArgs) {
        this.role = role != null ? role : MessageRole.SYSTEM;
        this.content = content != null ? content : "";
        this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<>();
        this.extraArgs = extraArgs != null ? new HashMap<>(extraArgs) : new HashMap<>();
    }

    public MessageRole getRole() {
        return role;
    }

    public void setRole(MessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<Map<String, Object>> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<Map<String, Object>> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public Map<String, Object> getExtraArgs() {
        return extraArgs;
    }

    public void setExtraArgs(Map<String, Object> extraArgs) {
        this.extraArgs = extraArgs != null ? extraArgs : new HashMap<>();
    }

    @JsonIgnore
    public String getText() {
        return this.content;
    }

    public Map<String, Object> getMetadata() {
        return this.extraArgs;
    }

    @JsonIgnore
    public MessageRole getMessageType() {
        return this.role;
    }

    // Static factory methods for convenience
    public static ChatMessage user(String content) {
        return new ChatMessage(MessageRole.USER, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(MessageRole.SYSTEM, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(MessageRole.ASSISTANT, content);
    }

    public static ChatMessage assistant(String content, List<Map<String, Object>> toolCalls) {
        return new ChatMessage(MessageRole.ASSISTANT, content, toolCalls, new HashMap<>());
    }

    public static ChatMessage tool(String content) {
        return new ChatMessage(MessageRole.TOOL, content);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChatMessage)) return false;
        ChatMessage that = (ChatMessage) o;
        return Objects.equals(role, that.role)
                && Objects.equals(content, that.content)
                && Objects.equals(toolCalls, that.toolCalls)
                && Objects.equals(extraArgs, that.extraArgs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, content, toolCalls, extraArgs);
    }

    @Override
    public String toString() {
        return role.getValue() + ": " + content;
    }
}
