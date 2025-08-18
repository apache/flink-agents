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

import org.apache.flink.agents.api.EventFilter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Unified configuration for event loggers with a fluent builder API.
 *
 * <p>This class provides a unified approach to configuring different types of event loggers using
 * string-based logger type identification and a flexible property map for implementation-specific
 * parameters.
 *
 * <h3>Usage Examples</h3>
 *
 * <pre>{@code
 * // Enable default file-based event logging with custom properties
 * EventLoggerConfig fileConfig = EventLoggerConfig.builder()
 *     .loggerType("file")
 *     .property("baseLogDir", "/tmp/logs")
 *     .build();
 * }</pre>
 */
public final class EventLoggerConfig {

    private final String loggerType;
    private final EventFilter eventFilter;
    private final Map<String, Object> properties;

    /** Private constructor - use {@link #builder()} to create instances. */
    private EventLoggerConfig(
            String loggerType, EventFilter eventFilter, Map<String, Object> properties) {
        this.loggerType = Objects.requireNonNull(loggerType, "Logger type cannot be null");
        this.eventFilter = eventFilter == null ? EventFilter.ACCEPT_ALL : eventFilter;
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
     * Gets the logger type identifier.
     *
     * <p>This string identifier is used to determine which EventLogger implementation should be
     * instantiated. Built-in logger types include:
     *
     * <ul>
     *   <li>"file" - File-based event logger (default)
     * </ul>
     *
     * @return the logger type identifier (e.g., "file", "database", "kafka")
     */
    public String getLoggerType() {
        return loggerType;
    }

    /**
     * Gets the event filter for this logger configuration.
     *
     * @return the EventFilter to apply, never null
     */
    public EventFilter getEventFilter() {
        return eventFilter;
    }

    /**
     * Gets the implementation-specific properties for this logger configuration.
     *
     * <p>These properties contain logger-specific configuration parameters that are not common
     * across all logger implementations. For example:
     *
     * <ul>
     *   <li>File logger: "baseLogDir", "maxFileSize", "compression"
     *   <li>Database logger: "connectionUrl", "tableName", "batchSize"
     * </ul>
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
                && Objects.equals(eventFilter, that.eventFilter)
                && Objects.equals(properties, that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(loggerType, eventFilter, properties);
    }

    @Override
    public String toString() {
        return "EventLoggerConfig{"
                + "loggerType='"
                + loggerType
                + '\''
                + ", eventFilter="
                + eventFilter
                + ", properties="
                + properties
                + '}';
    }

    /**
     * Builder for creating EventLoggerConfig instances with a fluent API.
     *
     * <p>This builder provides a convenient way to construct EventLoggerConfig objects with
     * validation and sensible defaults.
     */
    public static final class Builder {
        private String loggerType = "file"; // Default to file logger
        private EventFilter eventFilter = EventFilter.ACCEPT_ALL; // Default to accept all
        private final Map<String, Object> properties = new HashMap<>();

        private Builder() {}

        /**
         * Sets the logger type identifier.
         *
         * @param loggerType the logger type (e.g., "file", "database", "kafka")
         * @return this Builder instance for method chaining
         * @throws IllegalArgumentException if loggerType is null or empty
         */
        public Builder loggerType(String loggerType) {
            if (loggerType == null || loggerType.trim().isEmpty()) {
                throw new IllegalArgumentException("Logger type cannot be null or empty");
            }
            this.loggerType = loggerType.trim();
            return this;
        }

        /**
         * Sets the event filter for this configuration.
         *
         * @param eventFilter the EventFilter to apply
         * @return this Builder instance for method chaining
         * @throws IllegalArgumentException if eventFilter is null
         */
        public Builder eventFilter(EventFilter eventFilter) {
            this.eventFilter = Objects.requireNonNull(eventFilter, "Event filter cannot be null");
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
            return new EventLoggerConfig(loggerType, eventFilter, properties);
        }
    }
}
