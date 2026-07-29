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

package org.apache.flink.agents.api.yaml.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Declarative prompt: either a single text template or a list of role-tagged messages. */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class PromptSpec {
    private final String name;
    private final String text;
    private final List<PromptMessage> messages;

    @JsonCreator
    public PromptSpec(
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty("text") String text,
            @JsonProperty("messages") List<PromptMessage> messages) {
        this.name = name;
        this.text = text;
        this.messages = messages;
        boolean hasText = text != null && !text.isEmpty();
        boolean hasMessages = messages != null && !messages.isEmpty();
        if (hasText == hasMessages) {
            throw new IllegalArgumentException(
                    "prompt must define exactly one non-empty 'text' or 'messages'");
        }
    }

    public String getName() {
        return name;
    }

    public String getText() {
        return text;
    }

    public List<PromptMessage> getMessages() {
        return messages;
    }
}
