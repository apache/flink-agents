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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Helper class to describe a {@link Resource} */
public class ResourceDescriptor {
    private static final String FIELD_CLAZZ = "target_clazz";
    private static final String FIELD_MODULE = "target_module";
    private static final String FIELD_INITIAL_ARGUMENTS = "arguments";

    @JsonProperty(FIELD_CLAZZ)
    private final String clazz;

    @JsonProperty(FIELD_MODULE)
    private final String module;

    @JsonProperty(FIELD_INITIAL_ARGUMENTS)
    private final Map<String, Object> initialArguments;

    /**
     * Initialize ResourceDescriptor.
     *
     * <p>Creates a new ResourceDescriptor with the specified class information and initial
     * arguments. This constructor supports cross-platform compatibility between Java and Python
     * resources.
     *
     * @param clazz The class identifier for the resource. Its meaning depends on the resource type:
     *     <ul>
     *       <li><b>For Java resources:</b> The fully qualified Java class name (e.g.,
     *           "com.example.YourJavaClass"). The {@code module} parameter should be empty or null.
     *       <li><b>For Python resources (when declaring from Java):</b> The Python class name
     *           (simple name, not module path, e.g., "YourPythonClass"). The Python module path
     *           must be specified in the {@code module} parameter (e.g., "your_module.submodule").
     *     </ul>
     *
     * @param module The Python module path for cross-platform compatibility. Defaults to empty
     *     string for Java resources. Example: "your_module.submodule"
     * @param initialArguments Additional arguments for resource initialization. Can be null or
     *     empty map if no initial arguments are needed.
     */
    @JsonCreator
    public ResourceDescriptor(
            @JsonProperty(FIELD_MODULE) String module,
            @JsonProperty(FIELD_CLAZZ) String clazz,
            @JsonProperty(FIELD_INITIAL_ARGUMENTS) Map<String, Object> initialArguments) {
        this.clazz = clazz;
        this.module = module;
        this.initialArguments = initialArguments;
    }

    public ResourceDescriptor(String clazz, Map<String, Object> initialArguments) {
        this("", clazz, initialArguments);
    }

    public String getClazz() {
        return clazz;
    }

    public String getModule() {
        return module;
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
                && Objects.equals(this.module, that.module)
                && Objects.equals(this.initialArguments, that.initialArguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clazz, module, initialArguments);
    }

    public static class Builder {
        private final String clazz;
        private final Map<String, Object> initialArguments;

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

        public ResourceDescriptor build() {
            return new ResourceDescriptor(clazz, initialArguments);
        }
    }
}
