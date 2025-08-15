/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.agents.plan.resourceprovider;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.apache.flink.agents.plan.tools.ToolMetadataFactory;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Resource provider for tools created from static Java methods annotated with @Tool. Stores the
 * reflective identity to reconstruct a FunctionTool at runtime.
 */
public class ToolResourceProvider extends ResourceProvider {

    private final String declaringClass;
    private final String methodName;
    private final String[] parameterTypeNames;

    public ToolResourceProvider(
            String name, String declaringClass, String methodName, String[] parameterTypeNames) {
        super(name, ResourceType.TOOL);
        this.declaringClass = declaringClass;
        this.methodName = methodName;
        this.parameterTypeNames =
                parameterTypeNames != null ? parameterTypeNames.clone() : new String[0];
    }

    public String getDeclaringClass() {
        return declaringClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public String[] getParameterTypeNames() {
        return parameterTypeNames.clone();
    }

    @Override
    public Resource provide(Callable<Resource> getResource) throws Exception {
        Class<?> clazz = Class.forName(declaringClass);
        Class<?>[] paramTypes = new Class<?>[parameterTypeNames.length];
        for (int i = 0; i < parameterTypeNames.length; i++) {
            paramTypes[i] = Class.forName(parameterTypeNames[i]);
        }
        Method method = clazz.getMethod(methodName, paramTypes);

        // Build metadata and function wrapper
        ToolMetadata metadata = ToolMetadataFactory.fromMethod(method, null);
        JavaFunction javaFunction = new JavaFunction(clazz, methodName, paramTypes);
        return new FunctionTool(metadata, javaFunction);
    }

    @Override
    public String toString() {
        return "ToolResourceProvider{"
                + "name='"
                + getName()
                + '\''
                + ", declaringClass='"
                + declaringClass
                + '\''
                + ", methodName='"
                + methodName
                + '\''
                + ", parameterTypeNames="
                + Arrays.toString(parameterTypeNames)
                + '}';
    }
}
