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

package org.apache.flink.agents.api.agents;

import org.apache.flink.agents.api.function.Function;
import org.apache.flink.agents.api.function.JavaFunction;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;
import org.apache.flink.agents.api.subagent.InternalSubagentResourceDescriptorBuilder;
import org.apache.flink.api.java.tuple.Tuple3;

import javax.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Base class for defining agent logic. */
public class Agent {
    private final Map<String, Tuple3<String[], Function, Map<String, Object>>> actions;

    private final Map<ResourceType, Map<String, Object>> resources;

    public Agent() {
        this.resources = new LinkedHashMap<>();
        for (ResourceType type : ResourceType.values()) {
            this.resources.put(type, new LinkedHashMap<>());
        }
        this.actions = new LinkedHashMap<>();
    }

    public Map<String, Tuple3<String[], Function, Map<String, Object>>> getActions() {
        return actions;
    }

    public Map<ResourceType, Map<String, Object>> getResources() {
        return resources;
    }

    /**
     * Add action to agent.
     *
     * @param triggerConditions Raw event-type names or Boolean conditions combined with OR
     *     semantics. Shape and expression validation occur during {@code AgentPlan} construction.
     * @param method The method of this action, should be static method.
     * @param config The optional config can be used by this action.
     */
    public Agent addAction(
            String[] triggerConditions, Method method, @Nullable Map<String, Object> config) {
        return addAction(
                method.getName(), triggerConditions, JavaFunction.fromMethod(method), config);
    }

    /**
     * Add action to agent.
     *
     * @param triggerConditions Raw event-type names or Boolean conditions combined with OR
     *     semantics. Shape and expression validation occur during {@code AgentPlan} construction.
     * @param method The method of this action, should be static method.
     */
    public Agent addAction(String[] triggerConditions, Method method) {
        return addAction(triggerConditions, method, null);
    }

    /**
     * Add action to agent.
     *
     * @param name The action name. Must be unique within this agent.
     * @param triggerConditions Raw event-type names or Boolean conditions combined with OR
     *     semantics. Shape and expression validation occur during {@code AgentPlan} construction.
     * @param function The api-layer function descriptor; will be promoted to a plan-layer
     *     executable at {@code AgentPlan} construction.
     * @param config Optional config for this action.
     */
    public Agent addAction(
            String name,
            String[] triggerConditions,
            Function function,
            @Nullable Map<String, Object> config) {
        if (actions.containsKey(name)) {
            throw new IllegalArgumentException(String.format("Action %s already defined.", name));
        }
        actions.put(name, new Tuple3<>(triggerConditions, function, config));
        return this;
    }

    /**
     * Merge environment-level resources into this agent. Entries already present at the agent level
     * are kept (agent wins on duplicates). Environment entries are placed before agent entries in
     * iteration order, so downstream merging sees env-then-agent precedence.
     *
     * <p>Order is load-bearing, not cosmetic: downstream consumers derive precedence from it.
     * {@code AgentPlan} flattens these entries into an ordered source list, and later entries win
     * on duplicate names. Overwriting an environment entry in place would leave the agent's
     * replacement in the environment's slot, so an unrelated environment entry could end up last
     * and win instead — the opposite of the contract above. Environment entries the agent overrides
     * are therefore dropped, and the agent's own entries are appended as a block.
     */
    public void addResourcesIfAbsent(Map<ResourceType, Map<String, Object>> resources) {
        for (ResourceType type : resources.keySet()) {
            Map<String, Object> envTyped = resources.get(type);
            if (envTyped == null || envTyped.isEmpty()) {
                continue;
            }
            Map<String, Object> existing = this.resources.get(type);
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Map.Entry<String, Object> envEntry : envTyped.entrySet()) {
                if (!existing.containsKey(envEntry.getKey())) {
                    merged.put(envEntry.getKey(), envEntry.getValue());
                }
            }
            // Appended as a block so agent entries stay last and keep winning on duplicates.
            merged.putAll(existing);
            this.resources.put(type, merged);
        }
    }

    /**
     * Add resource to agent.
     *
     * @param name The name indicate the resource.
     * @param type The type of the resource.
     * @param instance The serializable resource object, the resource descriptor, or — for an {@code
     *     AGENT} resource — an {@link Agent} wrapped in a descriptor and compiled into an internal
     *     sub-agent during plan construction.
     */
    public Agent addResource(String name, ResourceType type, Object instance) {
        if (resources.get(type).containsKey(name)) {
            throw new IllegalArgumentException(String.format("%s %s already defined.", type, name));
        }
        checkNoChatModelRouterNameClash(name, type, resources);

        if (type == ResourceType.AGENT && instance instanceof Agent) {
            instance =
                    new InternalSubagentResourceDescriptorBuilder().agent((Agent) instance).build();
        }
        if (instance instanceof SerializableResource) {
            resources.get(type).put(name, instance);
        } else if (instance instanceof ResourceDescriptor) {
            resources.get(type).put(name, instance);
        } else {
            throw new IllegalArgumentException(
                    String.format("Unsupported resource %s", instance.getClass().getName()));
        }
        return this;
    }

    /**
     * Chat models and model routers share the chat request namespace ({@code ChatRequestEvent}
     * names either), so one name must not be registered as both. Checked here, at the registration
     * call site, so the failure points at the user's own {@code addResource} line; {@code
     * AgentPlan} re-validates as a backstop.
     */
    public static void checkNoChatModelRouterNameClash(
            String name, ResourceType type, Map<ResourceType, Map<String, Object>> resources) {
        ResourceType clashing =
                type == ResourceType.CHAT_MODEL
                        ? ResourceType.MODEL_ROUTER
                        : type == ResourceType.MODEL_ROUTER ? ResourceType.CHAT_MODEL : null;
        if (clashing != null
                && resources.containsKey(clashing)
                && resources.get(clashing).containsKey(name)) {
            throw new IllegalArgumentException(
                    String.format(
                            "'%s' is already registered as %s; chat models and model routers share the"
                                    + " chat request namespace and must use distinct names.",
                            name, clashing));
        }
    }

    public static String STRUCTURED_OUTPUT = "structured_output";
}
