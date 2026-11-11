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

import javax.annotation.Nullable;

import java.util.Objects;

/** Handle of a submitted asynchronous media task. */
public final class MediaJob {
    private final String provider;
    private final String model;
    private final MediaTask task;
    private final String jobId;
    private final long submittedAtMs;
    @Nullable private final Long expiresAtMs;

    @JsonCreator
    public MediaJob(
            @JsonProperty(value = "provider", required = true) String provider,
            @JsonProperty(value = "model", required = true) String model,
            @JsonProperty(value = "task", required = true) MediaTask task,
            @JsonProperty(value = "job_id", required = true) String jobId,
            @JsonProperty(value = "submitted_at_ms", required = true) long submittedAtMs,
            @JsonProperty("expires_at_ms") @Nullable Long expiresAtMs) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = Objects.requireNonNull(model, "model");
        this.task = Objects.requireNonNull(task, "task");
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.submittedAtMs = submittedAtMs;
        this.expiresAtMs = expiresAtMs;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public MediaTask getTask() {
        return task;
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
}
