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

package org.apache.flink.agents.api.chat.model;

import org.apache.flink.agents.api.chat.messages.AssistantMessage;

import java.util.Objects;

/**
 * Represents a response returned by the AI model. This class encapsulates the assistant's message
 * output from a chat generation.
 */
public class Generation {

    private final AssistantMessage assistantMessage;

    /**
     * Creates a new Generation with the specified assistant message.
     *
     * @param assistantMessage the assistant message output
     * @throws IllegalArgumentException if assistantMessage is null
     */
    public Generation(AssistantMessage assistantMessage) {
        if (assistantMessage == null) {
            throw new IllegalArgumentException("Assistant message cannot be null");
        }
        this.assistantMessage = assistantMessage;
    }

    /**
     * Gets the assistant message output.
     *
     * @return the assistant message
     */
    public AssistantMessage getOutput() {
        return this.assistantMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Generation)) {
            return false;
        }
        Generation that = (Generation) o;
        return Objects.equals(this.assistantMessage, that.assistantMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.assistantMessage);
    }

    @Override
    public String toString() {
        return "Generation[assistantMessage=" + this.assistantMessage + ']';
    }
}
