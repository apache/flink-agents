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
package org.apache.flink.agents.runtime.python.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.plan.tools.FunctionTool;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Adapter for managing Java resources and facilitating Python-Java interoperability. */
public class JavaResourceAdapter {
    private static final String TOOL_RESULT_MARKER = "__flink_agents_tool_result__";

    private final ResourceContext resourceContext;

    /**
     * Class loader used to resolve Java tool methods declared by name. Captured at construction
     * (the operator passes its {@code RuntimeContext.getUserCodeClassLoader()}) because pemja
     * worker threads inherit the JVM system loader as their context loader and would not see
     * user-supplied jars added via {@code env.add_jars(...)}.
     */
    private final transient ClassLoader userCodeClassLoader;

    private final Map<String, FunctionTool> functionTools =
            new java.util.concurrent.ConcurrentHashMap<>();

    public JavaResourceAdapter(ResourceContext resourceContext, ClassLoader userCodeClassLoader) {
        this.resourceContext = resourceContext;
        this.userCodeClassLoader = userCodeClassLoader;
    }

    /**
     * Retrieves a Java resource by name and type value. This method is intended for use by the
     * Python interpreter.
     *
     * @param name the name of the resource to retrieve
     * @param typeValue the type value of the resource
     * @return the resource
     * @throws Exception if the resource cannot be retrieved
     */
    public Resource getResource(String name, String typeValue) throws Exception {
        return resourceContext.getResource(name, ResourceType.fromValue(typeValue));
    }

    /**
     * Generate the available skills prompt for the given skill names. Used by the Python {@code
     * JavaResourceContextWrapper} when a Python chat model running in a Java agent needs the skill
     * discovery prompt.
     */
    public String generateAvailableSkillsPrompt(List<String> skillNames) throws Exception {
        return resourceContext.generateAvailableSkillsPrompt(skillNames);
    }

    /** Return absolute directory paths for the given skill names. */
    public List<String> getSkillDirs(List<String> skillNames) throws Exception {
        return resourceContext.getSkillDirs(skillNames);
    }

    /**
     * Convert a Python chat message to a Java chat message. This method is intended for use by the
     * Python interpreter.
     *
     * <p>The Python caller extracts the message fields before crossing into Java. Keeping this
     * method Java-only avoids an unnecessary Python→Java→Python callback while constructing the
     * Java value.
     *
     * @param roleValue the Python message role value
     * @param blocks the content blocks as plain maps in the wire shape (see {@link
     *     ChatMessage#setBlocksFromMaps(List)})
     * @param toolCalls the normalized tool calls
     * @param extraArgs additional message arguments
     * @return the Java chat message
     */
    public ChatMessage fromPythonChatMessage(
            String roleValue,
            List<Map<String, Object>> blocks,
            List<Map<String, Object>> toolCalls,
            Map<String, Object> extraArgs) {
        // TODO: Delete this method after the pemja findClass method is fixed.
        ChatMessage message =
                new ChatMessage(MessageRole.fromValue(roleValue), List.of(), toolCalls, extraArgs);
        message.setBlocksFromMaps(blocks);
        return message;
    }

    /**
     * Build a Java {@link Document} from already-extracted Python fields. The earlier overload
     * accepted a {@code PyObject} and called {@code getAttr} from Java, which crashes the JVM when
     * Python invokes the bridge from a thread mem0 (or any other consumer) span up itself: the
     * reverse Java→Python attribute lookup is unsafe outside Pemja's main interpreter thread state.
     * Pulling the fields out on the Python side (where the GIL is already held by the calling
     * thread) sidesteps that altogether.
     */
    public Document fromPythonDocument(
            String content,
            Map<String, Object> metadata,
            String id,
            float[] embedding,
            Float score) {
        return new Document(content, metadata, id, embedding, score);
    }

    /**
     * Resolve the metadata for a Java static tool method declared by fully-qualified class name,
     * method name and parameter type names.
     *
     * <p>Invoked from the Python side via the {@code _j_resource_adapter} bridge when a {@code
     * plan.FunctionTool} backed by a {@code JavaFunction} first materialises its metadata. Reuses
     * the compiled {@link FunctionTool} once the {@code Method} is resolved, then flattens its
     * {@link ToolMetadata} plus any method-declared injected arguments into a {@code Map<String,
     * String>} before returning.
     *
     * <p>The flattening is required because pemja can crash with a SIGSEGV inside {@code
     * JcpPyJObject_New} when Java returns an arbitrary Java object to a Python call that originated
     * on a non-main interpreter thread (e.g. a Flink mailbox worker that resolves a tool's
     * metadata). Returning only String fields — which pemja maps natively to {@code str} —
     * sidesteps the reverse Java→Python object wrap entirely. The Python side rebuilds {@link
     * ToolMetadata} and injected-argument declarations from the flat map.
     */
    public Map<String, String> getJavaToolMetadata(
            String className, String methodName, List<String> parameterTypes) throws Exception {
        return getJavaToolMetadata(className, methodName, parameterTypes, List.of());
    }

    public Map<String, String> getJavaToolMetadata(
            String className,
            String methodName,
            List<String> parameterTypes,
            List<String> injectedArgs)
            throws Exception {
        Method method = resolveMethod(className, methodName, parameterTypes);
        ToolMetadata metadata = functionTool(method, injectedArgs).getMetadata();
        Map<String, ToolParameterInjection> annotatedInjectedArgs =
                FunctionTool.getInjectedArgs(method);
        Map<String, String> result = new HashMap<>();
        result.put("name", metadata.getName());
        result.put("description", metadata.getDescription());
        result.put("inputSchema", metadata.getInputSchema());
        result.put("injectedArgs", new ObjectMapper().writeValueAsString(annotatedInjectedArgs));
        return result;
    }

    /**
     * Invoke a Java static tool method with keyword arguments coming from a Python tool call.
     *
     * <p>Arguments include the framework-resolved injection values. The owning Java function tool
     * validates and binds arguments before invocation, using the complete function signature. The
     * response uses an internal envelope to preserve explicit tool failures across the bridge.
     */
    public Map<String, Object> invokeJavaTool(
            String className,
            String methodName,
            List<String> parameterTypes,
            Map<String, Object> arguments)
            throws Exception {
        Method method = resolveMethod(className, methodName, parameterTypes);
        ToolResponse response = functionTool(method, List.of()).call(new ToolParameters(arguments));
        Map<String, Object> result = new HashMap<>();
        result.put(TOOL_RESULT_MARKER, "response");
        result.put("result", response.getResult());
        result.put("success", response.isSuccess());
        result.put("error", response.getError());
        result.put("execution_time_ms", response.getExecutionTimeMs());
        result.put("tool_name", response.getToolName());
        return result;
    }

    /** Invoke a Java static action method with positional arguments from Python. */
    public Object invokeJavaAction(
            String className,
            String methodName,
            List<String> parameterTypes,
            List<Object> arguments)
            throws Exception {
        Method method = resolveMethod(className, methodName, parameterTypes);
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    "JavaAction target must be a static method. Got instance method: "
                            + className
                            + "#"
                            + methodName);
        }
        Object[] args = arguments == null ? new Object[0] : arguments.toArray();
        return method.invoke(null, args);
    }

    private FunctionTool functionTool(Method method, List<String> injectedNames) {
        List<String> sortedNames = new java.util.ArrayList<>(injectedNames);
        java.util.Collections.sort(sortedNames);
        String key = method.toGenericString() + sortedNames;
        return functionTools.computeIfAbsent(
                key,
                ignored -> {
                    Map<String, ToolParameterInjection> declarations =
                            new HashMap<>(FunctionTool.getInjectedArgs(method));
                    for (String name : injectedNames) {
                        declarations.putIfAbsent(
                                name, ToolParameterInjection.fromSensoryMemory(name));
                    }
                    try {
                        return new FunctionTool(
                                null,
                                new org.apache.flink.agents.plan.JavaFunction(
                                        method.getDeclaringClass(),
                                        method.getName(),
                                        method.getParameterTypes()),
                                declarations);
                    } catch (Exception error) {
                        throw new IllegalArgumentException("Cannot resolve function tool", error);
                    }
                });
    }

    private Method resolveMethod(String className, String methodName, List<String> parameterTypes)
            throws ClassNotFoundException, NoSuchMethodException {
        ClassLoader classLoader =
                userCodeClassLoader != null
                        ? userCodeClassLoader
                        : Thread.currentThread().getContextClassLoader();
        Class<?> clazz = Class.forName(className, true, classLoader);
        Class<?>[] paramClasses = new Class<?>[parameterTypes.size()];
        for (int i = 0; i < parameterTypes.size(); i++) {
            paramClasses[i] = resolveType(parameterTypes.get(i), classLoader);
        }
        return clazz.getMethod(methodName, paramClasses);
    }

    private static Class<?> resolveType(String typeName, ClassLoader classLoader)
            throws ClassNotFoundException {
        switch (typeName) {
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
                return Class.forName(typeName, true, classLoader);
        }
    }
}
