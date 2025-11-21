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

import pemja.core.object.PyObject;

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
     * Invokes a method on a Python object with the specified parameters.
     *
     * @param obj the Python object on which to call the method
     * @param methodName the name of the method to invoke
     * @param kwargs keyword arguments to pass to the method
     * @return the result of the method invocation
     */
    Object callMethod(Object obj, String methodName, Map<String, Object> kwargs);

    /**
     * Invokes a method with the specified name and arguments.
     *
     * @param name the name of the method to invoke
     * @param args the arguments to pass to the method
     * @return the result of the method invocation
     */
    Object invoke(String name, Object... args);
}
