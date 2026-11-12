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

package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.media.MediaJob;
import org.apache.flink.agents.api.media.MediaJobException;
import org.apache.flink.agents.api.media.MediaJobStatus;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.media.MediaTask;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Java calls a Python media model through the tagged JSON bridge. */
class PythonMediaModelTest {
    @Test
    void generateReadsThePythonResult() {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        when(adapter.invoke(
                        eq("python_java_utils.call_media_generate"), any(), any(), any(), any()))
                .thenReturn(
                        Map.of(
                                "status",
                                "SUCCESS",
                                "result",
                                "{\"content\":[{\"type\":\"text\",\"text\":\"A notebook.\"}],"
                                        + "\"usage\":{\"images\":1},\"metadata\":{\"seed\":null}}"));
        PythonMediaModel model =
                new PythonMediaModel(
                        adapter, null, new ResourceDescriptor("unused", Map.of()), null);

        MediaResult result =
                model.generate(java.util.List.of(TextBlock.of("A notebook.")), 1.5, Map.of());

        assertThat(result.getContent()).containsExactly(TextBlock.of("A notebook."));
        assertThat(result.getUsage()).containsEntry("images", 1);
        assertThat(result.getMetadata()).containsEntry("seed", null);
    }

    @ParameterizedTest
    @CsvSource({
        "generate,INTERRUPTED",
        "submit,INTERRUPTED",
        "fetch,INTERRUPTED",
        "wait,INTERRUPTED",
        "cancel,INTERRUPTED",
        "generate,CANCELLED",
        "submit,CANCELLED",
        "fetch,CANCELLED",
        "wait,CANCELLED",
        "cancel,CANCELLED"
    })
    void preservesCancellation(String operation, String status) {
        PythonResourceAdapter adapter =
                mock(
                        PythonResourceAdapter.class,
                        invocation -> Map.of("status", status, "message", "operation stopped"));
        PythonMediaModel model =
                new PythonMediaModel(
                        adapter, null, new ResourceDescriptor("unused", Map.of()), null);
        MediaJob job =
                new MediaJob("dashscope", "model", MediaTask.VIDEO_GENERATION, "job-1", 1, null);
        Throwable failure =
                catchThrowable(
                        () -> {
                            switch (operation) {
                                case "generate":
                                    model.generate(List.of(), null, Map.of());
                                    break;
                                case "submit":
                                    model.submit(List.of(), Map.of());
                                    break;
                                case "fetch":
                                    model.fetch(job);
                                    break;
                                case "wait":
                                    model.wait(job, null);
                                    break;
                                case "cancel":
                                    model.cancel(job);
                                    break;
                                default:
                                    throw new AssertionError(operation);
                            }
                        });
        assertThat(failure)
                .isInstanceOf(CancellationException.class)
                .hasMessage("operation stopped");
        assertThat(CancellationUtils.isCancellation(failure)).isTrue();
        assertThat(CancellationUtils.isInterruption(failure))
                .isEqualTo("INTERRUPTED".equals(status));
    }

    @Test
    void jobFailureRemainsABusinessFailure() {
        PythonResourceAdapter adapter =
                mock(
                        PythonResourceAdapter.class,
                        invocation ->
                                Map.of(
                                        "status",
                                        "JOB_ERROR",
                                        "job_status",
                                        "failed",
                                        "code",
                                        "BadRequest",
                                        "message",
                                        "invalid prompt",
                                        "job",
                                        "{\"provider\":\"dashscope\",\"model\":\"model\",\"task\":\"video_generation\",\"job_id\":\"job-1\",\"submitted_at_ms\":1}"));
        PythonMediaModel model =
                new PythonMediaModel(
                        adapter, null, new ResourceDescriptor("unused", Map.of()), null);
        Throwable failure = catchThrowable(() -> model.generate(List.of(), null, Map.of()));
        assertThat(failure).isInstanceOf(MediaJobException.class);
        assertThat(((MediaJobException) failure).getStatus()).isEqualTo(MediaJobStatus.FAILED);
        assertThat(((MediaJobException) failure).getCode()).isEqualTo("BadRequest");
        assertThat(CancellationUtils.isCancellation(failure)).isFalse();
    }
}
