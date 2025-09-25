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
package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.utils.JsonUtils;
import org.apache.flink.agents.runtime.memory.CachedMemoryStore;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.flink.util.Preconditions;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * The implementation class of {@link RunnerContext}, which serves as the execution context for
 * actions.
 */
public class RunnerContextImpl implements RunnerContext {

    protected final List<Event> pendingEvents = new ArrayList<>();
    protected final CachedMemoryStore store;
    protected final FlinkAgentsMetricGroupImpl agentMetricGroup;
    protected final Runnable mailboxThreadChecker;
    protected final AgentPlan agentPlan;
    protected final List<MemoryUpdate> memoryUpdates;
    protected String actionName;

    public RunnerContextImpl(
            CachedMemoryStore store,
            FlinkAgentsMetricGroupImpl agentMetricGroup,
            Runnable mailboxThreadChecker,
            AgentPlan agentPlan) {
        this.store = store;
        this.agentMetricGroup = agentMetricGroup;
        this.mailboxThreadChecker = mailboxThreadChecker;
        this.agentPlan = agentPlan;
        this.memoryUpdates = new LinkedList<>();
    }

    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    @Override
    public FlinkAgentsMetricGroupImpl getAgentMetricGroup() {
        return agentMetricGroup;
    }

    @Override
    public FlinkAgentsMetricGroupImpl getActionMetricGroup() {
        return agentMetricGroup.getSubGroup(actionName);
    }

    @Override
    public void sendEvent(Event event) {
        mailboxThreadChecker.run();
        try {
            JsonUtils.checkSerializable(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Event is not JSON serializable. All events sent to context must be JSON serializable.",
                    e);
        }
        pendingEvents.add(event);
    }

    public List<Event> drainEvents() {
        mailboxThreadChecker.run();
        List<Event> list = new ArrayList<>(this.pendingEvents);
        this.pendingEvents.clear();
        return list;
    }

    public void checkNoPendingEvents() {
        Preconditions.checkState(
                this.pendingEvents.isEmpty(), "There are pending events remaining in the context.");
    }

    /**
     * Gets all the updates made to this MemoryObject since it was created or the last time this
     * method was called. This method lives here because it is internally used by the ActionTask to
     * persist memory updates after an action is executed.
     *
     * @return list of memory updates
     */
    public List<MemoryUpdate> getAllMemoryUpdates() {
        mailboxThreadChecker.run();
        return List.copyOf(memoryUpdates);
    }

    @Override
    public MemoryObject getShortTermMemory() throws Exception {
        mailboxThreadChecker.run();
        return new MemoryObjectImpl(
                store, MemoryObjectImpl.ROOT_KEY, mailboxThreadChecker, memoryUpdates);
    }

    @Override
    public Resource getResource(String name, ResourceType type) throws Exception {
        if (agentPlan == null) {
            throw new IllegalStateException("AgentPlan is not available in this context");
        }
        return agentPlan.getResource(name, type);
    }

    @Override
    public ReadableConfiguration getConfig() {
        return agentPlan.getConfig();
    }

    @Override
    public Map<String, Object> getActionConfig() {
        return agentPlan.getActionConfig(actionName);
    }

    @Override
    public Object getActionConfigValue(String key) {
        return agentPlan.getActionConfigValue(actionName, key);
    }

    public String getActionName() {
        return actionName;
    }

    public void persistMemory() throws Exception {
        store.persistCache();
    }
}
