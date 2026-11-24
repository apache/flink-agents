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

import javax.annotation.Nullable;

import java.util.Objects;

/** An operation on a media job failed. */
public class MediaJobException extends RuntimeException {
    private final MediaJob job;
    private final MediaJobStatus status;
    @Nullable private final String code;

    public MediaJobException(
            MediaJob job, MediaJobStatus status, @Nullable String code, @Nullable String message) {
        super(formatMessage(job, status, code, message));
        this.job = Objects.requireNonNull(job, "job");
        this.status = Objects.requireNonNull(status, "status");
        this.code = code;
    }

    private static String formatMessage(
            MediaJob job, MediaJobStatus status, @Nullable String code, @Nullable String message) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(status, "status");
        if (message != null) {
            return message;
        }
        String detail = code != null ? code + " (" + status.getValue() + ")" : status.getValue();
        return "Media job " + job.getJobId() + ": " + detail;
    }

    public MediaJob getJob() {
        return job;
    }

    public MediaJobStatus getStatus() {
        return status;
    }

    @Nullable
    public String getCode() {
        return code;
    }
}
