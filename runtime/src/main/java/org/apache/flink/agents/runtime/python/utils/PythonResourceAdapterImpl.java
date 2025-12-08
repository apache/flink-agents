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

import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.tools.Tool;
import pemja.core.PythonInterpreter;
import pemja.core.object.PyObject;

import java.util.Map;
import java.util.function.BiFunction;

public class PythonResourceAdapterImpl implements PythonResourceAdapter {

    static final String PYTHON_IMPORTS = "from flink_agents.runtime import python_java_utils";

    static final String GET_RESOURCE_KEY = "get_resource";

    static final String PYTHON_MODULE_PREFIX = "python_java_utils.";

    static final String GET_RESOURCE_FUNCTION = PYTHON_MODULE_PREFIX + "get_resource_function";

    static final String CALL_METHOD = PYTHON_MODULE_PREFIX + "call_method";

    static final String CREATE_RESOURCE = PYTHON_MODULE_PREFIX + "create_resource";

    static final String FROM_JAVA_TOOL = PYTHON_MODULE_PREFIX + "from_java_tool";

    static final String FROM_JAVA_PROMPT = PYTHON_MODULE_PREFIX + "from_java_prompt";

    private final BiFunction<String, ResourceType, Resource> getResource;
    private final PythonInterpreter interpreter;
    private Object pythonGetResourceFunction;

    public PythonResourceAdapterImpl(
            BiFunction<String, ResourceType, Resource> getResource, PythonInterpreter interpreter) {
        this.getResource = getResource;
        this.interpreter = interpreter;
    }

    public void open() {
        interpreter.exec(PYTHON_IMPORTS);
        pythonGetResourceFunction = interpreter.invoke(GET_RESOURCE_FUNCTION, this);
    }

    public Object getResource(String resourceName, String resourceType) {
        Resource resource =
                this.getResource.apply(resourceName, ResourceType.fromValue(resourceType));
        if (resource instanceof PythonResourceWrapper) {
            PythonResourceWrapper pythonResource = (PythonResourceWrapper) resource;
            return pythonResource.getPythonResource();
        }
        if (resource instanceof Tool) {
            return convertToPythonTool((Tool) resource);
        }
        if (resource instanceof Prompt) {
            return convertToPythonPrompt((Prompt) resource);
        }
        return resource;
    }

    @Override
    public PyObject initPythonResource(String module, String clazz, Map<String, Object> kwargs) {
        kwargs.put(GET_RESOURCE_KEY, pythonGetResourceFunction);
        return (PyObject) interpreter.invoke(CREATE_RESOURCE, module, clazz, kwargs);
    }

    private Object convertToPythonTool(Tool tool) {
        return interpreter.invoke(FROM_JAVA_TOOL, tool);
    }

    private Object convertToPythonPrompt(Prompt prompt) {
        return interpreter.invoke(FROM_JAVA_PROMPT, prompt);
    }

    @Override
    public Object callMethod(Object obj, String methodName, Map<String, Object> kwargs) {
        return interpreter.invoke(CALL_METHOD, obj, methodName, kwargs);
    }

    @Override
    public Object invoke(String name, Object... args) {
        return interpreter.invoke(name, args);
    }
}
