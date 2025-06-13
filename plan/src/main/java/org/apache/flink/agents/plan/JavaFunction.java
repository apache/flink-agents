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

import java.lang.reflect.Method;
import java.util.Arrays;

/** Represent a Java function. */
public class JavaFunction implements Function {
    private final String qualName;
    private final String methodName;
    private final Class<?>[] parameterTypes;

    public JavaFunction(String qualName, String methodName, Class<?>[] parameterTypes) {
        this.qualName = qualName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
    }

    @Override
    public Object call(Object... args) throws Exception {
        Class<?> clazz = Class.forName(qualName);
        Method method = clazz.getMethod(methodName, parameterTypes);
        return method.invoke(null, args);
    }

    @Override
    public void checkSignature(Class<?>[] parameterTypes) throws Exception {
        String errMsg =
                String.format(
                        "Expect signature %s, but got %s",
                        Arrays.toString(parameterTypes), Arrays.toString(this.parameterTypes));
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
