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

package org.apache.flink.agents.api.decision;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaseDecisionModelSetupTest {
    private final DecisionRequest firstRequest =
            new DecisionRequest(
                    "The receipt is missing.",
                    Map.of("eligible", DecisionRequest.Question.predicate("Has a receipt?")));
    private final DecisionResult firstResult =
            new DecisionResult(
                    Map.of("eligible", new DecisionResult.PredicateAnswer(0.1)),
                    null,
                    null,
                    Map.of());

    @Test
    void namedSetupResolvesConnectionAndDelegatesEachRequestWithoutRetainingParameters()
            throws Exception {
        BaseDecisionModelConnection connection = mock(BaseDecisionModelConnection.class);
        ResourceContext context = mock(ResourceContext.class);
        when(context.getResource("shared", ResourceType.DECISION_MODEL_CONNECTION))
                .thenReturn(connection);
        BaseDecisionModelSetup setup = setup("shared", "decision", context);
        DecisionRequest nextRequest =
                new DecisionRequest(
                        Map.of("ticket", "New evidence"),
                        Map.of("urgent", DecisionRequest.Question.predicate("Is it urgent?")));
        DecisionResult nextResult =
                new DecisionResult(
                        Map.of("urgent", new DecisionResult.RefusalAnswer("Insufficient evidence")),
                        null,
                        null,
                        Map.of());
        Map<String, Object> parameters =
                Map.of("timeout", 0.5, "extra_headers", Map.of("x-request-id", "first"));
        when(connection.decide(firstRequest, "decision", parameters)).thenReturn(firstResult);
        when(connection.decide(nextRequest, "decision", Map.of())).thenReturn(nextResult);

        assertThatThrownBy(() -> setup.decide(firstRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Open the setup");
        assertThatThrownBy(setup::supportsAsyncExecution)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Open the setup");
        setup.open();

        assertThat(setup.getResourceType()).isEqualTo(ResourceType.DECISION_MODEL);
        assertThat(setup.decide(firstRequest, parameters)).isSameAs(firstResult);
        assertThat(setup.decide(nextRequest)).isSameAs(nextResult);
        verify(context).getResource("shared", ResourceType.DECISION_MODEL_CONNECTION);
        verify(connection).decide(firstRequest, "decision", parameters);
        verify(connection).decide(nextRequest, "decision", Map.of());
        verify(connection, never()).open();
    }

    @Test
    void setupsShareNamedConnectionWithoutOwningItsLifecycle() throws Exception {
        BaseDecisionModelConnection connection = mock(BaseDecisionModelConnection.class);
        ResourceContext context = mock(ResourceContext.class);
        when(context.getResource("shared", ResourceType.DECISION_MODEL_CONNECTION))
                .thenReturn(connection);
        BaseDecisionModelSetup first = setup("shared", "first-model", context);
        BaseDecisionModelSetup second = setup("shared", "second-model", context);
        when(connection.decide(firstRequest, "first-model", Map.of())).thenReturn(firstResult);
        when(connection.decide(firstRequest, "second-model", Map.of())).thenReturn(firstResult);

        first.open();
        second.open();
        assertThat(first.decide(firstRequest)).isSameAs(firstResult);
        first.close();
        assertThat(second.decide(firstRequest)).isSameAs(firstResult);
        second.close();

        verify(connection, never()).open();
        verify(connection, never()).close();
    }

    @Test
    void namedConnectionRequiresAResourceContext() {
        assertThatThrownBy(() -> setup("shared", "decision", null).open())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a ResourceContext");
    }

    @Test
    void resolvedResourceMustBeADecisionConnection() throws Exception {
        ResourceContext context = mock(ResourceContext.class);
        when(context.getResource("shared", ResourceType.DECISION_MODEL_CONNECTION))
                .thenReturn(mock(Resource.class));

        assertThatThrownBy(() -> setup("shared", "decision", context).open())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision model connection");
    }

    @ParameterizedTest
    @MethodSource("invalidConfiguration")
    void openRequiresConnectionAndModel(Map<String, Object> arguments) {
        BaseDecisionModelSetup setup =
                new BaseDecisionModelSetup(
                        new ResourceDescriptor(BaseDecisionModelSetup.class.getName(), arguments),
                        null);

        assertThatThrownBy(setup::open)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a non-blank string");
        assertThatThrownBy(() -> setup.decide(firstRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Open the setup");
    }

    private static Stream<Map<String, Object>> invalidConfiguration() {
        return Stream.of(
                Map.of("model", "decision"),
                Map.of("connection", "shared"),
                Map.of("connection", " ", "model", "decision"),
                Map.of("connection", 42, "model", "decision"),
                Map.of("connection", "shared", "model", " "),
                Map.of("connection", "shared", "model", 42));
    }

    private static BaseDecisionModelSetup setup(
            String connection, String model, ResourceContext context) {
        return new BaseDecisionModelSetup(
                new ResourceDescriptor(
                        BaseDecisionModelSetup.class.getName(),
                        Map.of("connection", connection, "model", model)),
                context);
    }
}
