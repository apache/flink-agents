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

import org.apache.flink.agents.plan.serializer.AgentPlanJsonDeserializer;
import org.apache.flink.agents.plan.serializer.AgentPlanJsonSerializer;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** Agent plan compiled from user defined agent. */
@JsonSerialize(using = AgentPlanJsonSerializer.class)
@JsonDeserialize(using = AgentPlanJsonDeserializer.class)
public class AgentPlan implements Serializable {

    /** Mapping from action name to action itself. */
    private Map<String, Action> actions;

    /** Mapping from event class name to list of actions that should be triggered by the event. */
    private Map<String, List<Action>> actionsByEvent;

    public AgentPlan() {}

    public AgentPlan(Map<String, Action> actions, Map<String, List<Action>> actionsByEvent) {
        this.actions = actions;
        this.actionsByEvent = actionsByEvent;
    }

    public Map<String, Action> getActions() {
        return actions;
    }

    public Map<String, List<Action>> getActionsByEvent() {
        return actionsByEvent;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        String serializedStr = new ObjectMapper().writeValueAsString(this);
        out.writeUTF(serializedStr);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        String serializedStr = in.readUTF();
        AgentPlan agentPlan = new ObjectMapper().readValue(serializedStr, AgentPlan.class);
        this.actions = agentPlan.getActions();
        this.actionsByEvent = agentPlan.getActionsByEvent();
    }
}
