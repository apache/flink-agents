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
import org.apache.flink.agents.api.chat.messages.DataContentBlock;

import javax.annotation.Nullable;

import java.util.Objects;

/** One document after ranking. */
public final class RerankHit {
    private final int index;
    private final double relevanceScore;
    @Nullable private final DataContentBlock document;

    @JsonCreator
    public RerankHit(
            @JsonProperty(value = "index", required = true) int index,
            @JsonProperty(value = "relevance_score", required = true) double relevanceScore,
            @JsonProperty("document") @Nullable DataContentBlock document) {
        this.index = index;
        this.relevanceScore = relevanceScore;
        this.document = document;
    }

    public int getIndex() {
        return index;
    }

    @JsonProperty("relevance_score")
    public double getRelevanceScore() {
        return relevanceScore;
    }

    @Nullable
    public DataContentBlock getDocument() {
        return document;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof RerankHit)) {
            return false;
        }
        RerankHit other = (RerankHit) obj;
        return index == other.index
                && Double.compare(other.relevanceScore, relevanceScore) == 0
                && Objects.equals(document, other.document);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, relevanceScore, document);
    }

    @Override
    public String toString() {
        return "RerankHit{"
                + "index="
                + index
                + ", relevanceScore="
                + relevanceScore
                + ", document="
                + document
                + '}';
    }
}
