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

package org.apache.flink.agents.api.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.function.Function;
import org.apache.flink.agents.api.function.JavaFunction;
import org.apache.flink.agents.api.function.PythonFunction;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.SkillSourceSpec;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.FunctionTool;
import org.apache.flink.agents.api.yaml.spec.ActionSpec;
import org.apache.flink.agents.api.yaml.spec.AgentActionRef;
import org.apache.flink.agents.api.yaml.spec.AgentSpec;
import org.apache.flink.agents.api.yaml.spec.DescriptorSpec;
import org.apache.flink.agents.api.yaml.spec.PackageSkillSpec;
import org.apache.flink.agents.api.yaml.spec.PromptSpec;
import org.apache.flink.agents.api.yaml.spec.SkillsSpec;
import org.apache.flink.agents.api.yaml.spec.ToolSpec;
import org.apache.flink.agents.api.yaml.spec.YamlAgentsDocument;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** YAML loader entry points and helpers. */
public final class YamlLoader {

    private YamlLoader() {}

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /** Default Java parameter types for an action method: (Event, RunnerContext). */
    private static final List<String> JAVA_ACTION_PARAMETER_TYPES =
            Collections.unmodifiableList(
                    List.of(
                            "org.apache.flink.agents.api.Event",
                            "org.apache.flink.agents.api.context.RunnerContext"));

    /**
     * Resolve a YAML function reference into a pure-data {@link Function}.
     *
     * <p>{@code function} must be {@code <module-or-class>:<qualname>}. For {@link Language#PYTHON}
     * the right side is a qualified Python name (which may contain dots for class methods). For
     * {@link Language#JAVA} the left side is a class FQN and the right side is a method name; the
     * caller must pass {@code parameterTypes}.
     */
    public static Function resolveFunction(
            String name, String function, Language language, List<String> parameterTypes) {
        if (function == null) {
            throw new IllegalArgumentException(
                    "Action/tool '"
                            + name
                            + "': 'function' is required and must be of the form "
                            + "'<module-or-class>:<qualname>'.");
        }
        int firstColon = function.indexOf(':');
        int lastColon = function.lastIndexOf(':');
        if (firstColon <= 0 || firstColon != lastColon || lastColon == function.length() - 1) {
            String kind = language == Language.JAVA ? "java" : "python";
            throw new IllegalArgumentException(
                    "Action/tool '"
                            + name
                            + "': "
                            + kind
                            + " function '"
                            + function
                            + "' must be of the form '<module-or-class>:<qualname>' (e.g. "
                            + "'pkg.tools:add', 'pkg.tools:MyTools.add', 'com.example.X:method').");
        }
        String left = function.substring(0, firstColon);
        String right = function.substring(firstColon + 1);
        if (language == Language.JAVA) {
            return new JavaFunction(
                    left, right, parameterTypes == null ? Collections.emptyList() : parameterTypes);
        }
        return new PythonFunction(left, right);
    }

    /**
     * Build a {@link ResourceDescriptor} from a parsed {@link DescriptorSpec}, resolving the alias
     * and applying cross-language wrapping when {@code type: python}.
     *
     * <p>For {@code type: python} the resulting descriptor's {@code clazz} is the Java-side wrapper
     * FQN (looked up in {@link Aliases#PYTHON_WRAPPER_CLAZZ}) and a {@code pythonClazz} init
     * argument carries the Python implementation FQN — matching what {@code PythonResourceProvider}
     * already expects.
     */
    public static ResourceDescriptor buildDescriptor(
            DescriptorSpec spec, ResourceType resourceType) {
        Language language = spec.getType() == null ? Language.PYTHON : spec.getType();
        Map<String, Object> extras = new LinkedHashMap<>(spec.getExtras());

        if (language == Language.PYTHON) {
            String wrapper = Aliases.PYTHON_WRAPPER_CLAZZ.get(resourceType);
            if (wrapper == null) {
                throw new IllegalArgumentException(
                        "Resource '"
                                + spec.getName()
                                + "': type='python' is not supported for "
                                + resourceType.getValue()
                                + " (no Java-side Python wrapper).");
            }
            String pythonFqn = Aliases.resolveClazz(spec.getClazz(), resourceType, Language.PYTHON);
            extras.put("pythonClazz", pythonFqn);
            return new ResourceDescriptor(wrapper, extras);
        }
        String javaFqn = Aliases.resolveClazz(spec.getClazz(), resourceType, Language.JAVA);
        return new ResourceDescriptor(javaFqn, extras);
    }

    /** Build a {@link FunctionTool} from a parsed {@link ToolSpec}. */
    public static FunctionTool buildTool(ToolSpec spec) {
        Language language = spec.getType() == null ? Language.PYTHON : spec.getType();
        if (language == Language.JAVA && spec.getParameterTypes() == null) {
            throw new IllegalArgumentException(
                    "Tool '"
                            + spec.getName()
                            + "': java tools must declare 'parameter_types' in YAML.");
        }
        Function fn =
                resolveFunction(
                        spec.getName(), spec.getFunction(), language, spec.getParameterTypes());
        return new FunctionTool(fn, spec.getInjectedArgs());
    }

    /** Build a {@link Prompt} from a parsed {@link PromptSpec}. */
    public static Prompt buildPrompt(PromptSpec spec) {
        if (spec.getText() != null) {
            return Prompt.fromText(spec.getText());
        }
        List<ChatMessage> messages =
                spec.getMessages().stream()
                        .map(m -> new ChatMessage(m.getRole(), m.getContent()))
                        .collect(Collectors.toList());
        return Prompt.fromMessages(messages);
    }

    /** Build a {@link Skills} resource from a parsed {@link SkillsSpec}. */
    public static Skills buildSkills(SkillsSpec spec) {
        List<SkillSourceSpec> sources = new ArrayList<>();
        for (String p : spec.getPaths()) {
            sources.add(new SkillSourceSpec("local", Map.of("path", p)));
        }
        for (String u : spec.getUrls()) {
            sources.add(new SkillSourceSpec("url", Map.of("url", u)));
        }
        for (String r : spec.getClasspath()) {
            sources.add(new SkillSourceSpec("classpath", Map.of("resource", r)));
        }
        for (PackageSkillSpec pkg : spec.getPackageEntries()) {
            sources.add(
                    new SkillSourceSpec(
                            "package",
                            Map.of(
                                    "package",
                                    pkg.getPackageName(),
                                    "resource",
                                    pkg.getResource())));
        }
        return new Skills(sources);
    }

    /**
     * Output of {@link #buildAgents(Path)}. Holds in-file state without touching any environment.
     */
    public static final class LoadedFile {
        private final Map<String, Agent> agents;
        private final Map<ResourceType, Map<String, Object>> sharedResources;
        private final Map<String, ActionSpec> sharedActions;
        private final Map<String, AgentSpec> agentSpecs;

        LoadedFile(
                Map<String, Agent> agents,
                Map<ResourceType, Map<String, Object>> sharedResources,
                Map<String, ActionSpec> sharedActions,
                Map<String, AgentSpec> agentSpecs) {
            this.agents = agents;
            this.sharedResources = sharedResources;
            this.sharedActions = sharedActions;
            this.agentSpecs = agentSpecs;
        }

        public Map<String, Agent> getAgents() {
            return agents;
        }

        public Map<ResourceType, Map<String, Object>> getSharedResources() {
            return sharedResources;
        }

        public Map<String, ActionSpec> getSharedActions() {
            return sharedActions;
        }

        /** Package-private — used by {@code loadYaml(...)} when it lands. */
        Map<String, AgentSpec> getAgentSpecs() {
            return agentSpecs;
        }
    }

    /** Parse one YAML file and build the agents it declares. */
    public static LoadedFile buildAgents(Path path) {
        YamlAgentsDocument doc;
        try {
            doc = YAML_MAPPER.readValue(Files.readString(path), YamlAgentsDocument.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML " + path, e);
        }
        if (doc == null) {
            throw new IllegalArgumentException("YAML file " + path + " is empty");
        }

        Map<String, Agent> agents = new LinkedHashMap<>();
        Map<String, AgentSpec> agentSpecs = new LinkedHashMap<>();
        for (AgentSpec spec : doc.getAgents()) {
            if (agents.containsKey(spec.getName())) {
                throw new IllegalArgumentException(
                        "Duplicate agent name '" + spec.getName() + "' in " + path);
            }
            agentSpecs.put(spec.getName(), spec);
            agents.put(spec.getName(), buildAgent(spec));
        }

        Map<ResourceType, Map<String, Object>> sharedResources = new EnumMap<>(ResourceType.class);
        for (ResourceType t : ResourceType.values()) {
            sharedResources.put(t, new LinkedHashMap<>());
        }

        addSharedDescriptors(
                sharedResources,
                ResourceType.CHAT_MODEL_CONNECTION,
                doc.getChatModelConnections(),
                path);
        addSharedDescriptors(
                sharedResources, ResourceType.CHAT_MODEL, doc.getChatModelSetups(), path);
        addSharedDescriptors(
                sharedResources,
                ResourceType.EMBEDDING_MODEL_CONNECTION,
                doc.getEmbeddingModelConnections(),
                path);
        addSharedDescriptors(
                sharedResources, ResourceType.EMBEDDING_MODEL, doc.getEmbeddingModelSetups(), path);
        addSharedDescriptors(
                sharedResources, ResourceType.VECTOR_STORE, doc.getVectorStores(), path);
        addSharedDescriptors(sharedResources, ResourceType.MCP_SERVER, doc.getMcpServers(), path);

        for (ToolSpec t : doc.getTools()) {
            if (sharedResources.get(ResourceType.TOOL).put(t.getName(), buildTool(t)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate shared tool name '" + t.getName() + "' in " + path);
            }
        }
        for (PromptSpec p : doc.getPrompts()) {
            if (sharedResources.get(ResourceType.PROMPT).put(p.getName(), buildPrompt(p)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate shared prompt name '" + p.getName() + "' in " + path);
            }
        }
        for (SkillsSpec s : doc.getSkills()) {
            if (sharedResources.get(ResourceType.SKILLS).put(s.getName(), buildSkills(s)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate shared skills name '" + s.getName() + "' in " + path);
            }
        }

        Map<String, ActionSpec> sharedActions = new LinkedHashMap<>();
        for (ActionSpec a : doc.getActions()) {
            if (sharedActions.containsKey(a.getName())) {
                throw new IllegalArgumentException(
                        "Duplicate shared action name '" + a.getName() + "' in " + path);
            }
            sharedActions.put(a.getName(), a);
        }

        return new LoadedFile(agents, sharedResources, sharedActions, agentSpecs);
    }

    /** Load one YAML file and register its agents and shared resources on the environment. */
    public static void loadYaml(AgentsExecutionEnvironment env, Path path) {
        loadYaml(env, List.of(path));
    }

    /**
     * Load multiple YAML files. Multiple calls accumulate. Duplicate names — both within a single
     * file (caught by {@link #buildAgents(Path)}) and across the current environment — raise {@link
     * IllegalArgumentException}.
     */
    public static void loadYaml(AgentsExecutionEnvironment env, List<Path> paths) {
        for (Path path : paths) {
            LoadedFile loaded = buildAgents(path);

            for (Map.Entry<String, Agent> entry : loaded.getAgents().entrySet()) {
                AgentSpec spec = loaded.getAgentSpecs().get(entry.getKey());
                List<ActionSpec> orderedActions = new ArrayList<>();
                for (AgentActionRef ref : spec.getActions()) {
                    ActionSpec actionSpec = ref.getSpec();
                    if (ref.isReference()) {
                        actionSpec = loaded.getSharedActions().get(ref.getReference());
                        if (actionSpec == null) {
                            throw new IllegalArgumentException(
                                    "Agent '"
                                            + entry.getKey()
                                            + "' references shared action '"
                                            + ref.getReference()
                                            + "' in "
                                            + path
                                            + ", but no shared action with that name is defined at"
                                            + " the file level.");
                        }
                    }
                    orderedActions.add(actionSpec);
                }

                entry.getValue().getActions().clear();
                for (ActionSpec actionSpec : orderedActions) {
                    addActionToAgent(entry.getValue(), actionSpec);
                }
            }

            // Cross-environment agent name uniqueness check.
            for (String name : loaded.getAgents().keySet()) {
                if (env.getAgents().containsKey(name)) {
                    throw new IllegalArgumentException(
                            "Duplicate agent name '" + name + "' (loading " + path + ")");
                }
            }

            // Commit shared resources — env.addResource enforces dedup with its own message.
            for (Map.Entry<ResourceType, Map<String, Object>> e :
                    loaded.getSharedResources().entrySet()) {
                for (Map.Entry<String, Object> r : e.getValue().entrySet()) {
                    env.addResource(r.getKey(), e.getKey(), r.getValue());
                }
            }
            env.getAgents().putAll(loaded.getAgents());
        }
    }

    private static void addSharedDescriptors(
            Map<ResourceType, Map<String, Object>> sharedResources,
            ResourceType type,
            List<DescriptorSpec> specs,
            Path path) {
        Map<String, Object> bucket = sharedResources.get(type);
        for (DescriptorSpec s : specs) {
            if (bucket.put(s.getName(), buildDescriptor(s, type)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate shared resource name '" + s.getName() + "' in " + path);
            }
        }
    }

    private static Agent buildAgent(AgentSpec spec) {
        Agent agent = new Agent();
        addAgentDescriptors(
                agent, ResourceType.CHAT_MODEL_CONNECTION, spec.getChatModelConnections());
        addAgentDescriptors(agent, ResourceType.CHAT_MODEL, spec.getChatModelSetups());
        addAgentDescriptors(
                agent,
                ResourceType.EMBEDDING_MODEL_CONNECTION,
                spec.getEmbeddingModelConnections());
        addAgentDescriptors(agent, ResourceType.EMBEDDING_MODEL, spec.getEmbeddingModelSetups());
        addAgentDescriptors(agent, ResourceType.VECTOR_STORE, spec.getVectorStores());
        addAgentDescriptors(agent, ResourceType.MCP_SERVER, spec.getMcpServers());

        for (ToolSpec t : spec.getTools()) {
            agent.addResource(t.getName(), ResourceType.TOOL, buildTool(t));
        }
        for (PromptSpec p : spec.getPrompts()) {
            agent.addResource(p.getName(), ResourceType.PROMPT, buildPrompt(p));
        }
        for (SkillsSpec s : spec.getSkills()) {
            agent.addResource(s.getName(), ResourceType.SKILLS, buildSkills(s));
        }
        for (AgentActionRef ref : spec.getActions()) {
            if (ref.isReference()) {
                continue; // resolved later in loadYaml
            }
            addActionToAgent(agent, ref.getSpec());
        }
        return agent;
    }

    private static void addAgentDescriptors(
            Agent agent, ResourceType type, List<DescriptorSpec> specs) {
        for (DescriptorSpec s : specs) {
            agent.addResource(s.getName(), type, buildDescriptor(s, type));
        }
    }

    /**
     * Resolve an ActionSpec's function reference. Java actions always use the standard {@code
     * (Event, RunnerContext)} parameter types — ActionSpec has no {@code parameter_types} field
     * because action method signatures are fixed by the framework.
     */
    static Function resolveActionFunction(ActionSpec action) {
        Language language = action.getType() == null ? Language.PYTHON : action.getType();
        List<String> paramTypes = language == Language.JAVA ? JAVA_ACTION_PARAMETER_TYPES : null;
        return resolveFunction(action.getName(), action.getFunction(), language, paramTypes);
    }

    /**
     * Registers an action, replacing trigger conditions that exactly match built-in event aliases
     * while leaving expression conditions unchanged.
     */
    static void addActionToAgent(Agent agent, ActionSpec action) {
        Function fn = resolveActionFunction(action);
        String[] triggerConditions =
                action.getTriggerConditions().stream()
                        .map(Aliases::resolveEventType)
                        .toArray(String[]::new);
        Map<String, Object> config = action.getConfig();
        agent.addAction(action.getName(), triggerConditions, fn, config);
    }
}
