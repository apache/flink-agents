/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flink.agents.runtime.metrics;

import org.apache.flink.agents.plan.AgentPlan;

import java.util.HashMap;

/**
 * Represents a group of built-in metrics for monitoring the performance and behavior of a flink
 * agent job. This class is responsible for collecting and managing various metrics such as the
 * number of events processed, the number of actions being executed, and the number of actions
 * executed per second.
 */
public class BuiltInMetricGroup {

    private final HashMap<String, ActionMetricGroup> actionMetricGroups;

    public BuiltInMetricGroup(FlinkAgentsMetricGroupImpl parentMetricGroup, AgentPlan agentPlan) {
        this.actionMetricGroups = new HashMap<>();
        for (String actionName : agentPlan.getActions().keySet()) {
            actionMetricGroups.put(
                    actionName, new ActionMetricGroup(parentMetricGroup.getSubGroup(actionName)));
        }
    }

    /**
     * Retrieves the metric group for a specific action.
     *
     * @param actionName The name of the action.
     * @return The ActionMetricGroup instance for the specified action.
     */
    public ActionMetricGroup getActionMetricGroup(String actionName) {
        return actionMetricGroups.get(actionName);
    }
}
