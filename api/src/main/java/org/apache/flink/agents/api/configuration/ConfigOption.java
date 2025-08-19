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
package org.apache.flink.agents.api.configuration;

import java.util.Objects;

/** A {@code ConfigOption} describes a configuration parameter. */
public class ConfigOption<T> {
    private final String key;
    private final Class<T> type;
    private final T defaultValue;

    /**
     * Constructs a new configuration option.
     *
     * @param key The configuration key name
     * @param type The expected type of the configuration value
     * @param defaultValue The default value for this configuration option (can be null)
     */
    public ConfigOption(String key, Class<T> type, T defaultValue) {
        this.key = Objects.requireNonNull(key);
        this.type = Objects.requireNonNull(type);
        this.defaultValue = defaultValue;
    }

    /**
     * Gets the configuration key.
     *
     * @return the key
     */
    public String getKey() {
        return key;
    }

    /**
     * Gets the expected type of the configuration value.
     *
     * @return the type
     */
    public Class<T> getType() {
        return type;
    }

    /**
     * Gets the expected type name of the configuration value.
     *
     * @return the type name
     */
    public String getTypeName() {
        return type.getTypeName();
    }

    /**
     * Gets the default value of this configuration option.
     *
     * @return the default value, or null if not set
     */
    public T getDefaultValue() {
        return defaultValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigOption<?> that = (ConfigOption<?>) o;
        return key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "ConfigOption{"
                + "key='"
                + key
                + '\''
                + ", type="
                + type.getName()
                + ", defaultValue="
                + defaultValue
                + '}';
    }
}
