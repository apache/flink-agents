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

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.serializer.AgentPlanJsonDeserializer;
import org.apache.flink.agents.plan.serializer.AgentPlanJsonSerializer;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
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

    /**
     * Constructor that creates an AgentPlan from an Agent instance by scanning for @Action
     * annotations.
     *
     * @param agent the agent instance to scan for actions
     * @throws Exception if there's an error creating actions from the agent
     */
    public AgentPlan(Agent agent) throws Exception {
        this.actions = new HashMap<>();
        this.actionsByEvent = new HashMap<>();
        extractActionsFromAgent(agent);
    }

    public Map<String, Action> getActions() {
        return actions;
    }

    public Map<String, List<Action>> getActionsByEvent() {
        return actionsByEvent;
    }

    public List<Action> getActionsTriggeredBy(String eventType) {
        return actionsByEvent.get(eventType);
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

    private void extractActionsFromAgent(Agent agent) throws Exception {
        // Scan the agent class for methods annotated with @Action
        Class<?> agentClass = agent.getClass();
        for (Method method : agentClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.apache.flink.agents.api.Action.class)) {
                org.apache.flink.agents.api.Action actionAnnotation =
                        method.getAnnotation(org.apache.flink.agents.api.Action.class);

                // Get the event types this action listens to
                Class<? extends Event>[] listenEventTypes = actionAnnotation.listenEvents();

                // Convert event types to string names
                List<String> eventTypeNames = new ArrayList<>();
                for (Class<? extends Event> eventType : listenEventTypes) {
                    eventTypeNames.add(eventType.getName());
                }

                // Create a JavaFunction for this method
                JavaFunction javaFunction =
                        new JavaFunction(agentClass, method.getName(), method.getParameterTypes());

                // Create an Action
                Action action = new Action(method.getName(), javaFunction, eventTypeNames);

                // Add to actions map
                actions.put(action.getName(), action);

                // Add to actionsByEvent map
                for (String eventTypeName : eventTypeNames) {
                    actionsByEvent
                            .computeIfAbsent(eventTypeName, k -> new ArrayList<>())
                            .add(action);
                }
            }
        }
    }
}
