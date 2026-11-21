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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Media job handles preserve task identifiers when stored and restored. */
class MediaJobTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(
            strings = {"image_generation", "video_generation", "transcription", "speech_synthesis"})
    void serializesAndRestoresTheJob(String task) throws Exception {
        MediaJob job = new MediaJob("dashscope", "model", task, "job-1", 123, 456L);
        String expected =
                "{\"provider\":\"dashscope\",\"model\":\"model\",\"task\":\""
                        + task
                        + "\",\"job_id\":\"job-1\",\"submitted_at_ms\":123,\"expires_at_ms\":456}";

        String json = MAPPER.writeValueAsString(job);
        assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree(expected));
        MediaJob restored = MAPPER.readValue(json, MediaJob.class);

        assertThat(restored.getProvider()).isEqualTo("dashscope");
        assertThat(restored.getModel()).isEqualTo("model");
        assertThat(restored.getTask()).isEqualTo(task);
        assertThat(restored.getJobId()).isEqualTo("job-1");
        assertThat(restored.getSubmittedAtMs()).isEqualTo(123);
        assertThat(restored.getExpiresAtMs()).isEqualTo(456);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", ""})
    void rejectsUnknownTasks(String task) {
        assertThatThrownBy(() -> new MediaJob("dashscope", "model", task, "job-1", 123, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown media task: " + task);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", ""})
    void rejectsUnknownTasksWhenRestoring(String task) {
        String json =
                "{\"provider\":\"dashscope\",\"model\":\"model\",\"task\":\""
                        + task
                        + "\",\"job_id\":\"job-1\",\"submitted_at_ms\":123}";

        assertThatThrownBy(() -> MAPPER.readValue(json, MediaJob.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("Unknown media task: " + task);
    }

    @Test
    void timeoutExceptionStoresNonNegativeDurationAndRejectsInvalidValues() {
        MediaJob job = new MediaJob("dashscope", "model", "image_generation", "job-1", 123, null);

        MediaJobTimeoutException zero =
                new MediaJobTimeoutException(job, MediaJobStatus.PENDING, Duration.ZERO, null);
        assertThat(zero.getTimeout()).isEqualTo(Duration.ZERO);
        assertThat(zero.getJob()).isSameAs(job);
        assertThat(zero.getStatus()).isEqualTo(MediaJobStatus.PENDING);
        assertThat(zero.getCode()).isNull();

        MediaJobTimeoutException positive =
                new MediaJobTimeoutException(
                        job, MediaJobStatus.RUNNING, Duration.ofMillis(12500), "timed out");
        assertThat(positive.getTimeout()).isEqualTo(Duration.ofMillis(12500));
        assertThat(positive.getMessage()).isEqualTo("timed out");

        assertThatThrownBy(
                        () ->
                                new MediaJobTimeoutException(
                                        job, MediaJobStatus.RUNNING, null, "timed out"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("timeout");

        assertThatThrownBy(
                        () ->
                                new MediaJobTimeoutException(
                                        job,
                                        MediaJobStatus.RUNNING,
                                        Duration.ofMillis(-1),
                                        "timed out"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout must be non-negative");
    }
}
