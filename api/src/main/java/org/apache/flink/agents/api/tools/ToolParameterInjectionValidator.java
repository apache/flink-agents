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

package org.apache.flink.agents.api.tools;

import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.function.Function;
import org.apache.flink.agents.api.function.JavaFunction;
import org.apache.flink.agents.api.function.JavaFunctionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Validation helpers for declarative tool parameter injection. */
public final class ToolParameterInjectionValidator {

    private ToolParameterInjectionValidator() {}

    public static void validate(
            Function function, Map<String, ToolParameterInjection> injectedArgs, String toolName) {
        if (injectedArgs.isEmpty() || !(function instanceof JavaFunction)) {
            return;
        }
        JavaFunction javaFunction = (JavaFunction) function;
        String displayName = toolName == null ? javaFunction.getMethodName() : toolName;
        try {
            Method method = JavaFunctionUtils.resolveMethod(javaFunction);
            Set<String> parameterNames = new HashSet<>();
            boolean allParameterNamesReliable = true;
            for (Parameter parameter : method.getParameters()) {
                if (parameter.isAnnotationPresent(ToolParam.class)) {
                    ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
                    if (!toolParam.name().isEmpty()) {
                        parameterNames.add(toolParam.name());
                        continue;
                    }
                }
                if (parameter.isNamePresent()) {
                    parameterNames.add(parameter.getName());
                } else {
                    allParameterNamesReliable = false;
                }
            }
            if (!allParameterNamesReliable) {
                return;
            }
            Set<String> unknown = new HashSet<>(injectedArgs.keySet());
            unknown.removeAll(parameterNames);
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(
                        "Tool '"
                                + displayName
                                + "': injected_args contain unknown parameter(s): "
                                + unknown);
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "Tool '" + displayName + "': failed to validate injected_args.", e);
        }
    }
}
