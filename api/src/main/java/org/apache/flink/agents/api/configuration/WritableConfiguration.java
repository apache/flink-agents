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

/** Write access to a configuration object. */
public interface WritableConfiguration {
    /**
     * Set the string configuration value using the key.
     *
     * @param key The configuration key to set
     * @param value The string value to set for the key
     */
    void setStr(String key, String value);

    /**
     * Set the integer configuration value using the key.
     *
     * @param key The configuration key to set
     * @param value The integer value to set for the key
     */
    void setInt(String key, int value);

    /**
     * Set the long configuration value using the key.
     *
     * @param key The configuration key to set
     * @param value The long value to set for the key
     */
    void setLong(String key, long value);

    /**
     * Set the float configuration value using the key.
     *
     * @param key The configuration key to set
     * @param value The float value to set for the key
     */
    void setFloat(String key, float value);

    /**
     * Set the double configuration value using the key.
     *
     * @param key The configuration key to set
     * @param value The double value to set for the key
     */
    void setDouble(String key, double value);

    /**
     * Set the boolean configuration value using the key.
     *
     * @param key The configuration key to set
     * @param value The boolean value to set for the key
     */
    void setBool(String key, boolean value);

    /**
     * Set the configuration value using the ConfigOption.
     *
     * @param option The config option to set
     * @param value The value to set for the key
     */
    <T> void set(ConfigOption<T> option, T value);
}
