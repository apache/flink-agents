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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MediaJob}, {@link MediaJobException}, and {@link MediaJobTimeoutException}.
 */
class MediaJobTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serializesAndRestoresTheJob() throws Exception {
        MediaJob job = new MediaJob("job-1", 123, 456L);
        String expected = "{\"job_id\":\"job-1\",\"submitted_at_ms\":123,\"expires_at_ms\":456}";

        String json = MAPPER.writeValueAsString(job);
        assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree(expected));
        MediaJob restored = MAPPER.readValue(json, MediaJob.class);

        assertThat(restored).isEqualTo(job).hasSameHashCodeAs(job);
        assertThat(restored.getJobId()).isEqualTo("job-1");
        assertThat(restored.getSubmittedAtMs()).isEqualTo(123);
        assertThat(restored.getExpiresAtMs()).isEqualTo(456);

        MediaJobState<MediaResult<?>> state =
                new MediaJobState<>(job, MediaJobStatus.PENDING, null, null, null);
        MediaJobState<MediaResult<?>> sameState =
                new MediaJobState<>(restored, MediaJobStatus.PENDING, null, null, null);
        assertThat(sameState).isEqualTo(state).hasSameHashCodeAs(state);

        assertThatThrownBy(() -> new MediaJob(null, 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("jobId");
        assertThatThrownBy(() -> new MediaJob("", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'jobId' must be a non-blank string.");
        assertThatThrownBy(() -> new MediaJob("   ", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'jobId' must be a non-blank string.");
    }

    @Test
    void timeoutExceptionStoresPositiveDurationAndRejectsInvalidValues() {
        MediaJob job = new MediaJob("job-1", 123, null);

        MediaJobException statusOnly =
                new MediaJobException(job, MediaJobStatus.PENDING, null, null);
        assertThat(statusOnly.getJob()).isSameAs(job);
        assertThat(statusOnly.getStatus()).isEqualTo(MediaJobStatus.PENDING);
        assertThat(statusOnly.getCode()).isNull();
        assertThat(statusOnly.getMessage()).isEqualTo("Media job job-1: pending");

        MediaJobException withCodeOnly =
                new MediaJobException(job, MediaJobStatus.FAILED, "BadRequest", null);
        assertThat(withCodeOnly.getMessage()).isEqualTo("Media job job-1: BadRequest (failed)");

        assertThatThrownBy(() -> new MediaJobException(null, MediaJobStatus.FAILED, null, "err"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("job");
        assertThatThrownBy(() -> new MediaJobException(job, null, null, "err"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("status");

        MediaJobTimeoutException positive =
                new MediaJobTimeoutException(
                        job, MediaJobStatus.RUNNING, Duration.ofMillis(12500), "timed out");
        assertThat(positive.getTimeout()).isEqualTo(Duration.ofMillis(12500));
        assertThat(positive.getMessage()).isEqualTo("timed out");

        MediaJobTimeoutException defaultMessage =
                new MediaJobTimeoutException(
                        job, MediaJobStatus.RUNNING, Duration.ofSeconds(5), null);
        assertThat(defaultMessage.getMessage())
                .isEqualTo("Media job job-1 timed out after PT5S (last status: running)");

        assertThatThrownBy(
                        () ->
                                new MediaJobTimeoutException(
                                        job, MediaJobStatus.RUNNING, null, "timed out"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("timeout");

        assertThatThrownBy(
                        () ->
                                new MediaJobTimeoutException(
                                        job, MediaJobStatus.PENDING, Duration.ZERO, "timed out"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout must be positive");

        assertThatThrownBy(
                        () ->
                                new MediaJobTimeoutException(
                                        job,
                                        MediaJobStatus.RUNNING,
                                        Duration.ofMillis(-1),
                                        "timed out"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout must be positive");
    }
}
