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

package org.apache.flink.agents.api.resource.python;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;
import org.apache.flink.agents.api.vectorstores.VectorStoreQueryResult;
import pemja.core.object.PyObject;

import java.util.List;
import java.util.Map;

/**
 * Adapter interface for managing Python resources and facilitating Java-Python interoperability.
 * This interface provides methods to interact with Python objects, invoke Python methods, and
 * handle data conversion between Java and Python environments.
 */
public interface PythonResourceAdapter {

    /**
     * Retrieves a Python resource by name and type.
     *
     * @param resourceName the name of the resource to retrieve
     * @param resourceType the type of the resource
     * @return the retrieved resource object
     */
    Object getResource(String resourceName, String resourceType);

    /**
     * Initializes a Python resource instance from the specified module and class.
     *
     * @param module the Python module containing the target class
     * @param clazz the Python class name to instantiate
     * @param kwargs keyword arguments to pass to the Python class constructor
     * @return a PyObject representing the initialized Python resource
     */
    PyObject initPythonResource(String module, String clazz, Map<String, Object> kwargs);

    /**
     * Converts a Java ChatMessage object to its Python equivalent.
     *
     * @param message the Java ChatMessage to convert
     * @return the Python representation of the chat message
     */
    Object toPythonChatMessage(ChatMessage message);

    /**
     * Converts a Python chat message object back to a Java ChatMessage.
     *
     * @param pythonChatMessage the Python chat message object to convert
     * @return the Java ChatMessage representation
     */
    ChatMessage fromPythonChatMessage(Object pythonChatMessage);

    /**
     * Converts a list of java document object to its Python equivalent.
     *
     * @param documents the list of java document to convert
     * @return the Python representation of the documents
     */
    Object toPythonDocuments(List<Document> documents);

    /**
     * Converts List of Python Document object back to a list of Java Document.
     *
     * @param pythonDocuments the List of Python Document object to convert
     * @return the list of Java Document representation
     */
    List<Document> fromPythonDocuments(List<PyObject> pythonDocuments);

    /**
     * Converts a Java VectorStoreQuery object to its Python equivalent.
     *
     * @param query the Java VectorStoreQuery to convert
     * @return the Python representation of the vector store query
     */
    Object toPythonVectorStoreQuery(VectorStoreQuery query);

    /**
     * Converts a Python VectorStoreQuery object back to a Java VectorStoreQuery.
     *
     * @param pythonVectorStoreQueryResult the Python VectorStoreQuery object to convert
     * @return the Java VectorStoreQuery representation
     */
    VectorStoreQueryResult fromPythonVectorStoreQueryResult(PyObject pythonVectorStoreQueryResult);

    /**
     * Converts a Java Tool object to its Python equivalent.
     *
     * @param tool the Java Tool to convert
     * @return the Python representation of the tool
     */
    Object convertToPythonTool(Tool tool);

    /**
     * Invokes a method on a Python object with the specified parameters.
     *
     * @param obj the Python object on which to call the method
     * @param methodName the name of the method to invoke
     * @param kwargs keyword arguments to pass to the method
     * @return the result of the method invocation
     */
    Object callMethod(Object obj, String methodName, Map<String, Object> kwargs);

    /**
     * Binds a Java metric group to a Python resource.
     *
     * @param pythonResource the Python resource object
     * @param metricGroup the Java metric group to expose through Python's metric group API
     */
    default void setMetricGroup(Object pythonResource, FlinkAgentsMetricGroup metricGroup) {}

    /**
     * Invokes a method with the specified name and arguments.
     *
     * @param name the name of the method to invoke
     * @param args the arguments to pass to the method
     * @return the result of the method invocation
     */
    Object invoke(String name, Object... args);

    /**
     * Look up tool metadata for a Python function across the JVM&rarr;Python bridge.
     *
     * <p>The Java side asks the Python side to introspect a callable identified by {@code module} +
     * {@code qualName}, and returns a flat {@code Map<String, String>} with keys {@code "name"},
     * {@code "description"}, and {@code "inputSchema"} (a JSON schema string compatible with {@code
     * ToolMetadata.inputSchema}).
     *
     * <p>The return shape is intentionally flat — pemja can SIGSEGV when returning arbitrary Python
     * objects to Java on non-main-interpreter threads.
     *
     * @param module the Python module containing the callable
     * @param qualName the qualified name of the callable inside the module (e.g. {@code "fn"} or
     *     {@code "MyClass.method"})
     * @return flat map with keys "name", "description", "inputSchema"
     */
    Map<String, String> getPythonToolMetadata(String module, String qualName);

    /**
     * Invoke a Python callable as a tool, passing keyword arguments. Used when a Java chat model's
     * tool list contains a {@code plan.FunctionTool} whose function descriptor is a {@code
     * PythonFunction}: instead of routing the invocation through Java reflection, dispatch it
     * across the bridge so the underlying Python function runs in the Pemja interpreter.
     *
     * @param module the Python module containing the callable
     * @param qualName the qualified name of the callable inside the module
     * @param kwargs keyword arguments to pass to the callable; LLM tool calls always arrive as
     *     keyword arguments
     * @return the raw return value from the Python callable
     */
    Object invokePythonTool(String module, String qualName, Map<String, Object> kwargs);
}
