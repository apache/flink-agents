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

import java.util.List;
import java.util.function.Function;

/** Template for prompts that can be either a string or a sequence of ChatMessages. */
public abstract class PromptTemplate {

    /** Create a string-based template. */
    public static PromptTemplate fromString(String content) {
        return new StringTemplate(content);
    }

    /** Create a message-based template. */
    public static PromptTemplate fromMessages(List<ChatMessage> messages) {
        return new MessagesTemplate(messages);
    }

    /**
     * Pattern matching method for type-safe operations. This replaces instanceof checks and
     * casting.
     */
    public abstract <T> T match(
            Function<String, T> onString, Function<List<ChatMessage>, T> onMessages);

    /** Check if this is a string template. */
    public abstract boolean isStringTemplate();

    /** Check if this is a messages template. */
    public abstract boolean isMessageTemplate();
}
