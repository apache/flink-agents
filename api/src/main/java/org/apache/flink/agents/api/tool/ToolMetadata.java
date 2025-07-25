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

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Metadata for a tool including its definition, invocation method, and parameters. This class holds
 * all the information needed to register and invoke a tool.
 */
public class ToolMetadata {

    private final ToolDefinition definition;
    private final ToolType type;
    private final Method method;
    private final Object instance;
    private final List<ToolParameterInfo> parameters;

    public ToolMetadata(
            ToolDefinition definition,
            ToolType type,
            Method method,
            Object instance,
            List<ToolParameterInfo> parameters) {
        this.definition = Objects.requireNonNull(definition, "definition cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.method = method; // can be null for built-in or remote tools
        this.instance = instance; // can be null for static methods
        this.parameters = Objects.requireNonNull(parameters, "parameters cannot be null");
    }

    public ToolDefinition getDefinition() {
        return definition;
    }

    public ToolType getType() {
        return type;
    }

    public Method getMethod() {
        return method;
    }

    public Object getInstance() {
        return instance;
    }

    public List<ToolParameterInfo> getParameters() {
        return parameters;
    }

    public String getName() {
        return definition.getName();
    }

    public String getDescription() {
        return definition.getDescription();
    }

    public String getInputSchema() {
        return definition.getInputSchema();
    }

    /** Check if this tool is a static method. */
    public boolean isStaticMethod() {
        return method != null && instance == null;
    }

    /** Check if this tool is an instance method. */
    public boolean isInstanceMethod() {
        return method != null && instance != null;
    }

    /** Check if this tool requires an instance to be invoked. */
    public boolean requiresInstance() {
        return isInstanceMethod();
    }

    /** Get parameter info by name. */
    public ToolParameterInfo getParameter(String name) {
        return parameters.stream()
                .filter(param -> param.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /** Check if a parameter exists. */
    public boolean hasParameter(String name) {
        return getParameter(name) != null;
    }

    /** Get the number of parameters. */
    public int getParameterCount() {
        return parameters.size();
    }

    /** Get the number of required parameters. */
    public int getRequiredParameterCount() {
        return (int) parameters.stream().filter(ToolParameterInfo::isRequired).count();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolMetadata that = (ToolMetadata) o;
        return Objects.equals(definition, that.definition)
                && type == that.type
                && Objects.equals(method, that.method)
                && Objects.equals(instance, that.instance)
                && Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(definition, type, method, instance, parameters);
    }

    @Override
    public String toString() {
        return "ToolMetadata{"
                + "name='"
                + getName()
                + '\''
                + ", type="
                + type
                + ", parameterCount="
                + parameters.size()
                + ", requiredParameters="
                + getRequiredParameterCount()
                + '}';
    }

    /** Information about a tool parameter. */
    public static class ToolParameterInfo {
        private final String name;
        private final String description;
        private final Class<?> type;
        private final boolean required;
        private final String defaultValue;

        public ToolParameterInfo(
                String name,
                String description,
                Class<?> type,
                boolean required,
                String defaultValue) {
            this.name = Objects.requireNonNull(name, "name cannot be null");
            this.description = description != null ? description : "";
            this.type = Objects.requireNonNull(type, "type cannot be null");
            this.required = required;
            this.defaultValue = defaultValue != null ? defaultValue : "";
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public Class<?> getType() {
            return type;
        }

        public boolean isRequired() {
            return required;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        /** Check if this parameter has a default value. */
        public boolean hasDefaultValue() {
            return !defaultValue.isEmpty();
        }

        /** Get the type name as a string. */
        public String getTypeName() {
            return type.getSimpleName();
        }

        /** Check if this parameter is optional (not required or has default value). */
        public boolean isOptional() {
            return !required || hasDefaultValue();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ToolParameterInfo that = (ToolParameterInfo) o;
            return required == that.required
                    && Objects.equals(name, that.name)
                    && Objects.equals(description, that.description)
                    && Objects.equals(type, that.type)
                    && Objects.equals(defaultValue, that.defaultValue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, description, type, required, defaultValue);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(name).append(": ").append(getTypeName());
            if (!required) {
                sb.append(" (optional)");
            }
            if (hasDefaultValue()) {
                sb.append(" = ").append(defaultValue);
            }
            if (!description.isEmpty()) {
                sb.append(" - ").append(description);
            }
            return sb.toString();
        }
    }
}
