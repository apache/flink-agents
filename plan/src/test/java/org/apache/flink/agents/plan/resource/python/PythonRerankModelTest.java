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
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

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
        Throwable failure =
                catchThrowable(
                        () ->
                                model.rerank(
                                        TextBlock.of("query"),
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        Map.of()));
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
        assertThat(
                        catchThrowable(
                                () ->
                                        model.rerank(
                                                TextBlock.of("query"),
                                                List.of(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                Map.of())))
                .isSameAs(error);
    }
}
