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
 * Factory for creating EventLogger instances based on {@link LoggerType}.
 *
 * <p>Built-in loggers (see {@link LoggerType}) are auto-registered. Additional implementations for
 * a given {@link LoggerType} can replace the built-in factory via {@link
 * #registerFactory(LoggerType, Function)}.
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
 * EventLoggerConfig fileConfig = EventLoggerConfig.builder()
 *     .loggerType(LoggerType.FILE)
 *     .property("baseLogDir", "/tmp/flink-agents")
 *     .build();
 * EventLogger fileLogger = EventLoggerFactory.createLogger(fileConfig);
 * }</pre>
 *
 * @see EventLogger
 * @see EventLoggerConfig
 * @see LoggerType
 */
public final class EventLoggerFactory {

    /** Thread-safe registry of factory functions keyed by {@link LoggerType}. */
    private static final Map<LoggerType, Function<EventLoggerConfig, EventLogger>> FACTORIES =
            new ConcurrentHashMap<>();

    private EventLoggerFactory() {}

    static {
        registerBuiltInFactories();
    }

    /**
     * Creates an EventLogger instance based on the provided configuration.
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

        LoggerType loggerType = config.getLoggerType();
        Function<EventLoggerConfig, EventLogger> factory = FACTORIES.get(loggerType);

        if (factory == null) {
            throw new IllegalArgumentException(
                    String.format(
                            "No factory registered for logger type: %s. Available types: %s.",
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
     * Registers a factory function for a specific {@link LoggerType}, replacing any previously
     * registered factory for that type.
     *
     * @param loggerType the logger type
     * @param factory the factory function that creates EventLogger instances
     * @throws IllegalArgumentException if loggerType or factory is null
     */
    public static void registerFactory(
            LoggerType loggerType, Function<EventLoggerConfig, EventLogger> factory) {
        Objects.requireNonNull(loggerType, "Logger type cannot be null");
        Objects.requireNonNull(factory, "Factory function cannot be null");
        FACTORIES.put(loggerType, factory);
    }

    /** Registers built-in EventLogger factories during class initialization. */
    private static void registerBuiltInFactories() {
        registerByReflection(
                LoggerType.FILE, "org.apache.flink.agents.runtime.eventlog.FileEventLogger");
        registerByReflection(
                LoggerType.SLF4J, "org.apache.flink.agents.runtime.eventlog.Slf4jEventLogger");
    }

    /**
     * Registers a built-in logger factory backed by a runtime-module class loaded reflectively, so
     * the api module does not need a compile-time dependency on the runtime module. Silently skips
     * registration when the runtime class is not on the classpath.
     */
    private static void registerByReflection(LoggerType loggerType, String className) {
        try {
            Class<?> loggerClass = Class.forName(className);
            registerFactory(
                    loggerType,
                    config -> {
                        try {
                            return (EventLogger)
                                    loggerClass
                                            .getConstructor(EventLoggerConfig.class)
                                            .newInstance(config);
                        } catch (Exception e) {
                            throw new RuntimeException(
                                    "Failed to create " + loggerType + " event logger", e);
                        }
                    });
        } catch (ClassNotFoundException e) {
            // The runtime module is not on the classpath; the built-in logger is unavailable.
        }
    }
}
