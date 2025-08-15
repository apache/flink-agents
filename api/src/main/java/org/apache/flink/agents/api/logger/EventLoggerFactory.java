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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Factory for creating EventLogger instances based on EventLoggerConfig types.
 *
 * <p>This factory uses a registry-based approach that allows both built-in and custom EventLogger
 * implementations to be created in a type-safe manner. Built-in loggers are automatically
 * registered, while custom loggers can be registered using the {@link #registerFactory(Class,
 * Function)} method.
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
 * FileEventLoggerConfig fileConfig = FileEventLoggerConfig.builder()
 *     .logFileDir("/tmp/flink-agents")
 *     .build();
 * EventLogger fileLogger = EventLoggerFactory.createLogger(fileConfig);
 *
 * // Register and use custom logger
 * EventLoggerFactory.registerFactory(MyCustomConfig.class,
 *     config -> new MyCustomLogger((MyCustomConfig) config));
 *
 * MyCustomConfig customConfig = new MyCustomConfig(...);
 * EventLogger customLogger = EventLoggerFactory.createLogger(customConfig);
 * }</pre>
 *
 * @see EventLogger
 * @see EventLoggerConfig
 */
public final class EventLoggerFactory {

    /** Thread-safe registry of factory functions keyed by config class type. */
    private static final Map<
                    Class<? extends EventLoggerConfig>, Function<EventLoggerConfig, EventLogger>>
            FACTORIES = new ConcurrentHashMap<>();

    private EventLoggerFactory() {}

    static {
        registerBuiltInFactories();
    }

    /**
     * Creates an EventLogger instance based on the provided configuration.
     *
     * <p>This method looks up the appropriate factory function for the given configuration type and
     * uses it to create the EventLogger instance.
     *
     * @param config the EventLogger configuration
     * @return a new EventLogger instance configured according to the provided config
     * @throws IllegalArgumentException if config is null or no factory is registered for the config
     *     type
     * @throws RuntimeException if the factory function fails to create the logger
     */
    public static EventLogger createLogger(EventLoggerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("EventLoggerConfig cannot be null");
        }

        Class<? extends EventLoggerConfig> configType = config.getClass();
        Function<EventLoggerConfig, EventLogger> factory = FACTORIES.get(configType);

        if (factory == null) {
            throw new IllegalArgumentException(
                    String.format(
                            "No factory registered for config type: %s. "
                                    + "Use EventLoggerFactory.registerFactory() to register custom loggers.",
                            configType.getName()));
        }

        try {
            return factory.apply(config);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to create EventLogger for config type: " + configType.getName(), e);
        }
    }

    /**
     * Registers a factory function for a specific EventLoggerConfig type.
     *
     * <p>This method allows custom EventLogger implementations to be registered with the factory.
     * Once registered, the factory can create instances of the custom logger using the {@link
     * #createLogger(EventLoggerConfig)} method.
     *
     * <p>If a factory is already registered for the given config type, it will be replaced with the
     * new factory function.
     *
     * @param <T> the specific EventLoggerConfig type
     * @param configType the Class object representing the config type
     * @param factory the factory function that creates EventLogger instances
     * @throws IllegalArgumentException if configType or factory is null
     */
    public static <T extends EventLoggerConfig> void registerFactory(
            Class<T> configType, Function<T, EventLogger> factory) {

        if (configType == null) {
            throw new IllegalArgumentException("Config type cannot be null");
        }
        if (factory == null) {
            throw new IllegalArgumentException("Factory function cannot be null");
        }

        // Type-safe wrapper that casts the config before applying the factory
        Function<EventLoggerConfig, EventLogger> wrappedFactory =
                config -> {
                    if (!configType.isInstance(config)) {
                        throw new IllegalArgumentException(
                                "Config type mismatch. Expected: "
                                        + configType.getName()
                                        + ", but got: "
                                        + config.getClass().getName());
                    }
                    @SuppressWarnings("unchecked")
                    T typedConfig = (T) config;
                    return factory.apply(typedConfig);
                };

        FACTORIES.put(configType, wrappedFactory);
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

    private static void registerFileEventLoggerFactory() {
        // Register FileEventLogger factory
        // Use reflection to avoid hard dependency on runtime module
        try {
            Class<?> fileConfigClass =
                    Class.forName("org.apache.flink.agents.runtime.logger.FileEventLoggerConfig");
            Class<?> fileLoggerClass =
                    Class.forName("org.apache.flink.agents.runtime.logger.FileEventLogger");
            if (EventLoggerConfig.class.isAssignableFrom(fileConfigClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends EventLoggerConfig> configType =
                        (Class<? extends EventLoggerConfig>) fileConfigClass;

                registerFactory(
                        configType,
                        config -> {
                            try {
                                return (EventLogger)
                                        fileLoggerClass
                                                .getConstructor(fileConfigClass)
                                                .newInstance(config);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to create FileEventLogger", e);
                            }
                        });
            }
        } catch (ClassNotFoundException e) {
            // FileEventLoggerConfig not found, skip registration
            // This is expected if the runtime module is not on the classpath
        }
    }
}
