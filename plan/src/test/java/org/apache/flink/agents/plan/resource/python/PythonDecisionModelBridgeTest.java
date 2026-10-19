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

import com.fasterxml.jackson.databind.JsonMappingException;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import pemja.core.object.PyObject;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PythonDecisionModelBridgeTest {
    private final PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
    private final PyObject resource = mock(PyObject.class);
    private final PythonDecisionModelBridge bridge =
            new PythonDecisionModelBridge(adapter, resource);
    private final DecisionRequest request =
            new DecisionRequest(
                    "input", Map.of("eligible", DecisionRequest.Question.predicate("Eligible?")));

    @Test
    void decodesSuccessfulResult() {
        returnFromPython(
                Map.of(
                        "status",
                        "SUCCESS",
                        "result",
                        "{\"answers\":{\"eligible\":{\"type\":\"predicate\",\"probability_true\":0.9}}}"));

        assertThat(bridge.decide(request, null, Map.of()).getAnswers())
                .containsOnlyKeys("eligible");
    }

    @Test
    void setupPassesParametersForOnlyTheCurrentCall() {
        returnFromPython(
                Map.of(
                        "status",
                        "SUCCESS",
                        "result",
                        "{\"answers\":{\"eligible\":{\"type\":\"predicate\",\"probability_true\":0.9}}}"));
        PythonDecisionModelSetup setup =
                new PythonDecisionModelSetup(
                        adapter,
                        resource,
                        new ResourceDescriptor("unused", Map.of()),
                        mock(ResourceContext.class));
        Map<String, Object> params =
                Map.of("extra_headers", Map.of("x-request-id", "setup-call"), "timeout", 2.5);

        setup.open();
        setup.decide(request, params);
        setup.decide(request);

        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_decision_model"),
                        same(resource),
                        anyString(),
                        isNull(),
                        eq(params));
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_decision_model"),
                        same(resource),
                        anyString(),
                        isNull(),
                        eq(Map.of()));
    }

    @Test
    void connectionPassesModelAndParametersForOnlyTheCurrentCall() {
        returnFromPython(
                Map.of(
                        "status",
                        "SUCCESS",
                        "result",
                        "{\"answers\":{\"eligible\":{\"type\":\"predicate\",\"probability_true\":0.9}}}"));
        PythonDecisionModelConnection connection =
                new PythonDecisionModelConnection(
                        adapter,
                        resource,
                        new ResourceDescriptor("unused", Map.of()),
                        mock(ResourceContext.class));
        Map<String, Object> params =
                Map.of("extra_headers", Map.of("x-request-id", "connection-call"));

        connection.decide(request, "first", params);
        connection.decide(request, "second");

        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_decision_model"),
                        same(resource),
                        anyString(),
                        eq("first"),
                        eq(params));
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_decision_model"),
                        same(resource),
                        anyString(),
                        eq("second"),
                        eq(Map.of()));
    }

    @Test
    void rejectsMalformedResultWithinSuccessfulOutcome() {
        returnFromPython(
                Map.of(
                        "status",
                        "SUCCESS",
                        "result",
                        "{\"answers\":{\"eligible\":{\"type\":\"refusal\"}},\"usage\":{\"input_tokens\":\"3\"}}"));

        assertThatThrownBy(() -> bridge.decide(request, null, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid DecisionModel result from Python")
                .hasCauseInstanceOf(JsonMappingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "INTERRUPTED"})
    void restoresTypedCancellationSignal(String status) {
        returnFromPython(Map.of("status", status, "message", "provider stopped"));

        assertThatThrownBy(() -> bridge.decide(request, null, Map.of()))
                .isInstanceOf(CancellationException.class)
                .hasMessage("provider stopped")
                .satisfies(
                        error ->
                                assertThat(CancellationUtils.isInterruption(error))
                                        .isEqualTo(status.equals("INTERRUPTED")));
    }

    @ParameterizedTest
    @MethodSource("invalidOutcomes")
    void rejectsMalformedOutcomeInsteadOfGuessingItsMeaning(Object outcome) {
        returnFromPython(outcome);

        assertThatThrownBy(() -> bridge.decide(request, null, Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void rejectsNonJsonParametersBeforeEnteringPython(Map<String, Object> requestParams) {
        assertThatThrownBy(() -> bridge.decide(request, null, requestParams))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request_params");
        verifyNoInteractions(adapter);
    }

    private static Stream<Map<String, Object>> invalidParameters() {
        return Stream.of(
                Map.of("timeout", Double.NaN),
                Map.of("extra_body", Map.of(42, "not a string key")),
                Map.of("extra_body", Map.of("unsupported", new Object())));
    }

    private static Stream<Object> invalidOutcomes() {
        return Stream.of(
                "a result without an envelope",
                Map.of(),
                Map.of("status", "UNKNOWN", "message", "provider stopped"),
                Map.of("status", "SUCCESS", "message", "wrong payload"),
                Map.of("status", "CANCELLED", "result", "wrong payload"),
                Map.of("status", "INTERRUPTED", "message", 42),
                Map.of("status", "SUCCESS", "result", "{}", "message", "ambiguous"));
    }

    private void returnFromPython(Object outcome) {
        when(adapter.invoke(anyString(), any(), any(), any(), any())).thenReturn(outcome);
    }
}
