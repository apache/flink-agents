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
package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PythonPrompt is a subclass of Prompt that provides a method to parse a Python prompt from a
 * serialized map.
 */
public class PythonPrompt extends Prompt {
    public PythonPrompt(String template) {
        super(template);
    }

    public PythonPrompt(List<ChatMessage> template) {
        super(template);
    }

    public static PythonPrompt fromSerializedMap(Map<String, Object> serialized) {
        if (serialized == null || !serialized.containsKey("template")) {
            throw new IllegalArgumentException("Map must contain 'template' key");
        }

        Object templateObj = serialized.get("template");
        if (templateObj instanceof String) {
            return new PythonPrompt((String) templateObj);
        } else if (templateObj instanceof List) {
            List<?> templateList = (List<?>) templateObj;
            if (templateList.isEmpty()) {
                throw new IllegalArgumentException("Template list cannot be empty");
            }

            List<ChatMessage> messages = new ArrayList<>();
            for (Object item : templateList) {
                if (!(item instanceof Map)) {
                    throw new IllegalArgumentException("Each template item must be a Map");
                }

                Map<String, Object> messageMap = (Map<String, Object>) item;
                ChatMessage chatMessage = parseChatMessage(messageMap);
                messages.add(chatMessage);
            }

            return new PythonPrompt(messages);
        }
        throw new IllegalArgumentException(
                "Python prompt parsing failed. Template is not a string or list.");
    }

    /** Parse a single ChatMessage from a Map representation. */
    @SuppressWarnings("unchecked")
    private static ChatMessage parseChatMessage(Map<String, Object> messageMap) {
        String roleValue = messageMap.get("role").toString();
        MessageRole role = MessageRole.fromValue(roleValue);

        Object contentObj = messageMap.get("content");
        String content = contentObj != null ? contentObj.toString() : "";

        List<Map<String, Object>> toolCalls =
                (List<Map<String, Object>>) messageMap.get("tool_calls");

        Map<String, Object> extraArgs = (Map<String, Object>) messageMap.get("extra_args");

        return new ChatMessage(role, content, toolCalls, extraArgs);
    }
}
