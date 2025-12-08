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

package org.apache.flink.agents.api.resource;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonCreator;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Helper class to describe a {@link Resource} */
public class ResourceDescriptor {
    private static final String FIELD_CLAZZ = "java_clazz";
    private static final String FIELD_PYTHON_CLAZZ = "python_clazz";
    private static final String FIELD_PYTHON_MODULE = "python_module";
    private static final String FIELD_INITIAL_ARGUMENTS = "arguments";

    @JsonProperty(FIELD_CLAZZ)
    private final String clazz;

    @JsonProperty(FIELD_PYTHON_CLAZZ)
    private final String pythonClazz;

    @JsonProperty(FIELD_PYTHON_MODULE)
    private final String pythonModule;

    @JsonProperty(FIELD_INITIAL_ARGUMENTS)
    private final Map<String, Object> initialArguments;

    /**
     * Initialize ResourceDescriptor.
     *
     * <p>Creates a new ResourceDescriptor with the specified class information and initial
     * arguments. This constructor supports cross-platform compatibility between Java and Python
     * resources.
     *
     * @param clazz The Java class full path for the resource type to create a descriptor for.
     *     Example: "com.example.YourJavaClass"
     * @param pythonModule The Python module path for cross-platform compatibility. **REQUIRED when
     *     declaring Python resources in Java.** Defaults to empty string for Java-only resources.
     *     Example: "your_module.submodule"
     * @param pythonClazz The Python class name for cross-platform compatibility. **REQUIRED when
     *     declaring Python resources in Java.** Defaults to empty string for Java-only resources.
     *     Example: "YourPythonClass"
     * @param initialArguments Additional arguments for resource initialization. Can be null or
     *     empty map if no initial arguments are needed.
     */
    @JsonCreator
    public ResourceDescriptor(
            @JsonProperty(FIELD_CLAZZ) String clazz,
            @JsonProperty(FIELD_PYTHON_MODULE) String pythonModule,
            @JsonProperty(FIELD_PYTHON_CLAZZ) String pythonClazz,
            @JsonProperty(FIELD_INITIAL_ARGUMENTS) Map<String, Object> initialArguments) {
        this.clazz = clazz;
        this.pythonClazz = pythonClazz;
        this.pythonModule = pythonModule;
        this.initialArguments = initialArguments;
    }

    public ResourceDescriptor(String clazz, Map<String, Object> initialArguments) {
        this(clazz, "", "", initialArguments);
    }

    public String getClazz() {
        return clazz;
    }

    public String getPythonClazz() {
        return pythonClazz;
    }

    public String getPythonModule() {
        return pythonModule;
    }

    public Map<String, Object> getInitialArguments() {
        return initialArguments;
    }

    @SuppressWarnings("unchecked")
    public <T> T getArgument(String argName) {
        return (T) initialArguments.get(argName);
    }

    public <T> T getArgument(String argName, T defaultValue) {
        T value = getArgument(argName);
        return value != null ? value : defaultValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ResourceDescriptor that = (ResourceDescriptor) o;
        return Objects.equals(this.clazz, that.clazz)
                && Objects.equals(this.pythonClazz, that.pythonClazz)
                && Objects.equals(this.pythonModule, that.pythonModule)
                && Objects.equals(this.initialArguments, that.initialArguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clazz, pythonClazz, pythonModule, initialArguments);
    }

    public static class Builder {
        private final String clazz;
        private final Map<String, Object> initialArguments;

        private String pythonClazz;
        private String pythonModule;

        public static Builder newBuilder(String clazz) {
            return new Builder(clazz);
        }

        public Builder(String clazz) {
            this.clazz = clazz;
            this.initialArguments = new HashMap<>();
        }

        public Builder addInitialArgument(String argName, Object argValue) {
            this.initialArguments.put(argName, argValue);
            return this;
        }

        public Builder setPythonResourceClass(String pythonModule, String pythonClazz) {
            this.pythonClazz = pythonClazz;
            this.pythonModule = pythonModule;
            return this;
        }

        public ResourceDescriptor build() {
            if (pythonClazz != null && pythonModule != null) {
                return new ResourceDescriptor(clazz, pythonModule, pythonClazz, initialArguments);
            }
            return new ResourceDescriptor(clazz, initialArguments);
        }
    }
}
