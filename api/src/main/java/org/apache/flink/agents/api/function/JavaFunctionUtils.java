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

import java.lang.reflect.Method;
import java.util.List;

/** Utilities for resolving pure-data Java function descriptors. */
public final class JavaFunctionUtils {

    private JavaFunctionUtils() {}

    public static Method resolveMethod(JavaFunction function)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> clazz =
                Class.forName(
                        function.getQualName(),
                        true,
                        Thread.currentThread().getContextClassLoader());
        return clazz.getMethod(
                function.getMethodName(), resolveParameterTypes(function.getParameterTypes()));
    }

    public static Class<?>[] resolveParameterTypes(List<String> names)
            throws ClassNotFoundException {
        Class<?>[] out = new Class<?>[names.size()];
        for (int i = 0; i < names.size(); i++) {
            out[i] = resolveParameterType(names.get(i));
        }
        return out;
    }

    public static Class<?> resolveParameterType(String name) throws ClassNotFoundException {
        switch (name) {
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
                return Class.forName(name, true, Thread.currentThread().getContextClassLoader());
        }
    }
}
