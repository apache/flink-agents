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
package org.apache.flink.agents.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

/** Represent a Java function. */
public class JavaFunction implements Function {
    static final String FIELD_NAME_QUAL_NAME = "qualName";
    static final String FIELD_NAME_METHOD_NAME = "methodName";
    static final String FIELD_NAME_PARAMETER_TYPES = "parameterTypes";

    @JsonProperty(FIELD_NAME_QUAL_NAME)
    private final String qualName;

    @JsonProperty(FIELD_NAME_METHOD_NAME)
    private final String methodName;

    @JsonProperty(FIELD_NAME_PARAMETER_TYPES)
    private final Class<?>[] parameterTypes;

    @JsonIgnore private transient Method method;

    public JavaFunction(
            @JsonProperty(FIELD_NAME_QUAL_NAME) String qualName,
            @JsonProperty(FIELD_NAME_METHOD_NAME) String methodName,
            @JsonProperty(FIELD_NAME_PARAMETER_TYPES) Class<?>[] parameterTypes)
            throws Exception {
        this.qualName = qualName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
    }

    public JavaFunction(Class<?> clazz, String methodName, Class<?>[] parameterTypes)
            throws Exception {
        this.qualName = clazz.getName();
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.method = clazz.getMethod(methodName, parameterTypes);
    }

    public String getQualName() {
        return qualName;
    }

    public String getMethodName() {
        return methodName;
    }

    public Class<?>[] getParameterTypes() {
        return parameterTypes;
    }

    public Method getMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (method == null) {
            this.method =
                    Class.forName(qualName, true, Thread.currentThread().getContextClassLoader())
                            .getMethod(methodName, parameterTypes);
        }
        return method;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        JavaFunction that = (JavaFunction) o;
        return Objects.equals(this.qualName, that.qualName)
                && Objects.equals(this.methodName, that.methodName)
                && Arrays.equals(this.parameterTypes, that.parameterTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(qualName, methodName, Arrays.hashCode(parameterTypes));
    }

    @Override
    public Object call(Object... args) throws Exception {
        return getMethod().invoke(null, args);
    }

    @Override
    public void checkSignature(Class<?>[] parameterTypes) {
        String errMsg =
                String.format(
                        "Function \"%s\" expects signature %s, but got %s",
                        qualName + '.' + methodName,
                        Arrays.toString(parameterTypes),
                        Arrays.toString(this.parameterTypes));
        if (this.parameterTypes.length != parameterTypes.length) {
            throw new IllegalArgumentException(errMsg);
        }

        for (int i = 0; i < parameterTypes.length; i++) {
            if (!parameterTypes[i].isAssignableFrom(this.parameterTypes[i])) {
                throw new IllegalArgumentException(errMsg);
            }
        }
    }
}
