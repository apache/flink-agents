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

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat-model/router name-clash has two per-call checks (agent and environment {@code
 * addResource}), but each of those sees only its own registry. When the same name arrives from
 * <em>different</em> registries — environment resources merged via {@code addResourcesIfAbsent},
 * agent resources added directly — only {@link AgentPlan}'s backstop check catches the clash.
 */
class AgentPlanRoutingBackstopTest {

    private static ResourceDescriptor descriptor(String clazz) {
        // Router descriptors must declare a strategy (validated at plan construction), so the
        // hand-built minimal descriptor carries an empty rules strategy.
        return new ResourceDescriptor(
                clazz, Map.of("strategy_type", "rule_based", "strategy_args", Map.of()));
    }

    @Test
    void planBackstopRejectsClashArrivingFromDifferentRegistries() {
        Agent agent = new Agent();
        agent.addResource("shared", ResourceType.MODEL_ROUTER, descriptor("some.Router"));
        // Environment-level resources merge in via putIfAbsent, bypassing the per-call
        // checks: neither addResource call ever saw both registrations.
        agent.addResourcesIfAbsent(
                Map.of(
                        ResourceType.CHAT_MODEL,
                        new HashMap<>(Map.of("shared", descriptor("some.ChatModel")))));

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new AgentPlan(agent));
        assertTrue(e.getMessage().contains("shared"), e.getMessage());
    }

    @Test
    void planBackstopAllowsDistinctNamesAcrossRegistries() throws Exception {
        Agent agent = new Agent();
        agent.addResource("router", ResourceType.MODEL_ROUTER, descriptor("some.Router"));
        agent.addResourcesIfAbsent(
                Map.of(
                        ResourceType.CHAT_MODEL,
                        new HashMap<>(Map.of("small", descriptor("some.ChatModel")))));
        new AgentPlan(agent);
    }
}
