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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Factory for creating EventLogger instances based on logger type identifiers.
 *
 * <p>This factory uses a string-based registry approach that allows both built-in and custom
 * EventLogger implementations to be created in a flexible manner. Built-in loggers are
 * automatically registered, while custom loggers can be registered using the {@link
 * #registerFactory(String, Function)} method.
 *
 * <p>Logger types are identified by simple string identifiers (e.g., "file", "database", "kafka"),
 * making the factory easy to use with configuration files and providing a clean abstraction for
 * different logger implementations.
 *
 * <h3>Thread Safety</h3>
 *
 * <p>This factory is thread-safe and can be used concurrently from multiple threads. Factory
 * registration and logger creation operations are atomic and do not require external
 * synchronization.
 *
 * <h3>Usage Examples</h3>
 *
 * <pre>{@code
 * // Create built-in file logger
 * EventLoggerConfig fileConfig = EventLoggerConfig.builder()
 *     .loggerType("file")
 *     .property("baseLogDir", "/tmp/flink-agents")
 *     .build();
 * EventLogger fileLogger = EventLoggerFactory.createLogger(fileConfig);
 *
 * // Register and use custom logger
 * EventLoggerFactory.registerFactory("database",
 *     config -> new DatabaseEventLogger(config));
 *
 * EventLoggerConfig dbConfig = EventLoggerConfig.builder()
 *     .loggerType("database")
 *     .property("jdbcUrl", "jdbc:mysql://localhost/logs")
 *     .build();
 * EventLogger customLogger = EventLoggerFactory.createLogger(dbConfig);
 * }</pre>
 *
 * @see EventLogger
 * @see EventLoggerConfig
 */
public final class EventLoggerFactory {

    /** Thread-safe registry of factory functions keyed by logger type identifier. */
    private static final Map<String, Function<EventLoggerConfig, EventLogger>> FACTORIES =
            new ConcurrentHashMap<>();

    private EventLoggerFactory() {}

    static {
        registerBuiltInFactories();
    }

    /**
     * Creates an EventLogger instance based on the provided configuration.
     *
     * <p>This method looks up the appropriate factory function for the logger type specified in the
     * configuration and uses it to create the EventLogger instance.
     *
     * @param config the EventLogger configuration
     * @return a new EventLogger instance configured according to the provided config
     * @throws IllegalArgumentException if config is null or no factory is registered for the logger
     *     type
     * @throws RuntimeException if the factory function fails to create the logger
     */
    public static EventLogger createLogger(EventLoggerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("EventLoggerConfig cannot be null");
        }

        String loggerType = config.getLoggerType();
        if (loggerType == null || loggerType.trim().isEmpty()) {
            throw new IllegalArgumentException("Logger type cannot be null or empty");
        }

        Function<EventLoggerConfig, EventLogger> factory = FACTORIES.get(loggerType);

        if (factory == null) {
            throw new IllegalArgumentException(
                    String.format(
                            "No factory registered for logger type: '%s'. "
                                    + "Available types: %s. "
                                    + "Use EventLoggerFactory.registerFactory() to register custom loggers.",
                            loggerType, FACTORIES.keySet()));
        }

        try {
            return factory.apply(config);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to create EventLogger for logger type: " + loggerType, e);
        }
    }

    /**
     * Registers a factory function for a specific logger type.
     *
     * <p>This method allows custom EventLogger implementations to be registered with the factory.
     * Once registered, the factory can create instances of the custom logger using the {@link
     * #createLogger(EventLoggerConfig)} method.
     *
     * <p>If a factory is already registered for the given logger type, it will be replaced with the
     * new factory function.
     *
     * @param loggerType the logger type identifier (e.g., "file", "database", "kafka")
     * @param factory the factory function that creates EventLogger instances
     * @throws IllegalArgumentException if loggerType or factory is null or if loggerType is empty
     */
    public static void registerFactory(
            String loggerType, Function<EventLoggerConfig, EventLogger> factory) {
        if (loggerType == null || loggerType.trim().isEmpty()) {
            throw new IllegalArgumentException("Logger type cannot be null or empty");
        }
        Objects.requireNonNull(factory, "Factory function cannot be null");

        FACTORIES.put(loggerType.trim(), factory);
    }

    /**
     * Registers built-in EventLogger factories.
     *
     * <p>This method is called during class initialization to register factories for all built-in
     * EventLogger implementations.
     */
    private static void registerBuiltInFactories() {
        registerFileEventLoggerFactory();
    }

    /**
     * Registers the built-in file event logger factory.
     *
     * <p>This uses reflection to avoid hard dependencies on the runtime module, allowing the API
     * module to be used independently.
     */
    private static void registerFileEventLoggerFactory() {
        try {
            // Try to load FileEventLogger class
            Class<?> fileLoggerClass =
                    Class.forName("org.apache.flink.agents.runtime.eventlog.FileEventLogger");

            registerFactory(
                    "file",
                    config -> {
                        try {
                            // FileEventLogger now takes unified EventLoggerConfig directly
                            return (EventLogger)
                                    fileLoggerClass
                                            .getConstructor(EventLoggerConfig.class)
                                            .newInstance(config);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to create FileEventLogger", e);
                        }
                    });
        } catch (ClassNotFoundException e) {
            // FileEventLogger not found, skip registration
            // This is expected if the runtime module is not on the classpath
        }
    }
}
