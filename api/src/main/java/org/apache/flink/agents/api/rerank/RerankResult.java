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

package org.apache.flink.agents.api.rerank;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;

/** Ranked documents from one synchronous rerank call. */
public final class RerankResult {
    private final List<RerankHit> results;
    private final Map<String, Object> usage;
    @Nullable private final String requestId;

    @JsonCreator
    public RerankResult(
            @JsonProperty(value = "results", required = true) List<RerankHit> results,
            @JsonProperty("usage") @Nullable Map<String, Object> usage,
            @JsonProperty("request_id") @Nullable String requestId) {
        this.results = results == null ? List.of() : List.copyOf(results);
        this.usage = usage == null ? Map.of() : Map.copyOf(usage);
        this.requestId = requestId;
    }

    public List<RerankHit> getResults() {
        return results;
    }

    public Map<String, Object> getUsage() {
        return usage;
    }

    @JsonProperty("request_id")
    @Nullable
    public String getRequestId() {
        return requestId;
    }
}
