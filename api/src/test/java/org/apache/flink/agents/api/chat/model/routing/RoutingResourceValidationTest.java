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

package org.apache.flink.agents.api.chat.model.routing;

import org.apache.flink.agents.api.AgentBuilder;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Chat models and model routers share the chat request namespace, so registering one name as both
 * must fail at the registration call site (better failure locality than the {@code AgentPlan}
 * backstop check).
 */
class RoutingResourceValidationTest {

    private static ResourceDescriptor descriptor() {
        return new ResourceDescriptor("some.Clazz", Map.of());
    }

    @Test
    void agentRejectsRouterNameAlreadyUsedByChatModel() {
        Agent agent = new Agent();
        agent.addResource("shared", ResourceType.CHAT_MODEL, descriptor());
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> agent.addResource("shared", ResourceType.MODEL_ROUTER, descriptor()));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("CHAT_MODEL"));
    }

    @Test
    void agentRejectsChatModelNameAlreadyUsedByRouter() {
        Agent agent = new Agent();
        agent.addResource("shared", ResourceType.MODEL_ROUTER, descriptor());
        assertThrows(
                IllegalArgumentException.class,
                () -> agent.addResource("shared", ResourceType.CHAT_MODEL, descriptor()));
    }

    @Test
    void distinctNamesAndUnrelatedTypesAreAllowed() {
        Agent agent = new Agent();
        agent.addResource("router", ResourceType.MODEL_ROUTER, descriptor());
        agent.addResource("small", ResourceType.CHAT_MODEL, descriptor());
        // same name across unrelated types is not a clash
        agent.addResource("router", ResourceType.PROMPT, descriptor());
    }

    @Test
    void executionEnvironmentRejectsChatModelRouterNameClash() {
        AgentsExecutionEnvironment env = stubEnvironment();
        env.addResource("shared", ResourceType.CHAT_MODEL, descriptor());
        assertThrows(
                IllegalArgumentException.class,
                () -> env.addResource("shared", ResourceType.MODEL_ROUTER, descriptor()));
    }

    /** Minimal stub — resource registration lives in the abstract base class under test. */
    private static AgentsExecutionEnvironment stubEnvironment() {
        return new AgentsExecutionEnvironment() {
            @Override
            public org.apache.flink.agents.api.configuration.Configuration getConfig() {
                return null;
            }

            @Override
            public AgentBuilder fromList(java.util.List<Object> input) {
                return null;
            }

            @Override
            public <T, K> AgentBuilder fromDataStream(
                    org.apache.flink.streaming.api.datastream.DataStream<T> input,
                    org.apache.flink.api.java.functions.KeySelector<T, K> keySelector) {
                return null;
            }

            @Override
            public <K> AgentBuilder fromTable(
                    org.apache.flink.table.api.Table input,
                    org.apache.flink.api.java.functions.KeySelector<Object, K> keySelector) {
                return null;
            }

            @Override
            public void execute() {}

            @Override
            public void execute(String jobName) {}
        };
    }
}
