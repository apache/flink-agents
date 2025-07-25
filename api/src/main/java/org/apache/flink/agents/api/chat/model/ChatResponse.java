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

import org.apache.flink.agents.api.util.CollectionUtils;

import java.util.List;
import java.util.Objects;

public class ChatResponse {

    /** List of generated messages returned by the AI provider. */
    private final List<Generation> generations;

    // TODO Add metadata about the response, such as model used, id, rate limit, etc.

    /**
     * Construct a new {@link ChatResponse} instance.
     *
     * @param generations the {@link List} of {@link Generation} returned by the AI provider.
     */
    public ChatResponse(List<Generation> generations) {
        if (generations == null) {
            throw new IllegalArgumentException("Generations cannot be null");
        }
        this.generations = List.copyOf(generations);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The {@link List} of {@link Generation generated outputs}.
     *
     * @return the {@link List} of {@link Generation generated outputs}.
     */
    public List<Generation> getResults() {
        return this.generations;
    }

    /** @return Returns the first {@link Generation} in the generations list. */
    public Generation getResult() {
        if (CollectionUtils.isEmpty(this.generations)) {
            return null;
        }
        return this.generations.get(0);
    }

    /** Whether the model has requested the execution of a tool. */
    public boolean hasToolCalls() {
        if (CollectionUtils.isEmpty(this.generations)) {
            return false;
        }
        return this.generations.stream()
                .anyMatch(generation -> generation.getOutput().hasToolCalls());
    }

    @Override
    public String toString() {
        return "ChatResponse [generations=" + this.generations + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChatResponse)) {
            return false;
        }
        ChatResponse that = (ChatResponse) o;
        return Objects.equals(this.generations, that.generations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.generations);
    }

    public static final class Builder {

        private List<Generation> generations;

        public Builder from(ChatResponse other) {
            this.generations = other.generations;
            return this;
        }

        public Builder generations(List<Generation> generations) {
            this.generations = generations;
            return this;
        }

        public ChatResponse build() {
            return new ChatResponse(this.generations);
        }
    }
}
