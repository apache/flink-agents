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

package org.apache.flink.agents.plan.subagent;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;

/**
 * Plan-side descriptor of an internal sub-agent: the compiled child plan plus its scope name,
 * produced during {@link AgentPlan} compilation when an {@code Agent} is registered as an {@code
 * AGENT} resource. Carries only plan content; all runtime behaviour (call statuses, child caches,
 * bootstrap/await) lives in the runtime class it materializes.
 *
 * <p>Materialization follows route A: the runtime class is referenced by name and instantiated
 * reflectively at {@link #provide}, preserving the {@code runtime -> plan} dependency direction.
 * The eagerly materialized instance implements the runtime's {@code TaskLifecycleListener}, so the
 * operator registers it for lifecycle broadcast through its existing instanceof discovery.
 */
public class InternalSubagentProvider extends ResourceProvider {

    /** Runtime class materialized for this descriptor; referenced by name, not by dependency. */
    public static final String RUNTIME_SETUP_CLASS =
            "org.apache.flink.agents.runtime.subagent.InternalSubagentSetup";

    private final String scope;

    private final AgentPlan childPlan;

    public InternalSubagentProvider(String name, AgentPlan childPlan) {
        super(name, ResourceType.AGENT);
        this.scope = name;
        this.childPlan = childPlan;
    }

    public String getScope() {
        return scope;
    }

    public AgentPlan getChildPlan() {
        return childPlan;
    }

    @Override
    public Resource provide(ResourceContext resourceContext) throws Exception {
        Class<?> clazz =
                Class.forName(
                        RUNTIME_SETUP_CLASS, true, Thread.currentThread().getContextClassLoader());
        return (Resource)
                clazz.getConstructor(String.class, AgentPlan.class).newInstance(scope, childPlan);
    }
}
