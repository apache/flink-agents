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

package org.apache.flink.agents.api.media;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.utils.StringValidationUtils;

import javax.annotation.Nullable;

import java.util.Objects;

/**
 * Handle of a submitted asynchronous media task.
 *
 * <p>Callers are responsible for passing a job handle back to a setup matching the connection and
 * capability that created it.
 */
public final class MediaJob {
    private final String jobId;
    private final long submittedAtMs;
    @Nullable private final Long expiresAtMs;

    @JsonCreator
    public MediaJob(
            @JsonProperty(value = "job_id", required = true) String jobId,
            @JsonProperty(value = "submitted_at_ms", required = true) long submittedAtMs,
            @JsonProperty("expires_at_ms") @Nullable Long expiresAtMs) {
        Objects.requireNonNull(jobId, "jobId");
        this.jobId = StringValidationUtils.requireNonBlank(jobId, "jobId");
        this.submittedAtMs = submittedAtMs;
        this.expiresAtMs = expiresAtMs;
    }

    @JsonProperty("job_id")
    public String getJobId() {
        return jobId;
    }

    @JsonProperty("submitted_at_ms")
    public long getSubmittedAtMs() {
        return submittedAtMs;
    }

    @JsonProperty("expires_at_ms")
    @Nullable
    public Long getExpiresAtMs() {
        return expiresAtMs;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MediaJob)) {
            return false;
        }
        MediaJob other = (MediaJob) obj;
        return submittedAtMs == other.submittedAtMs
                && Objects.equals(jobId, other.jobId)
                && Objects.equals(expiresAtMs, other.expiresAtMs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, submittedAtMs, expiresAtMs);
    }

    @Override
    public String toString() {
        return "MediaJob{"
                + "jobId='"
                + jobId
                + "', submittedAtMs="
                + submittedAtMs
                + ", expiresAtMs="
                + expiresAtMs
                + '}';
    }
}
