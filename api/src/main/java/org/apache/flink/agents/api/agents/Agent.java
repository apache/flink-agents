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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;
import org.apache.flink.api.java.tuple.Tuple3;

import javax.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Base class for defining agent logic. */
public class Agent {
    private final Map<String, Tuple3<Class<? extends Event>[], Method, Map<String, Object>>>
            actions;

    private final Map<ResourceType, Map<String, Object>> resources;

    public Agent() {
        this.resources = new HashMap<>();
        for (ResourceType type : ResourceType.values()) {
            this.resources.put(type, new HashMap<>());
        }
        this.actions = new HashMap<>();
    }

    public Map<String, Tuple3<Class<? extends Event>[], Method, Map<String, Object>>> getActions() {
        return actions;
    }

    public Map<ResourceType, Map<String, Object>> getResources() {
        return resources;
    }

    /**
     * Add action to agent.
     *
     * @param events The event types this action listened.
     * @param method The method of this action, should be static method.
     * @param config The optional config can be used by this action.
     */
    public Agent addAction(
            Class<? extends Event>[] events, Method method, @Nullable Map<String, Object> config) {
        String name = method.getName();
        if (actions.containsKey(name)) {
            throw new IllegalArgumentException(String.format("Action %s already defined.", name));
        }
        actions.put(name, new Tuple3<>(events, method, config));
        return this;
    }

    /**
     * Add action to agent.
     *
     * @param events The event types this action listened.
     * @param method The method of this action, should be static method.
     */
    public Agent addAction(Class<? extends Event>[] events, Method method) {
        return addAction(events, method, null);
    }

    public void addResourcesIfAbsent(Map<ResourceType, Map<String, Object>> resources) {
        for (ResourceType type : resources.keySet()) {
            Map<String, Object> typedResources = resources.get(type);
            typedResources.forEach(this.resources.get(type)::putIfAbsent);
        }
    }

    /**
     * Add resource to agent.
     *
     * @param name The name indicate the resource.
     * @param type The type of the resource.
     * @param instance The serializable resource object, or the resource descriptor.
     */
    public Agent addResource(String name, ResourceType type, Object instance) {
        if (resources.get(type).containsKey(name)) {
            throw new IllegalArgumentException(String.format("%s %s already defined.", type, name));
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

    public enum ErrorHandlingStrategy {
        FAIL("fail"),
        RETRY("retry"),
        IGNORE("ignore");

        private final String value;

        ErrorHandlingStrategy(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public static String STRUCTURED_OUTPUT = "structured_output";
}
