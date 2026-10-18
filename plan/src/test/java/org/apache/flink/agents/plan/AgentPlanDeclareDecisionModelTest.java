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

package org.apache.flink.agents.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.DecisionModelConnection;
import org.apache.flink.agents.api.annotation.DecisionModelSetup;
import org.apache.flink.agents.api.decision.BaseDecisionModelConnection;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionRequest.Question;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.decision.DecisionResult.PredicateAnswer;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Decision resource declarations remain usable after plan serialization. */
public class AgentPlanDeclareDecisionModelTest {
    public static class DecisionAgent extends Agent {
        @DecisionModelConnection
        public static ResourceDescriptor connection() {
            return ResourceDescriptor.Builder.newBuilder(TestConnection.class.getName()).build();
        }

        @DecisionModelSetup
        public static ResourceDescriptor model() {
            return ResourceDescriptor.Builder.newBuilder(BaseDecisionModelSetup.class.getName())
                    .addInitialArgument("connection", "connection")
                    .addInitialArgument("model", "decision-model")
                    .build();
        }
    }

    public static class TestConnection extends BaseDecisionModelConnection {
        public TestConnection(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public DecisionResult decide(
                DecisionRequest request, String model, Map<String, Object> requestParams) {
            return new DecisionResult(
                    Map.of("eligible", new PredicateAnswer(0.8)),
                    model,
                    null,
                    Map.of("input", request.getInput(), "request_params", requestParams));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void declaredResourcesRemainCallableAfterPlanRoundTrip(boolean annotations) throws Exception {
        Agent agent;
        if (annotations) {
            agent = new DecisionAgent();
        } else {
            agent = new Agent();
            agent.addResource(
                    "connection",
                    ResourceType.DECISION_MODEL_CONNECTION,
                    DecisionAgent.connection());
            agent.addResource("model", ResourceType.DECISION_MODEL, DecisionAgent.model());
        }
        ObjectMapper mapper = new ObjectMapper();
        AgentPlan plan =
                mapper.readValue(mapper.writeValueAsString(new AgentPlan(agent)), AgentPlan.class);
        Map<ResourceType, Map<String, ResourceProvider>> providers = plan.getResourceProviders();
        assertThat(providers.get(ResourceType.DECISION_MODEL_CONNECTION))
                .containsOnlyKeys("connection");
        assertThat(providers.get(ResourceType.DECISION_MODEL)).containsOnlyKeys("model");
        assertThat(providers.get(ResourceType.DECISION_MODEL_CONNECTION).get("connection"))
                .isInstanceOf(JavaResourceProvider.class);
        assertThat(providers.get(ResourceType.DECISION_MODEL).get("model"))
                .isInstanceOf(JavaResourceProvider.class);
        BaseDecisionModelConnection connection =
                (BaseDecisionModelConnection)
                        providers
                                .get(ResourceType.DECISION_MODEL_CONNECTION)
                                .get("connection")
                                .provide(null);
        ResourceContext context =
                ResourceContext.fromGetResource(
                        (name, type) -> {
                            assertThat(name).isEqualTo("connection");
                            assertThat(type).isEqualTo(ResourceType.DECISION_MODEL_CONNECTION);
                            return connection;
                        });
        BaseDecisionModelSetup model =
                (BaseDecisionModelSetup)
                        providers.get(ResourceType.DECISION_MODEL).get("model").provide(context);
        model.open();
        try {
            DecisionRequest request =
                    new DecisionRequest(
                            "Receipt attached",
                            Map.of("eligible", Question.predicate("Is a receipt available?")));
            Map<String, Object> params = Map.of("timeout", 2.5);

            DecisionResult result = model.decide(request, params);

            assertThat(result.getModel()).isEqualTo("decision-model");
            assertThat(((PredicateAnswer) result.getAnswers().get("eligible")).getProbabilityTrue())
                    .isEqualTo(0.8);
            assertThat(result.getMetadata())
                    .containsEntry("input", request.getInput())
                    .containsEntry("request_params", params);
        } finally {
            model.close();
            connection.close();
        }
    }
}
