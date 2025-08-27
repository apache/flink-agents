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

/** Read access to a configuration object. */
public interface ReadableConfiguration {
    /**
     * Get the integer configuration value by key.
     *
     * @param key The configuration key to retrieve
     * @param defaultValue The default value to return if key is not found
     * @return The integer value associated with the key or the default value
     */
    Integer getInt(String key, Integer defaultValue);

    /**
     * Get the long configuration value by key.
     *
     * @param key The configuration key to retrieve
     * @param defaultValue The default value to return if key is not found
     * @return The long value associated with the key or the default value
     */
    Long getLong(String key, Long defaultValue);

    /**
     * Get the float configuration value by key.
     *
     * @param key The configuration key to retrieve
     * @param defaultValue The default value to return if key is not found
     * @return The float value associated with the key or the default value
     */
    Float getFloat(String key, Float defaultValue);

    /**
     * Get the double configuration value by key.
     *
     * @param key The configuration key to retrieve
     * @param defaultValue The default value to return if key is not found
     * @return The double value associated with the key or the default value
     */
    Double getDouble(String key, Double defaultValue);

    /**
     * Get the boolean configuration value by key.
     *
     * @param key The configuration key to retrieve
     * @param defaultValue The default value to return if key is not found
     * @return The boolean value associated with the key or the default value
     */
    Boolean getBool(String key, Boolean defaultValue);

    /**
     * Get the string configuration value by key.
     *
     * @param key The configuration key to retrieve
     * @param defaultValue The default value to return if key is not found
     * @return The string value associated with the key or the default value
     */
    String getStr(String key, String defaultValue);

    /**
     * Get the configuration value by ConfigOption.
     *
     * @param option The metadata of the option to read
     * @param <T> The type of the configuration value
     * @return The value of the given option
     */
    <T> T get(ConfigOption<T> option);
}
