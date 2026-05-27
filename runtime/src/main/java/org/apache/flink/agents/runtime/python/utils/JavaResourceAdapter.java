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

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.apache.flink.agents.plan.tools.ToolMetadataFactory;
import pemja.core.PythonInterpreter;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Adapter for managing Java resources and facilitating Python-Java interoperability. */
public class JavaResourceAdapter {
    private final ResourceContext resourceContext;

    private final transient PythonInterpreter interpreter;

    /**
     * Class loader used to resolve Java tool methods declared by name. Captured at construction
     * (the operator passes its {@code RuntimeContext.getUserCodeClassLoader()}) because pemja
     * worker threads inherit the JVM system loader as their context loader and would not see
     * user-supplied jars added via {@code env.add_jars(...)}.
     */
    private final transient ClassLoader userCodeClassLoader;

    public JavaResourceAdapter(
            ResourceContext resourceContext,
            PythonInterpreter interpreter,
            ClassLoader userCodeClassLoader) {
        this.resourceContext = resourceContext;
        this.interpreter = interpreter;
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
     * @param pythonChatMessage the Python chat message
     * @return the Java chat message
     */
    public ChatMessage fromPythonChatMessage(Object pythonChatMessage) {
        // TODO: Delete this method after the pemja findClass method is fixed.
        ChatMessage chatMessage = new ChatMessage();
        if (interpreter == null) {
            throw new IllegalStateException("Python interpreter is not set.");
        }
        String roleValue =
                (String)
                        interpreter.invoke(
                                "python_java_utils.update_java_chat_message",
                                pythonChatMessage,
                                chatMessage);
        chatMessage.setRole(MessageRole.fromValue(roleValue));
        return chatMessage;
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
     * plan.FunctionTool} backed by a {@code JavaFunction} first materialises its metadata.
     * Delegates to {@link ToolMetadataFactory#fromStaticMethod(Method)} once the {@code Method} is
     * resolved, then flattens the resulting {@link ToolMetadata} into a {@code Map<String, String>}
     * before returning.
     *
     * <p>The flattening is required because pemja can crash with a SIGSEGV inside {@code
     * JcpPyJObject_New} when Java returns an arbitrary Java object to a Python call that originated
     * on a non-main interpreter thread (e.g. a Flink mailbox worker that resolves a tool's
     * metadata). Returning only String fields — which pemja maps natively to {@code str} —
     * sidesteps the reverse Java→Python object wrap entirely. The Python side rebuilds {@link
     * ToolMetadata} from the flat map.
     */
    public Map<String, String> getJavaToolMetadata(
            String className, String methodName, List<String> parameterTypes) throws Exception {
        Method method = resolveMethod(className, methodName, parameterTypes);
        ToolMetadata metadata = ToolMetadataFactory.fromStaticMethod(method);
        Map<String, String> result = new HashMap<>();
        result.put("name", metadata.getName());
        result.put("description", metadata.getDescription());
        result.put("inputSchema", metadata.getInputSchema());
        return result;
    }

    /**
     * Invoke a Java static tool method with keyword arguments coming from a Python tool call.
     *
     * <p>Delegates to {@link FunctionTool#call(ToolParameters)} so the Python-driven tool-call path
     * shares every detail of argument resolution with the Java agent path — {@link
     * org.apache.flink.agents.api.annotation.ToolParam} name override, {@link ToolParameters}
     * numeric coercion (covers the LLM-emitted JSON Number → Java box type mismatch that reflective
     * {@code Method.invoke} otherwise rejects), required-parameter checking, and {@link
     * ToolResponse} success / error semantics. The success result is unwrapped for the Python
     * caller; an unsuccessful response is re-thrown as a {@link RuntimeException}.
     */
    public Object invokeJavaTool(
            String className,
            String methodName,
            List<String> parameterTypes,
            Map<String, Object> arguments)
            throws Exception {
        Method method = resolveMethod(className, methodName, parameterTypes);
        FunctionTool tool = FunctionTool.fromStaticMethod(method);
        ToolResponse response =
                tool.call(new ToolParameters(arguments == null ? new HashMap<>() : arguments));
        if (!response.isSuccess()) {
            throw new RuntimeException(response.getError());
        }
        return response.getResult();
    }

    /** Invoke a Java static action method with positional arguments from Python. */
    public Object invokeJavaAction(
            String className,
            String methodName,
            List<String> parameterTypes,
            List<Object> arguments)
            throws Exception {
        Method method = resolveMethod(className, methodName, parameterTypes);
        Object[] args = arguments == null ? new Object[0] : arguments.toArray();
        return method.invoke(null, args);
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
