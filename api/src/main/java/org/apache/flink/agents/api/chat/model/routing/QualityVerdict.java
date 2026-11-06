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
package org.apache.flink.agents.api.chat.model.routing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Objects;

/**
 * The outcome of a {@link QualitySignal} on one model round: accept the answer, or escalate the
 * request to the next tier with a reason and an optional score. Verdicts are plain data so the
 * engine can persist them as durable records and reuse them on replay.
 */
public final class QualityVerdict implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final QualityVerdict ACCEPT = new QualityVerdict(false, null, null);

    private final boolean escalate;
    @Nullable private final String reason;
    @Nullable private final Double score;

    @JsonCreator
    public QualityVerdict(
            @JsonProperty("escalate") boolean escalate,
            @JsonProperty("reason") @Nullable String reason,
            @JsonProperty("score") @Nullable Double score) {
        this.escalate = escalate;
        this.reason = reason;
        this.score = score;
    }

    /** The answer is good enough; the loop continues on the current tier. */
    public static QualityVerdict accept() {
        return ACCEPT;
    }

    /** The answer is rejected; the request moves to the next tier. */
    public static QualityVerdict escalate(String reason) {
        return new QualityVerdict(true, Objects.requireNonNull(reason, "reason"), null);
    }

    /** Rejected with the signal's own score, for example a probability that fell below a bound. */
    public static QualityVerdict escalate(String reason, double score) {
        return new QualityVerdict(true, Objects.requireNonNull(reason, "reason"), score);
    }

    @JsonProperty("escalate")
    public boolean isEscalate() {
        return escalate;
    }

    @JsonProperty("reason")
    @Nullable
    public String getReason() {
        return reason;
    }

    @JsonProperty("score")
    @Nullable
    public Double getScore() {
        return score;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QualityVerdict)) {
            return false;
        }
        QualityVerdict other = (QualityVerdict) o;
        return escalate == other.escalate
                && Objects.equals(reason, other.reason)
                && Objects.equals(score, other.score);
    }

    @Override
    public int hashCode() {
        return Objects.hash(escalate, reason, score);
    }

    @Override
    public String toString() {
        return escalate
                ? "escalate(" + reason + (score == null ? "" : ", " + score) + ")"
                : "accept";
    }
}
