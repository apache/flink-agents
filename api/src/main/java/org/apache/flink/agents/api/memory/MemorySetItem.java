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
package org.apache.flink.agents.api.memory;

import javax.annotation.Nullable;

import java.time.LocalDateTime;
import java.util.Map;

/** Represents a long term memory item. */
public class MemorySetItem {
    private final String memorySetName;
    private final String id;
    private final String value;
    private final @Nullable LocalDateTime createdAt;
    private final @Nullable LocalDateTime updatedAt;
    private final @Nullable Map<String, Object> additionalMetadata;

    public MemorySetItem(
            String memorySetName,
            String id,
            String value,
            @Nullable LocalDateTime createdAt,
            @Nullable LocalDateTime updatedAt,
            @Nullable Map<String, Object> additionalMetadata) {
        this.memorySetName = memorySetName;
        this.id = id;
        this.value = value;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.additionalMetadata = additionalMetadata;
    }

    public String getMemorySetName() {
        return memorySetName;
    }

    public String getId() {
        return id;
    }

    public String getValue() {
        return value;
    }

    public @Nullable LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public @Nullable LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public @Nullable Map<String, Object> getAdditionalMetadata() {
        return additionalMetadata;
    }
}
