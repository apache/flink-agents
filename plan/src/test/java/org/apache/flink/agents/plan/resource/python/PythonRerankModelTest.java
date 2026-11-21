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
import org.apache.flink.agents.api.rerank.RerankResult;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PythonRerankModelTest {
    @ParameterizedTest
    @ValueSource(strings = {"INTERRUPTED", "CANCELLED"})
    void preservesCancellation(String status) {
        PythonResourceAdapter adapter =
                mock(
                        PythonResourceAdapter.class,
                        invocation -> Map.of("status", status, "message", "operation stopped"));
        PythonRerankModel model =
                new PythonRerankModel(
                        adapter, null, new ResourceDescriptor("unused", Map.of()), null);
        Throwable failure = catchThrowable(() -> model.rerank(TextBlock.of("query"), List.of()));
        assertThat(failure)
                .isInstanceOf(CancellationException.class)
                .hasMessage("operation stopped");
        assertThat(CancellationUtils.isCancellation(failure)).isTrue();
        assertThat(CancellationUtils.isInterruption(failure))
                .isEqualTo("INTERRUPTED".equals(status));
    }

    @Test
    void ordinaryFailurePropagates() {
        IllegalArgumentException error = new IllegalArgumentException("invalid query");
        PythonResourceAdapter adapter =
                mock(
                        PythonResourceAdapter.class,
                        invocation -> {
                            throw error;
                        });
        PythonRerankModel model =
                new PythonRerankModel(
                        adapter, null, new ResourceDescriptor("unused", Map.of()), null);
        assertThat(catchThrowable(() -> model.rerank(TextBlock.of("query"), List.of())))
                .isSameAs(error);
    }

    @Test
    void closedModelRejectsUseBeforeCallingPython() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PythonRerankModel model =
                new PythonRerankModel(
                        adapter, null, new ResourceDescriptor("unused", Map.of()), null);

        model.open();
        model.close();
        assertThatThrownBy(model::open)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rerank model is closed");
        assertThatThrownBy(
                        () ->
                                model.rerank(
                                        TextBlock.of("query"), List.of(TextBlock.of("document"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rerank model is closed");
        verify(adapter).callMethod(null, "open", Map.of());
        verify(adapter).callMethod(null, "close", Map.of());
        verifyNoMoreInteractions(adapter);
    }

    @Test
    void allowsNullParametersAndDecodesResultWithTimeout() {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        when(adapter.invoke(
                        eq("python_java_utils.call_rerank_model"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(
                        Map.of(
                                "status",
                                "SUCCESS",
                                "result",
                                "{\"results\":[{\"index\":0,\"relevance_score\":0.75}],"
                                        + "\"usage\":{\"total_tokens\":4},\"request_id\":\"req-1\"}"));
        PythonRerankModel model =
                new PythonRerankModel(
                        adapter, null, new ResourceDescriptor("unused", Map.of()), null);

        RerankResult result =
                model.rerank(
                        TextBlock.of("query"),
                        List.of(TextBlock.of("doc")),
                        null,
                        Duration.ofMillis(2500));

        assertThat(result.getResults()).hasSize(1);
        assertThat(result.getResults().get(0).getIndex()).isEqualTo(0);
        assertThat(result.getResults().get(0).getRelevanceScore()).isEqualTo(0.75);
        assertThat(result.getRequestId()).isEqualTo("req-1");
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_rerank_model"),
                        any(),
                        any(),
                        any(),
                        eq("{}"),
                        eq(2.5));
    }

    @Test
    void rejectsNullInputsAndInvalidTimeoutBeforeCallingPython() {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PythonRerankModel model =
                new PythonRerankModel(
                        adapter, null, new ResourceDescriptor("unused", Map.of()), null);

        assertThatThrownBy(() -> model.rerank(null, List.of(TextBlock.of("doc"))))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("query");
        assertThatThrownBy(() -> model.rerank(TextBlock.of("query"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("documents");
        assertThatThrownBy(
                        () ->
                                model.rerank(
                                        TextBlock.of("query"),
                                        List.of(TextBlock.of("doc")),
                                        Map.of(),
                                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout must be positive");
        assertThatThrownBy(
                        () ->
                                model.rerank(
                                        TextBlock.of("query"),
                                        List.of(TextBlock.of("doc")),
                                        Map.of(),
                                        Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout must be positive");
        verifyNoMoreInteractions(adapter);
    }
}
