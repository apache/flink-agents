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
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.EmbeddingModelConnection;
import org.apache.flink.agents.api.annotation.EmbeddingModelSetup;
import org.apache.flink.agents.api.annotation.Prompt;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.actions.ChatModelAction;
import org.apache.flink.agents.plan.actions.ToolCallAction;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.JavaSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.serializer.AgentPlanJsonDeserializer;
import org.apache.flink.agents.plan.serializer.AgentPlanJsonSerializer;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.apache.flink.agents.plan.tools.ToolMetadataFactory;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Agent plan compiled from user defined agent. */
@JsonSerialize(using = AgentPlanJsonSerializer.class)
@JsonDeserialize(using = AgentPlanJsonDeserializer.class)
public class AgentPlan implements Serializable {

    /** Mapping from action name to action itself. */
    private Map<String, Action> actions;

    /** Mapping from event class name to list of actions that should be triggered by the event. */
    private Map<String, List<Action>> actionsByEvent;

    /** Two-level mapping of resource type to resource name to resource provider. */
    private Map<ResourceType, Map<String, ResourceProvider>> resourceProviders;

    private AgentConfiguration config;

    private transient PythonResourceAdapter pythonResourceAdapter;

    /** Cache for instantiated resources. */
    private transient Map<ResourceType, Map<String, Resource>> resourceCache;

    public AgentPlan(Map<String, Action> actions, Map<String, List<Action>> actionsByEvent) {
        this.actions = actions;
        this.actionsByEvent = actionsByEvent;
        this.resourceProviders = new HashMap<>();
        this.config = new AgentConfiguration();
        this.resourceCache = new ConcurrentHashMap<>();
    }

    public AgentPlan(
            Map<String, Action> actions,
            Map<String, List<Action>> actionsByEvent,
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders) {
        this.actions = actions;
        this.actionsByEvent = actionsByEvent;
        this.resourceProviders = resourceProviders;
        this.resourceCache = new ConcurrentHashMap<>();
        this.config = new AgentConfiguration();
    }

    public AgentPlan(
            Map<String, Action> actions,
            Map<String, List<Action>> actionsByEvent,
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders,
            AgentConfiguration config) {
        this.actions = actions;
        this.actionsByEvent = actionsByEvent;
        this.resourceProviders = resourceProviders;
        this.resourceCache = new ConcurrentHashMap<>();
        this.config = config;
    }

    /**
     * Constructor that creates an AgentPlan from an Agent instance by scanning for all types of
     * annotations.
     *
     * @param agent the agent instance to scan for actions
     * @throws Exception if there's an error creating actions from the agent
     */
    public AgentPlan(Agent agent) throws Exception {
        this(agent, new AgentConfiguration());
    }

    public AgentPlan(Agent agent, AgentConfiguration config) throws Exception {
        this(new HashMap<>(), new HashMap<>());
        extractActionsFromAgent(agent);
        extractResourceProvidersFromAgent(agent);
        this.config = config;
    }

    public void setPythonResourceAdapter(PythonResourceAdapter adapter) {
        this.pythonResourceAdapter = adapter;
    }

    public Map<String, Action> getActions() {
        return actions;
    }

    public Map<String, Object> getActionConfig(String actionName) {
        return actions.get(actionName).getConfig();
    }

    public Object getActionConfigValue(String actionName, String key) {
        return Objects.requireNonNull(actions.get(actionName).getConfig()).get(key);
    }

    public Map<String, List<Action>> getActionsByEvent() {
        return actionsByEvent;
    }

    public Map<ResourceType, Map<String, ResourceProvider>> getResourceProviders() {
        return resourceProviders;
    }

    public List<Action> getActionsTriggeredBy(String eventType) {
        return actionsByEvent.get(eventType);
    }

    /**
     * Get resource from agent plan.
     *
     * @param name the resource name
     * @param type the resource type
     * @return the resource instance
     * @throws Exception if the resource cannot be found or created
     */
    public Resource getResource(String name, ResourceType type) throws Exception {
        // Check cache first
        if (resourceCache.containsKey(type) && resourceCache.get(type).containsKey(name)) {
            return resourceCache.get(type).get(name);
        }

        // Get resource provider
        if (!resourceProviders.containsKey(type)
                || !resourceProviders.get(type).containsKey(name)) {
            throw new IllegalArgumentException("Resource not found: " + name + " of type " + type);
        }

        ResourceProvider provider = resourceProviders.get(type).get(name);

        if (pythonResourceAdapter != null && provider instanceof PythonResourceProvider) {
            ((PythonResourceProvider) provider).setPythonResourceAdapter(pythonResourceAdapter);
        }

        // Create resource using provider
        Resource resource =
                provider.provide(
                        (String anotherName, ResourceType anotherType) -> {
                            try {
                                return this.getResource(anotherName, anotherType);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });

        // Cache the resource
        resourceCache.computeIfAbsent(type, k -> new ConcurrentHashMap<>()).put(name, resource);

        return resource;
    }

    public AgentConfiguration getConfig() {
        return config;
    }

    public Map<String, Object> getConfigData() {
        return config.getConfData();
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
        this.resourceProviders = agentPlan.getResourceProviders();
        this.config = agentPlan.getConfig();
        this.resourceCache = new ConcurrentHashMap<>();
    }

    private void extractActions(
            Class<? extends Event>[] listenEventTypes, Method method, Map<String, Object> config)
            throws Exception {
        // Convert event types to string names
        List<String> eventTypeNames = new ArrayList<>();
        for (Class<? extends Event> eventType : listenEventTypes) {
            eventTypeNames.add(eventType.getName());
        }

        // Create a JavaFunction for this method
        JavaFunction javaFunction =
                new JavaFunction(
                        method.getDeclaringClass(), method.getName(), method.getParameterTypes());

        // Create an Action
        Action action = new Action(method.getName(), javaFunction, eventTypeNames, config);

        // Add to actions map
        actions.put(action.getName(), action);

        // Add to actionsByEvent map
        for (String eventTypeName : eventTypeNames) {
            actionsByEvent.computeIfAbsent(eventTypeName, k -> new ArrayList<>()).add(action);
        }
    }

    private void addBuiltAction(Action action) {
        // Add to actions map
        actions.put(action.getName(), action);

        // Add to actionsByEvent map
        for (String eventTypeName : action.getListenEventTypes()) {
            actionsByEvent.computeIfAbsent(eventTypeName, k -> new ArrayList<>()).add(action);
        }
    }

    private void extractActionsFromAgent(Agent agent) throws Exception {
        // Add built-in actions
        addBuiltAction(ChatModelAction.getChatModelAction());
        addBuiltAction(ToolCallAction.getToolCallAction());

        // Scan the agent class for methods annotated with @Action
        Class<?> agentClass = agent.getClass();
        for (Method method : agentClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.apache.flink.agents.api.annotation.Action.class)) {
                org.apache.flink.agents.api.annotation.Action actionAnnotation =
                        method.getAnnotation(org.apache.flink.agents.api.annotation.Action.class);

                // Get the event types this action listens to
                Class<? extends Event>[] listenEventTypes =
                        Objects.requireNonNull(actionAnnotation).listenEvents();

                extractActions(listenEventTypes, method, null);
            }
        }

        for (Map.Entry<String, Tuple3<Class<? extends Event>[], Method, Map<String, Object>>>
                action : agent.getActions().entrySet()) {
            Tuple3<Class<? extends Event>[], Method, Map<String, Object>> tuple = action.getValue();
            extractActions(tuple.f0, tuple.f1, tuple.f2);
        }
    }

    private void extractResource(ResourceType type, Method method) throws Exception {
        String name = method.getName();
        ResourceProvider provider;
        ResourceDescriptor descriptor = (ResourceDescriptor) method.invoke(null);
        if (PythonResourceWrapper.class.isAssignableFrom(Class.forName(descriptor.getClazz()))) {
            provider = new PythonResourceProvider(name, type, descriptor);
        } else {
            provider = new JavaResourceProvider(name, type, descriptor);
        }
        addResourceProvider(provider);
    }

    private void extractTool(Method method) throws Exception {
        String name = method.getName();

        // Build parameter type names for reconstruction
        Class<?>[] paramTypes = method.getParameterTypes();

        ToolMetadata metadata = ToolMetadataFactory.fromStaticMethod(method);
        JavaFunction javaFunction =
                new JavaFunction(method.getDeclaringClass(), method.getName(), paramTypes);

        FunctionTool tool = new FunctionTool(metadata, javaFunction);
        JavaSerializableResourceProvider provider =
                JavaSerializableResourceProvider.createResourceProvider(
                        name, ResourceType.TOOL, tool);

        addResourceProvider(provider);
    }

    private void extractResourceProvidersFromAgent(Agent agent) throws Exception {
        Class<?> agentClass = agent.getClass();

        // Scan all fields in the agent class for @Tool and @ChatModel annotations
        for (Field field : agentClass.getDeclaredFields()) {
            field.setAccessible(true); // Allow access to private fields

            String errMsg =
                    "Failed to access field "
                            + field.getName()
                            + " in agent class "
                            + agentClass.getName();

            // Check for @Tool annotation
            if (field.isAnnotationPresent(Tool.class)) {
                String resourceName = field.getName();

                try {
                    Object fieldValue = field.get(agent);
                    if (fieldValue instanceof Resource) {
                        Resource resource = (Resource) fieldValue;
                        ResourceProvider provider =
                                createResourceProvider(
                                        resourceName, ResourceType.TOOL, resource, agentClass);
                        addResourceProvider(provider);
                    }
                } catch (IllegalAccessException e) {
                    throw new Exception(errMsg, e);
                }
            }

            // Check for @ChatModel annotation
            if (field.isAnnotationPresent(ChatModelSetup.class)) {
                ChatModelSetup chatModelAnnotation = field.getAnnotation(ChatModelSetup.class);
                String resourceName = field.getName();

                try {
                    Object fieldValue = field.get(agent);
                    if (fieldValue instanceof Resource) {
                        Resource resource = (Resource) fieldValue;
                        ResourceProvider provider =
                                createResourceProvider(
                                        resourceName,
                                        ResourceType.CHAT_MODEL,
                                        resource,
                                        agentClass);
                        addResourceProvider(provider);
                    }
                } catch (IllegalAccessException e) {
                    throw new Exception(errMsg, e);
                }
            }
        }

        // Scan static methods annotated with @Tool, @Prompt, @ChatModel .etc
        for (Method method : agentClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)
                    && Modifier.isStatic(method.getModifiers())) {
                extractTool(method);
            } else if (method.isAnnotationPresent(Prompt.class)) {
                String promptName = method.getName();
                SerializableResource prompt = (SerializableResource) method.invoke(null);

                JavaSerializableResourceProvider provider =
                        JavaSerializableResourceProvider.createResourceProvider(
                                promptName, ResourceType.PROMPT, prompt);

                addResourceProvider(provider);
            } else if (method.isAnnotationPresent(ChatModelSetup.class)) {
                extractResource(ResourceType.CHAT_MODEL, method);
            } else if (method.isAnnotationPresent(ChatModelConnection.class)) {
                extractResource(ResourceType.CHAT_MODEL_CONNECTION, method);
            } else if (method.isAnnotationPresent(EmbeddingModelSetup.class)) {
                extractResource(ResourceType.EMBEDDING_MODEL, method);
            } else if (method.isAnnotationPresent(EmbeddingModelConnection.class)) {
                extractResource(ResourceType.EMBEDDING_MODEL_CONNECTION, method);
            }
        }

        for (Map.Entry<ResourceType, Map<String, Object>> entry : agent.getResources().entrySet()) {
            ResourceType type = entry.getKey();
            if (type == ResourceType.CHAT_MODEL || type == ResourceType.CHAT_MODEL_CONNECTION) {
                for (Map.Entry<String, Object> kv : entry.getValue().entrySet()) {
                    ResourceProvider provider;
                    if (PythonResourceWrapper.class.isAssignableFrom(
                            Class.forName(((ResourceDescriptor) kv.getValue()).getClazz()))) {
                        provider =
                                new PythonResourceProvider(
                                        kv.getKey(), type, (ResourceDescriptor) kv.getValue());
                    } else {
                        provider =
                                new JavaResourceProvider(
                                        kv.getKey(), type, (ResourceDescriptor) kv.getValue());
                    }
                    addResourceProvider(provider);
                }
            } else if (type == ResourceType.PROMPT) {
                for (Map.Entry<String, Object> kv : entry.getValue().entrySet()) {
                    JavaSerializableResourceProvider provider =
                            JavaSerializableResourceProvider.createResourceProvider(
                                    kv.getKey(),
                                    ResourceType.PROMPT,
                                    (SerializableResource) kv.getValue());

                    addResourceProvider(provider);
                }
            } else if (type == ResourceType.TOOL) {
                for (Map.Entry<String, Object> kv : entry.getValue().entrySet()) {
                    extractTool(
                            ((org.apache.flink.agents.api.tools.FunctionTool) kv.getValue())
                                    .getMethod());
                }
            }
        }
    }

    /**
     * Creates an appropriate ResourceProvider based on the resource type and whether it's
     * serializable.
     */
    private ResourceProvider createResourceProvider(
            String name, ResourceType type, Resource resource, Class<?> agentClass)
            throws Exception {
        if (resource instanceof SerializableResource) {
            // For serializable resources, use JavaSerializableResourceProvider
            SerializableResource serializableResource = (SerializableResource) resource;
            return JavaSerializableResourceProvider.createResourceProvider(
                    name, type, serializableResource);
        } else {
            throw new UnsupportedOperationException(
                    "Only support declared SerializableResource as field of Agent.");
        }
    }

    /** Adds a resource provider to the resourceProviders map. */
    private void addResourceProvider(ResourceProvider provider) {
        resourceProviders
                .computeIfAbsent(provider.getType(), k -> new HashMap<>())
                .put(provider.getName(), provider);
    }
}
