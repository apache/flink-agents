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
package org.apache.flink.agents.api.function;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure-data descriptor for a Java method, identified by declaring class FQN, method name, and
 * parameter types as strings.
 *
 * <p>Parameter types are strings — JVM primitive names ({@code int}, {@code long}, {@code boolean},
 * …) or fully-qualified reference type names ({@code java.lang.String}, {@code java.util.List}). No
 * generic parameters. The wire form keeps the descriptor pure data; class resolution is deferred to
 * the plan-layer twin.
 */
public final class JavaFunction implements Function, Serializable {

    private static final String FIELD_QUAL_NAME = "qualName";
    private static final String FIELD_METHOD_NAME = "methodName";
    private static final String FIELD_PARAMETER_TYPES = "parameterTypes";

    @JsonProperty(FIELD_QUAL_NAME)
    private final String qualName;

    @JsonProperty(FIELD_METHOD_NAME)
    private final String methodName;

    @JsonProperty(FIELD_PARAMETER_TYPES)
    private final List<String> parameterTypes;

    @JsonCreator
    public JavaFunction(
            @JsonProperty(FIELD_QUAL_NAME) String qualName,
            @JsonProperty(FIELD_METHOD_NAME) String methodName,
            @JsonProperty(FIELD_PARAMETER_TYPES) List<String> parameterTypes) {
        this.qualName = Objects.requireNonNull(qualName, "qualName");
        this.methodName = Objects.requireNonNull(methodName, "methodName");
        this.parameterTypes =
                parameterTypes == null
                        ? Collections.emptyList()
                        : Collections.unmodifiableList(new ArrayList<>(parameterTypes));
    }

    /**
     * Build a descriptor from a reflected {@link Method}. Each parameter type is captured via
     * {@link Class#getName()} — the same form {@link Class#forName(String)} accepts when the api
     * descriptor is later promoted to its plan-layer twin. For primitives this is the keyword
     * ({@code int}, {@code long}); for reference types the fully-qualified name; for array types
     * the JVM-internal descriptor ({@code [I}, {@code [Ljava.lang.String;}).
     */
    public static JavaFunction fromMethod(Method method) {
        List<String> params = new ArrayList<>(method.getParameterCount());
        for (Class<?> p : method.getParameterTypes()) {
            params.add(p.getName());
        }
        return new JavaFunction(method.getDeclaringClass().getName(), method.getName(), params);
    }

    public String getQualName() {
        return qualName;
    }

    public String getMethodName() {
        return methodName;
    }

    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JavaFunction)) return false;
        JavaFunction that = (JavaFunction) o;
        return qualName.equals(that.qualName)
                && methodName.equals(that.methodName)
                && parameterTypes.equals(that.parameterTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(qualName, methodName, parameterTypes);
    }

    @Override
    public String toString() {
        return "JavaFunction{" + qualName + "#" + methodName + parameterTypes + "}";
    }
}
