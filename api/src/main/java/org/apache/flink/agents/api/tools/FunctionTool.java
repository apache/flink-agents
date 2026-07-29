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

import org.apache.flink.agents.api.function.Function;
import org.apache.flink.agents.api.function.JavaFunction;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-data tool descriptor: carries an {@link Function} reference. Used at agent-construction
 * time; compiled to the plan-layer executable {@code plan.tools.FunctionTool} when the agent
 * becomes an {@code AgentPlan}.
 */
public class FunctionTool extends SerializableResource {

    private final Function func;
    private final Map<String, ToolParameterInjection> injectedArgs;

    public FunctionTool(Function func) {
        this(func, Map.of());
    }

    public FunctionTool(Function func, Map<String, ToolParameterInjection> injectedArgs) {
        this.func = Objects.requireNonNull(func, "func");
        this.injectedArgs = normalizeInjectedArgs(injectedArgs);
    }

    /** Convenience factory: derive a {@link JavaFunction} from a reflected method. */
    public static FunctionTool fromMethod(Method method) {
        return new FunctionTool(JavaFunction.fromMethod(method));
    }

    public Function getFunc() {
        return func;
    }

    public Map<String, ToolParameterInjection> getInjectedArgs() {
        return injectedArgs;
    }

    private static Map<String, ToolParameterInjection> normalizeInjectedArgs(
            Map<String, ToolParameterInjection> injectedArgs) {
        Map<String, ToolParameterInjection> result = new LinkedHashMap<>();
        if (injectedArgs != null) {
            injectedArgs.forEach((name, spec) -> result.put(name, spec.withDefaultKey(name)));
        }
        return Map.copyOf(result);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.TOOL;
    }
}
