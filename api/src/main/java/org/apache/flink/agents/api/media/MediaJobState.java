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

/** One observation of a media job, returned by fetch. */
public final class MediaJobState {
    private final MediaJob job;
    private final MediaJobStatus status;
    @Nullable private final MediaResult result;
    @Nullable private final String errorCode;
    @Nullable private final String errorMessage;

    @JsonCreator
    public MediaJobState(
            @JsonProperty(value = "job", required = true) MediaJob job,
            @JsonProperty(value = "status", required = true) MediaJobStatus status,
            @JsonProperty("result") @Nullable MediaResult result,
            @JsonProperty("error_code") @Nullable String errorCode,
            @JsonProperty("error_message") @Nullable String errorMessage) {
        this.job = Objects.requireNonNull(job, "job");
        this.status = Objects.requireNonNull(status, "status");
        this.result = result;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public MediaJob getJob() {
        return job;
    }

    public MediaJobStatus getStatus() {
        return status;
    }

    @Nullable
    public MediaResult getResult() {
        return result;
    }

    @JsonProperty("error_code")
    @Nullable
    public String getErrorCode() {
        return errorCode;
    }

    @JsonProperty("error_message")
    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }
}
