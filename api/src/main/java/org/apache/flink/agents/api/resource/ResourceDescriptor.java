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
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.flink.agents.api.configuration.DurationUtils;

import java.io.IOException;
import java.time.Duration;
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
    @JsonSerialize(contentUsing = InitialArgumentSerializer.class)
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
     *     </ul>
     *
     * @param module The Python module path for cross-platform compatibility. Defaults to empty
     *     string for Java resources. Example: "your_module.submodule"
     * @param initialArguments Additional arguments for resource initialization. Can be null or
     *     empty map if no initial arguments are needed. Python resources should be declared using
     *     PythonResourceDescriptor.
     */
    public ResourceDescriptor(
            @JsonProperty(FIELD_MODULE) String module,
            @JsonProperty(FIELD_CLAZZ) String clazz,
            @JsonProperty(FIELD_INITIAL_ARGUMENTS) Map<String, Object> initialArguments) {
        this.clazz = clazz;
        this.module = module;
        this.initialArguments = initialArguments;
    }

    /** Restore the declaration in the language that owns the target implementation. */
    @JsonCreator
    public static ResourceDescriptor fromJson(
            @JsonProperty("language") String language,
            @JsonProperty(FIELD_MODULE) String module,
            @JsonProperty(FIELD_CLAZZ) String clazz,
            @JsonProperty(FIELD_INITIAL_ARGUMENTS) Map<String, Object> arguments) {
        if ("python".equals(language)) {
            return new PythonResourceDescriptor(module, clazz, arguments);
        }
        if (!"java".equals(language)) {
            throw new IllegalArgumentException("Unknown resource language: " + language);
        }
        return new ResourceDescriptor(module, clazz, arguments);
    }

    @JsonProperty("language")
    public String getLanguage() {
        return "java";
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

    /**
     * Reads a duration with microsecond precision. Integers and unitless integer strings mean
     * milliseconds; unit strings and ISO-8601 are also accepted. Resources validate their own
     * range.
     *
     * @param name argument name
     * @param defaultValue value returned for a missing or null argument, possibly null
     * @return the parsed duration or default, without modifying the descriptor
     * @throws IllegalArgumentException if the value has an invalid format, range, or precision; the
     *     message includes the argument name
     */
    public Duration getDurationArgument(String name, Duration defaultValue) {
        Object value = getArgument(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return DurationUtils.parse(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid duration for resource argument '" + name + "': " + e.getMessage(), e);
        }
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

    /** Serializes top-level Duration arguments as ISO-8601 strings. */
    static final class InitialArgumentSerializer extends JsonSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            if (value instanceof Duration) {
                generator.writeString(value.toString());
            } else {
                provider.defaultSerializeValue(value, generator);
            }
        }
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
