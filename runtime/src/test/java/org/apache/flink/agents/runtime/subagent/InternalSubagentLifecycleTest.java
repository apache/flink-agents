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

package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Shutdown must release child resources without assuming ownership of inherited resources. */
class InternalSubagentLifecycleTest {
    @Test
    void closeReleasesLocalResourcesAndLeavesInheritedResourcesToTheirOwner() throws Exception {
        ClosingResource shared = new ClosingResource(null);
        ClosingResource local = new ClosingResource(null);
        try (ResourceCache parent = new ResourceCache(Map.of())) {
            parent.put("shared", ResourceType.CHAT_MODEL_CONNECTION, shared);
            InternalSubagentSetup setup = setup("child");
            ResourceCache child = childCache(setup, parent);
            child.put("local", ResourceType.CHAT_MODEL_CONNECTION, local);
            assertThat(child.getResource("shared", ResourceType.CHAT_MODEL_CONNECTION))
                    .isSameAs(shared);

            setup.close();
            setup.close();
            assertThat(local.closes).isEqualTo(1);
            assertThat(shared.closes).isZero();
        }
        assertThat(shared.closes).isEqualTo(1);
    }

    @Test
    void parentShutdownClosesNestedCachesEvenWhenResourcesThrow() throws Exception {
        ClosingResource local = new ClosingResource(null);
        ClosingResource nestedLocal = new ClosingResource(null);
        IOException localFailure = new IOException("local close failed");
        AssertionError nestedFailure = new AssertionError("nested close failed");
        local.failure = localFailure;
        nestedLocal.failure = nestedFailure;
        ResourceCache parent = new ResourceCache(Map.of());
        InternalSubagentSetup setup = setup("child");
        InternalSubagentSetup nestedSetup = setup("nested");
        parent.put("child", ResourceType.AGENT, setup);
        ResourceCache child = childCache(setup, parent);
        child.put("local", ResourceType.CHAT_MODEL_CONNECTION, local);
        child.put("nested", ResourceType.AGENT, nestedSetup);
        childCache(nestedSetup, child)
                .put("nested-local", ResourceType.CHAT_MODEL_CONNECTION, nestedLocal);

        Throwable failure = catchThrowable(parent::close);
        assertThat(failure).isIn(localFailure, nestedFailure);
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).isIn(localFailure, nestedFailure);
        assertThat(failure.getSuppressed()[0]).isNotSameAs(failure);
        parent.close();
        setup.close();
        nestedSetup.close();
        assertThat(local.closes).isEqualTo(1);
        assertThat(nestedLocal.closes).isEqualTo(1);
    }

    @Test
    void closeCancelsPendingCallsAndUnregistersTheirOwner() throws Exception {
        InternalSubagentSetup setup = setup("child");
        OwnerContext context = new OwnerContext();
        setup.bootstrap(context, "session", "call", "prompt");
        InternalSubagentCallStatus call = setup.getCallStatus("session", "call");

        setup.close();
        setup.close();

        assertThat(call.getResponseFuture().isCancelled()).isTrue();
        assertThat(setup.getCallStatus("session", "call")).isNull();
        assertThat(context.unregistrations).isEqualTo(1);
    }

    private static class ClosingResource extends Resource {
        private int closes;
        private Throwable failure;

        ClosingResource(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public ResourceType getResourceType() {
            return ResourceType.CHAT_MODEL_CONNECTION;
        }

        @Override
        public void close() throws Exception {
            closes++;
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            if (failure != null) {
                throw (Exception) failure;
            }
        }
    }

    private static class OwnerContext extends RunnerContextImpl {
        private int unregistrations;

        OwnerContext() {
            super(null, () -> {}, new AgentPlan(Map.of()), null, "test");
        }

        @Override
        public void sendEvent(Event event) {}

        @Override
        public void unregisterInternalCallOwner(String sessionId) {
            super.unregisterInternalCallOwner(sessionId);
            unregistrations++;
        }
    }

    private static InternalSubagentSetup setup(String scope) {
        return new InternalSubagentSetup(scope, new AgentPlan(Map.of()));
    }

    private static ResourceCache childCache(InternalSubagentSetup setup, ResourceCache parent) {
        return setup.getOrCreateChildCache(
                InternalSubagentLifecycleTest.class.getClassLoader(), parent);
    }
}
