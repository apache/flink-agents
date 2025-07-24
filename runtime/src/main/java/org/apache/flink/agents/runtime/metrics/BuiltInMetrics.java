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
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Meter;

import java.util.HashMap;

/**
 * Represents a group of built-in metrics for monitoring the performance and behavior of a flink
 * agent job. This class is responsible for collecting and managing various metrics such as the
 * number of events processed, the number of actions being executed, and the number of actions
 * executed per second.
 */
public class BuiltInMetrics {

    private final Meter numOfEventProcessedPerSec;

    private final Meter numOfActionsExecutedPerSec;

    private final HashMap<String, BuiltInActionMetrics> actionMetricGroups;

    public BuiltInMetrics(FlinkAgentsMetricGroupImpl parentMetricGroup, AgentPlan agentPlan) {
        Counter numOfEventsProcessed = parentMetricGroup.getCounter("numOfEventProcessed");
        this.numOfEventProcessedPerSec =
                parentMetricGroup.getMeter("numOfEventProcessedPerSec", numOfEventsProcessed);

        Counter numOfActionsExecuted = parentMetricGroup.getCounter("numOfActionsExecuted");
        this.numOfActionsExecutedPerSec =
                parentMetricGroup.getMeter("numOfActionsExecutedPerSec", numOfActionsExecuted);

        this.actionMetricGroups = new HashMap<>();
        for (String actionName : agentPlan.getActions().keySet()) {
            actionMetricGroups.put(
                    actionName,
                    new BuiltInActionMetrics(parentMetricGroup.getSubGroup(actionName)));
        }
    }

    /** Records the occurrence of an event, increasing the count of events processed per second. */
    public void markEventProcessed() {
        numOfEventProcessedPerSec.markEvent();
    }

    /**
     * Marks that an action has finished executing. Decrements the executing actions counter and
     * marks an event on the executed meter.
     */
    public void markActionExecuted(String actionName) {
        numOfActionsExecutedPerSec.markEvent();
        actionMetricGroups.get(actionName).markActionExecuted();
    }
}
