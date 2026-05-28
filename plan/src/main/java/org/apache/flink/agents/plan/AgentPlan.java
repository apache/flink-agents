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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.*;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.skills.SkillSourceSpec;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.actions.ChatModelAction;
import org.apache.flink.agents.plan.actions.ContextRetrievalAction;
import org.apache.flink.agents.plan.actions.ToolCallAction;
import org.apache.flink.agents.plan.resource.python.PythonMCPServer;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.JavaSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.serializer.AgentPlanJsonDeserializer;
import org.apache.flink.agents.plan.serializer.AgentPlanJsonSerializer;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.apache.flink.agents.plan.tools.ToolMetadataFactory;
import org.apache.flink.agents.plan.tools.bash.BashTool;
import org.apache.flink.api.java.tuple.Tuple3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.apache.flink.agents.api.resource.ResourceType.MCP_SERVER;
import static org.apache.flink.agents.api.resource.ResourceType.PROMPT;
import static org.apache.flink.agents.api.resource.ResourceType.TOOL;

/** Agent plan compiled from user defined agent. */
@JsonSerialize(using = AgentPlanJsonSerializer.class)
@JsonDeserialize(using = AgentPlanJsonDeserializer.class)
public class AgentPlan implements Serializable {
    private static final Logger LOG = LoggerFactory.getLogger(AgentPlan.class);
    private static final String JAVA_MCP_SERVER_CLASS_NAME =
            "org.apache.flink.agents.integrations.mcp.MCPServer";

    /** Mapping from action name to action itself. */
    private Map<String, Action> actions;

    /** Mapping from event type string to list of actions that should be triggered by the event. */
    private Map<String, List<Action>> actionsByEvent;

    /** Two-level mapping of resource type to resource name to resource provider. */
    private Map<ResourceType, Map<String, ResourceProvider>> resourceProviders;

    private AgentConfiguration config;

    public AgentPlan(Map<String, Action> actions, Map<String, List<Action>> actionsByEvent) {
        this.actions = actions;
        this.actionsByEvent = actionsByEvent;
        this.resourceProviders = new HashMap<>();
        this.config = new AgentConfiguration();
    }

    public AgentPlan(
            Map<String, Action> actions,
            Map<String, List<Action>> actionsByEvent,
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders) {
        this.actions = actions;
        this.actionsByEvent = actionsByEvent;
        this.resourceProviders = resourceProviders;
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
    }

    private void extractActions(
            String actionName,
            String[] listenEventTypeStrings,
            org.apache.flink.agents.plan.Function function,
            Map<String, Object> config)
            throws Exception {
        List<String> eventTypeNames = new ArrayList<>(Arrays.asList(listenEventTypeStrings));

        if (eventTypeNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "Action "
                            + actionName
                            + " must specify at least one event type via listenEventTypes.");
        }

        // Create an Action
        Action action = new Action(actionName, function, eventTypeNames, config);

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
        addBuiltAction(ContextRetrievalAction.getContextRetrievalAction());

        // Scan the agent class for methods annotated with @Action
        Class<?> agentClass = agent.getClass();
        // getDeclaredMethods() skips inherited @Action methods; reject loudly.
        for (Class<?> parent = agentClass.getSuperclass();
                parent != null && parent != Agent.class;
                parent = parent.getSuperclass()) {
            for (Method inherited : parent.getDeclaredMethods()) {
                if (inherited.isAnnotationPresent(
                        org.apache.flink.agents.api.annotation.Action.class)) {
                    throw new IllegalStateException(
                            "Inherited @Action '"
                                    + parent.getName()
                                    + "#"
                                    + inherited.getName()
                                    + "' is not supported; declare on the concrete agent.");
                }
            }
        }
        for (Method method : agentClass.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(org.apache.flink.agents.api.annotation.Action.class)) {
                continue;
            }
            org.apache.flink.agents.api.annotation.Action actionAnnotation =
                    Objects.requireNonNull(
                            method.getAnnotation(
                                    org.apache.flink.agents.api.annotation.Action.class));
            String[] listenEventTypeStrings = actionAnnotation.listenEventTypes();
            org.apache.flink.agents.api.annotation.PythonFunction target =
                    actionAnnotation.target();
            String targetModule = target.module();
            String targetQualname = target.qualname();
            boolean moduleSet = !targetModule.isEmpty();
            boolean qualnameSet = !targetQualname.isEmpty();

            org.apache.flink.agents.plan.Function execFunction;
            if (!moduleSet && !qualnameSet) {
                execFunction =
                        new org.apache.flink.agents.plan.JavaFunction(
                                method.getDeclaringClass(),
                                method.getName(),
                                method.getParameterTypes());
            } else if (moduleSet && qualnameSet) {
                execFunction =
                        new org.apache.flink.agents.plan.PythonFunction(
                                targetModule, targetQualname);
            } else {
                throw new IllegalStateException(
                        "PythonFunction target on '"
                                + method.getName()
                                + "' must set both module and qualname");
            }
            extractActions(method.getName(), listenEventTypeStrings, execFunction, null);
        }

        for (Map.Entry<
                        String,
                        Tuple3<
                                String[],
                                org.apache.flink.agents.api.function.Function,
                                Map<String, Object>>>
                action : agent.getActions().entrySet()) {
            String actionName = action.getKey();
            Tuple3<String[], org.apache.flink.agents.api.function.Function, Map<String, Object>>
                    tuple = action.getValue();
            extractActions(actionName, tuple.f0, toPlanFunction(tuple.f1), tuple.f2);
        }
    }

    private static ResourceDescriptor requireResourceDescriptor(
            String name, ResourceType type, Object value) {
        if (!(value instanceof ResourceDescriptor)) {
            throw new IllegalStateException(
                    String.format(
                            "Resource '%s' of type %s must be a ResourceDescriptor when added via"
                                    + " Agent.addResource, but got %s",
                            name, type, value == null ? "null" : value.getClass().getName()));
        }
        return (ResourceDescriptor) value;
    }

    private void extractResource(ResourceType type, Method method) throws Exception {
        extractResource(type, method, null);
    }

    private void extractResource(
            ResourceType type,
            Method method,
            Function<ResourceDescriptor, ResourceDescriptor> descriptorDecorator)
            throws Exception {
        String name = method.getName();
        ResourceProvider provider;
        ResourceDescriptor descriptor = (ResourceDescriptor) method.invoke(null);

        descriptor =
                descriptorDecorator != null ? descriptorDecorator.apply(descriptor) : descriptor;

        if (PythonResourceWrapper.class.isAssignableFrom(
                Class.forName(
                        descriptor.getClazz(),
                        true,
                        Thread.currentThread().getContextClassLoader()))) {
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
                JavaSerializableResourceProvider.createResourceProvider(name, TOOL, tool);

        addResourceProvider(provider);
    }

    private void extractJavaMCPServer(Method method) throws Exception {
        // Use reflection to handle MCP classes to support Java 11 without MCP
        String name = method.getName();

        ResourceDescriptor descriptor = (ResourceDescriptor) method.invoke(null);
        descriptor =
                new ResourceDescriptor(
                        descriptor.getModule(),
                        JAVA_MCP_SERVER_CLASS_NAME,
                        new HashMap<>(descriptor.getInitialArguments()));
        JavaResourceProvider provider = new JavaResourceProvider(name, MCP_SERVER, descriptor);

        addResourceProvider(provider);
        Object mcpServer = provider.provide(null);

        // Call listTools() via reflection
        Method listToolsMethod = mcpServer.getClass().getMethod("listTools");
        @SuppressWarnings("unchecked")
        Iterable<? extends SerializableResource> tools =
                (Iterable<? extends SerializableResource>) listToolsMethod.invoke(mcpServer);

        for (SerializableResource tool : tools) {
            Method getNameMethod = tool.getClass().getMethod("getName");
            String toolName = (String) getNameMethod.invoke(tool);
            addResourceProvider(
                    JavaSerializableResourceProvider.createResourceProvider(toolName, TOOL, tool));
        }

        // Call listPrompts() via reflection
        Method listPromptsMethod = mcpServer.getClass().getMethod("listPrompts");
        @SuppressWarnings("unchecked")
        Iterable<? extends SerializableResource> prompts =
                (Iterable<? extends SerializableResource>) listPromptsMethod.invoke(mcpServer);

        for (SerializableResource prompt : prompts) {
            Method getNameMethod = prompt.getClass().getMethod("getName");
            String promptName = (String) getNameMethod.invoke(prompt);
            addResourceProvider(
                    JavaSerializableResourceProvider.createResourceProvider(
                            promptName, PROMPT, prompt));
        }

        // Call close() via reflection
        Method closeMethod = mcpServer.getClass().getMethod("close");
        closeMethod.invoke(mcpServer);
    }

    private void extractResourceProvidersFromAgent(Agent agent) throws Exception {
        Class<?> agentClass = agent.getClass();

        // Collect Skills declarations from both @Skills methods and Agent.addResource(SKILLS, ...)
        Map<String, Skills> skillsObjects = new LinkedHashMap<>();

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
                                createResourceProvider(resourceName, TOOL, resource, agentClass);
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
                                promptName, PROMPT, prompt);

                addResourceProvider(provider);
            } else if (method.isAnnotationPresent(ChatModelSetup.class)) {
                extractResource(ResourceType.CHAT_MODEL, method);
            } else if (method.isAnnotationPresent(ChatModelConnection.class)) {
                extractResource(ResourceType.CHAT_MODEL_CONNECTION, method);
            } else if (method.isAnnotationPresent(EmbeddingModelSetup.class)) {
                extractResource(ResourceType.EMBEDDING_MODEL, method);
            } else if (method.isAnnotationPresent(EmbeddingModelConnection.class)) {
                extractResource(ResourceType.EMBEDDING_MODEL_CONNECTION, method);
            } else if (method.isAnnotationPresent(VectorStore.class)) {
                extractResource(ResourceType.VECTOR_STORE, method);
            } else if (method.isAnnotationPresent(
                            org.apache.flink.agents.api.annotation.Skills.class)
                    && Modifier.isStatic(method.getModifiers())) {
                Object value = method.invoke(null);
                if (!(value instanceof Skills)) {
                    throw new IllegalStateException(
                            "@Skills method "
                                    + method.getName()
                                    + " must return org.apache.flink.agents.api.skills.Skills");
                }
                skillsObjects.put(method.getName(), (Skills) value);
            } else if (method.isAnnotationPresent(MCPServer.class)) {
                // Check the MCPServer annotation version to determine which version to use.
                MCPServer MCPServerAnnotation = method.getAnnotation(MCPServer.class);
                String lang = MCPServerAnnotation.lang();
                int javaVersion = Runtime.version().feature();

                if (lang.equalsIgnoreCase("auto")) {
                    lang = javaVersion >= 17 ? "java" : "python";
                } else if (lang.equalsIgnoreCase("java") && javaVersion < 17) {
                    throw new UnsupportedOperationException(
                            "Java version is less than 17, please use python MCP server.");
                }

                if (lang.equalsIgnoreCase("java")) {
                    extractJavaMCPServer(method);
                } else {
                    LOG.warn(
                            "Using the Python MCP server with cross-language support. The Java version is "
                                    + javaVersion);
                    extractResource(
                            ResourceType.MCP_SERVER,
                            method,
                            desc ->
                                    new ResourceDescriptor(
                                            desc.getModule(),
                                            PythonMCPServer.class.getName(),
                                            new HashMap<>(desc.getInitialArguments())));
                }
            }
        }

        for (Map.Entry<ResourceType, Map<String, Object>> entry : agent.getResources().entrySet()) {
            ResourceType type = entry.getKey();
            if (type == PROMPT) {
                for (Map.Entry<String, Object> kv : entry.getValue().entrySet()) {
                    JavaSerializableResourceProvider provider =
                            JavaSerializableResourceProvider.createResourceProvider(
                                    kv.getKey(), PROMPT, (SerializableResource) kv.getValue());

                    addResourceProvider(provider);
                }
            } else if (type == TOOL) {
                for (Map.Entry<String, Object> kv : entry.getValue().entrySet()) {
                    String resourceName = kv.getKey();
                    Object value = kv.getValue();
                    if (value instanceof org.apache.flink.agents.api.tools.FunctionTool) {
                        registerApiFunctionTool(
                                resourceName,
                                (org.apache.flink.agents.api.tools.FunctionTool) value);
                    } else if (value instanceof SerializableResource) {
                        // Plan-layer tools added directly (MCP-generated, etc.) — pass through.
                        addResourceProvider(
                                JavaSerializableResourceProvider.createResourceProvider(
                                        resourceName, TOOL, (SerializableResource) value));
                    } else {
                        throw new IllegalStateException(
                                "Unsupported tool resource '" + resourceName + "': " + value);
                    }
                }
            } else if (type == ResourceType.SKILLS) {
                for (Map.Entry<String, Object> kv : entry.getValue().entrySet()) {
                    if (kv.getValue() instanceof Skills) {
                        skillsObjects.put(kv.getKey(), (Skills) kv.getValue());
                    }
                }
            } else if (type == MCP_SERVER) {
                if (!entry.getValue().isEmpty()) {
                    throw new UnsupportedOperationException(
                            "Adding an MCP server via Agent.addResource is not supported."
                                    + " Declare the MCP server with a @MCPServer-annotated static"
                                    + " method on your Agent class so its tools and prompts can be"
                                    + " discovered.");
                }
            } else {
                for (Map.Entry<String, Object> kv : entry.getValue().entrySet()) {
                    ResourceDescriptor descriptor =
                            requireResourceDescriptor(kv.getKey(), type, kv.getValue());
                    ResourceProvider provider;
                    if (PythonResourceWrapper.class.isAssignableFrom(
                            Class.forName(
                                    descriptor.getClazz(),
                                    true,
                                    Thread.currentThread().getContextClassLoader()))) {
                        provider = new PythonResourceProvider(kv.getKey(), type, descriptor);
                    } else {
                        provider = new JavaResourceProvider(kv.getKey(), type, descriptor);
                    }
                    addResourceProvider(provider);
                }
            }
        }

        addSkills(skillsObjects);
    }

    /**
     * Mirror of Python {@code _add_skills}: register the merged Skills config under {@link
     * Skills#SKILLS_CONFIG} plus the built-in {@code load_skill} and {@code bash} tools.
     *
     * <p>{@link BashTool} lives in this module so we can reference its class directly; {@code
     * LoadSkillTool} lives in the runtime module and is referenced by FQN string to avoid a reverse
     * dependency.
     */
    private void addSkills(Map<String, Skills> skillsObjects) throws Exception {
        if (skillsObjects.isEmpty()) {
            return;
        }

        addResourceProvider(
                new JavaResourceProvider(
                        Skills.LOAD_SKILL_TOOL,
                        ResourceType.TOOL,
                        new ResourceDescriptor(
                                "org.apache.flink.agents.runtime.skill.LoadSkillTool",
                                new HashMap<>())));
        addResourceProvider(
                new JavaResourceProvider(
                        Skills.BASH_TOOL,
                        ResourceType.TOOL,
                        new ResourceDescriptor(BashTool.class.getName(), new HashMap<>())));

        // Sort by key before merging: getDeclaredMethods() makes no order guarantee, so without
        // this the winner on a duplicate skill name would vary across JDK / class layout.
        List<String> orderedKeys = new ArrayList<>(skillsObjects.keySet());
        Collections.sort(orderedKeys);
        LinkedHashSet<SkillSourceSpec> sources = new LinkedHashSet<>();
        for (String key : orderedKeys) {
            sources.addAll(skillsObjects.get(key).getSources());
        }
        Skills merged = new Skills(new ArrayList<>(sources));
        addResourceProvider(
                JavaSerializableResourceProvider.createResourceProvider(
                        Skills.SKILLS_CONFIG, ResourceType.SKILLS, merged));
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

    /**
     * Promote an api-layer {@link org.apache.flink.agents.api.function.Function} descriptor to its
     * plan-layer twin. Java parameter type strings are resolved to {@link Class} here; Python
     * descriptors pass through unchanged.
     */
    private static org.apache.flink.agents.plan.Function toPlanFunction(
            org.apache.flink.agents.api.function.Function f) throws Exception {
        if (f instanceof org.apache.flink.agents.api.function.JavaFunction) {
            org.apache.flink.agents.api.function.JavaFunction jf =
                    (org.apache.flink.agents.api.function.JavaFunction) f;
            Class<?>[] params = resolveParameterTypes(jf.getParameterTypes());
            Class<?> clazz =
                    Class.forName(
                            jf.getQualName(), true, Thread.currentThread().getContextClassLoader());
            return new org.apache.flink.agents.plan.JavaFunction(clazz, jf.getMethodName(), params);
        }
        if (f instanceof org.apache.flink.agents.api.function.PythonFunction) {
            org.apache.flink.agents.api.function.PythonFunction pf =
                    (org.apache.flink.agents.api.function.PythonFunction) f;
            return new org.apache.flink.agents.plan.PythonFunction(
                    pf.getModule(), pf.getQualName());
        }
        throw new IllegalStateException("Unknown api.function.Function: " + f);
    }

    private static Class<?>[] resolveParameterTypes(List<String> names)
            throws ClassNotFoundException {
        Class<?>[] out = new Class<?>[names.size()];
        for (int i = 0; i < names.size(); i++) {
            out[i] = resolveParameterType(names.get(i));
        }
        return out;
    }

    private static Class<?> resolveParameterType(String name) throws ClassNotFoundException {
        switch (name) {
            case "boolean":
                return boolean.class;
            case "byte":
                return byte.class;
            case "short":
                return short.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "char":
                return char.class;
            case "void":
                return void.class;
            default:
                return Class.forName(name, true, Thread.currentThread().getContextClassLoader());
        }
    }

    /**
     * Promote an api-layer {@link org.apache.flink.agents.api.tools.FunctionTool} to a plan-layer
     * executable {@link FunctionTool} and register it under the YAML-declared resource name.
     */
    private void registerApiFunctionTool(
            String resourceName, org.apache.flink.agents.api.tools.FunctionTool apiTool)
            throws Exception {
        org.apache.flink.agents.api.function.Function func = apiTool.getFunc();
        if (func instanceof org.apache.flink.agents.api.function.JavaFunction) {
            org.apache.flink.agents.api.function.JavaFunction jf =
                    (org.apache.flink.agents.api.function.JavaFunction) func;
            Class<?>[] params = resolveParameterTypes(jf.getParameterTypes());
            Class<?> clazz =
                    Class.forName(
                            jf.getQualName(), true, Thread.currentThread().getContextClassLoader());
            Method method = clazz.getMethod(jf.getMethodName(), params);
            ToolMetadata metadata = ToolMetadataFactory.fromStaticMethod(method);
            org.apache.flink.agents.plan.JavaFunction planFunc =
                    new org.apache.flink.agents.plan.JavaFunction(clazz, method.getName(), params);
            FunctionTool tool = new FunctionTool(metadata, planFunc);
            addResourceProvider(
                    JavaSerializableResourceProvider.createResourceProvider(
                            resourceName, TOOL, tool));
        } else if (func instanceof org.apache.flink.agents.api.function.PythonFunction) {
            org.apache.flink.agents.api.function.PythonFunction pf =
                    (org.apache.flink.agents.api.function.PythonFunction) func;
            org.apache.flink.agents.plan.PythonFunction planFunc =
                    new org.apache.flink.agents.plan.PythonFunction(
                            pf.getModule(), pf.getQualName());
            // Placeholder metadata: ResourceCache will replace it with introspected values from
            // the Python bridge via FunctionTool.setPythonResourceAdapter at first resolve.
            ToolMetadata metadata = new ToolMetadata(resourceName, "", "{}");
            FunctionTool tool = new FunctionTool(metadata, planFunc);
            addResourceProvider(
                    JavaSerializableResourceProvider.createResourceProvider(
                            resourceName, TOOL, tool));
        } else {
            throw new IllegalStateException(
                    "Unknown api.function.Function for tool '" + resourceName + "': " + func);
        }
    }
}
