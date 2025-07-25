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

package org.apache.flink.agents.api.tool;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Container for tool parameters with type-safe parameter retrieval. */
public class ToolParameters {

    private final Map<String, Object> parameters;

    public ToolParameters() {
        this.parameters = new HashMap<>();
    }

    public ToolParameters(Map<String, Object> parameters) {
        this.parameters = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
    }

    /** Check if a parameter exists. */
    public boolean hasParameter(String name) {
        return parameters.containsKey(name);
    }

    /** Get a parameter with type conversion. */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String name, Class<T> type) {
        Object value = parameters.get(name);
        if (value == null) {
            return null;
        }

        // Direct type match
        if (type.isInstance(value)) {
            return (T) value;
        }

        // Type conversion for common cases
        return convertValue(value, type);
    }

    /** Get raw parameter value. */
    public Object getParameter(String name) {
        return parameters.get(name);
    }

    /** Add a parameter. */
    public void addParameter(String name, Object value) {
        parameters.put(name, value);
    }

    /** Get all parameter names. */
    public Set<String> getParameterNames() {
        return parameters.keySet();
    }

    /** Get the number of parameters. */
    public int size() {
        return parameters.size();
    }

    /** Check if parameters are empty. */
    public boolean isEmpty() {
        return parameters.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private <T> T convertValue(Object value, Class<T> type) {
        String stringValue = value.toString();

        if (type == String.class) {
            return (T) stringValue;
        } else if (type == Integer.class || type == int.class) {
            return (T) Integer.valueOf(stringValue);
        } else if (type == Long.class || type == long.class) {
            return (T) Long.valueOf(stringValue);
        } else if (type == Double.class || type == double.class) {
            return (T) Double.valueOf(stringValue);
        } else if (type == Float.class || type == float.class) {
            return (T) Float.valueOf(stringValue);
        } else if (type == Boolean.class || type == boolean.class) {
            return (T) Boolean.valueOf(stringValue);
        } else if (type == Byte.class || type == byte.class) {
            return (T) Byte.valueOf(stringValue);
        } else if (type == Short.class || type == short.class) {
            return (T) Short.valueOf(stringValue);
        }

        throw new IllegalArgumentException(
                "Cannot convert parameter value to type " + type.getSimpleName());
    }

    @Override
    public String toString() {
        return "ToolParameters{" + parameters + "}";
    }
}
