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

import org.apache.flink.agents.plan.serializer.WorkflowPlanJsonDeserializer;
import org.apache.flink.agents.plan.serializer.WorkflowPlanJsonSerializer;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.List;
import java.util.Map;

/** Workflow plan compiled from user defined workflow. */
@JsonSerialize(using = WorkflowPlanJsonSerializer.class)
@JsonDeserialize(using = WorkflowPlanJsonDeserializer.class)
public class WorkflowPlan {
    private final Map<String, Action> actions;
    private final Map<String, List<Action>> eventTriggerActions;

    public WorkflowPlan(
            Map<String, Action> actions, Map<String, List<Action>> eventTriggerActions) {
        this.actions = actions;
        this.eventTriggerActions = eventTriggerActions;
    }

    public Map<String, Action> getActions() {
        return actions;
    }

    public Map<String, List<Action>> getEventTriggerActions() {
        return eventTriggerActions;
    }
}
