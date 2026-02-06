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
package org.apache.flink.agents.api.memory.compaction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import javax.annotation.Nullable;

import java.util.Objects;

/** Configuration for long-term memory compaction. */
public class CompactionConfig {
    private final String model;

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.CLASS,
            include = JsonTypeInfo.As.PROPERTY,
            property = "@class")
    private final Object prompt;

    private final int limit;

    public CompactionConfig(String model, int limit) {
        this(model, null, limit);
    }

    @JsonCreator
    public CompactionConfig(
            @JsonProperty("model") String model,
            @Nullable @JsonProperty("prompt") Object prompt,
            @JsonProperty("limit") int limit) {
        this.model = model;
        this.prompt = prompt;
        this.limit = limit;
    }

    public String getModel() {
        return model;
    }

    public Object getPrompt() {
        return prompt;
    }

    public int getLimit() {
        return limit;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        CompactionConfig that = (CompactionConfig) o;
        return limit == that.limit
                && Objects.equals(model, that.model)
                && Objects.equals(prompt, that.prompt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(model, prompt, limit);
    }

    @Override
    public String toString() {
        return "CompactionConfig{"
                + "model='"
                + model
                + '\''
                + ", prompt="
                + prompt
                + ", limit="
                + limit
                + '}';
    }
}
