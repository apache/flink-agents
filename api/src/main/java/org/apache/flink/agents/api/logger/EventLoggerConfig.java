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

package org.apache.flink.agents.api.logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Unified configuration for event loggers with a fluent builder API.
 *
 * <p>A config selects an implementation via a {@link LoggerType} and carries a property map. The
 * full agent configuration (e.g., {@code AgentConfiguration.getConfData()}) is passed through under
 * {@link #AGENT_CONFIG_PROPERTY_KEY}; logger settings such as {@code baseLogDir}, {@code
 * prettyPrint}, and the {@code event-log.*} keys live inside that map rather than as top-level
 * properties.
 *
 * <h3>Usage Examples</h3>
 *
 * <pre>{@code
 * // Enable file-based event logging with custom log directory.
 * Map<String, Object> agentConfig = new HashMap<>();
 * agentConfig.put("baseLogDir", "/tmp/logs");
 * EventLoggerConfig fileConfig = EventLoggerConfig.builder()
 *     .loggerType(LoggerType.FILE)
 *     .property(EventLoggerConfig.AGENT_CONFIG_PROPERTY_KEY, agentConfig)
 *     .build();
 * }</pre>
 */
public final class EventLoggerConfig {

    /**
     * Property key used to pass the full agent config data map (e.g., {@code
     * AgentConfiguration.getConfData()}) into a logger via {@link Builder#property(String,
     * Object)}.
     *
     * <p>Built-in loggers read this property to initialize per-event-type log level resolution and
     * STANDARD-level truncation. Custom loggers may use the same property to access agent-level
     * configuration without taking a hard dependency on the runtime module.
     */
    public static final String AGENT_CONFIG_PROPERTY_KEY = "agentConfig";

    private final LoggerType loggerType;
    private final Map<String, Object> properties;

    /** Private constructor - use {@link #builder()} to create instances. */
    private EventLoggerConfig(LoggerType loggerType, Map<String, Object> properties) {
        this.loggerType = Objects.requireNonNull(loggerType, "Logger type cannot be null");
        this.properties = Collections.unmodifiableMap(new HashMap<>(properties));
    }

    /**
     * Creates a new builder for constructing EventLoggerConfig instances.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the logger type. Built-in types include {@link LoggerType#SLF4J} (default, outputs to
     * Flink Web UI via log4j2) and {@link LoggerType#FILE} (writes to per-subtask log files).
     *
     * @return the logger type, never null
     */
    public LoggerType getLoggerType() {
        return loggerType;
    }

    /**
     * Gets the properties carried by this configuration.
     *
     * <p>Currently the only well-known key is {@link #AGENT_CONFIG_PROPERTY_KEY}, which holds the
     * full agent config map; logger-specific settings (e.g., {@code baseLogDir}, {@code
     * prettyPrint}, {@code event-log.*}) are looked up inside that map by the logger implementation
     * rather than from this top-level map.
     *
     * @return an immutable map of property names to values, never null
     */
    public Map<String, Object> getProperties() {
        return properties;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EventLoggerConfig that = (EventLoggerConfig) o;
        return Objects.equals(loggerType, that.loggerType)
                && Objects.equals(properties, that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(loggerType, properties);
    }

    @Override
    public String toString() {
        return "EventLoggerConfig{loggerType=" + loggerType + ", properties=" + properties + '}';
    }

    /**
     * Builder for creating EventLoggerConfig instances with a fluent API.
     *
     * <p>This builder provides a convenient way to construct EventLoggerConfig objects with
     * validation and sensible defaults.
     */
    public static final class Builder {
        private LoggerType loggerType = LoggerType.SLF4J;
        private final Map<String, Object> properties = new HashMap<>();

        private Builder() {}

        /**
         * Sets the logger type.
         *
         * @param loggerType the built-in logger type
         * @return this Builder instance for method chaining
         * @throws IllegalArgumentException if loggerType is null
         */
        public Builder loggerType(LoggerType loggerType) {
            if (loggerType == null) {
                throw new IllegalArgumentException("Logger type cannot be null");
            }
            this.loggerType = loggerType;
            return this;
        }

        /**
         * Adds a property to the configuration.
         *
         * @param key the property key
         * @param value the property value
         * @return this Builder instance for method chaining
         * @throws IllegalArgumentException if key is null or empty, or if value is null
         */
        public Builder property(String key, Object value) {
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("Property key cannot be null or empty");
            }
            Objects.requireNonNull(value, "Property value cannot be null");
            this.properties.put(key.trim(), value);
            return this;
        }

        /**
         * Adds multiple properties to the configuration.
         *
         * @param properties a map of properties to add
         * @return this Builder instance for method chaining
         * @throws IllegalArgumentException if properties is null or contains null keys/values
         */
        public Builder properties(Map<String, Object> properties) {
            Objects.requireNonNull(properties, "Properties map cannot be null");
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                property(entry.getKey(), entry.getValue());
            }
            return this;
        }

        /**
         * Builds and returns an immutable EventLoggerConfig instance.
         *
         * @return a new EventLoggerConfig instance
         */
        public EventLoggerConfig build() {
            return new EventLoggerConfig(loggerType, properties);
        }
    }
}
