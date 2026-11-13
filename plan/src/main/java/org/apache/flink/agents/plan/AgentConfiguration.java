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
package org.apache.flink.agents.plan;

import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.configuration.ConfigOption;
import org.apache.flink.agents.api.configuration.Configuration;
import org.apache.flink.agents.api.configuration.DurationFormat;
import org.apache.flink.agents.api.logger.TraceLogTarget;
import org.apache.flink.configuration.ConfigurationUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Agent configuration which stores key/value pairs. */
public class AgentConfiguration implements Configuration {
    private final Map<String, Object> confData;

    public AgentConfiguration() {
        this.confData = new HashMap<>();
    }

    /**
     * Copies configuration entries keyed by their full option names. Map and list values are
     * retained as option values; YAML key expansion belongs to the configuration loader.
     */
    public AgentConfiguration(Map<String, Object> confData) {
        this.confData = new HashMap<>();
        for (Map.Entry<String, Object> entry : confData.entrySet()) {
            this.confData.put(entry.getKey(), normalizeDurationValue(entry.getValue()));
        }
        String targetsKey = AgentConfigOptions.TRACE_LOG_TARGETS.getKey();
        if (this.confData.containsKey(targetsKey)) {
            this.confData.put(targetsKey, traceLogTargetConfig(this.confData.get(targetsKey)));
        }
    }

    public Map<String, Object> getConfData() {
        return confData;
    }

    @Override
    public void setStr(String key, String value) {
        putValue(key, value);
    }

    @Override
    public void setInt(String key, int value) {
        putValue(key, value);
    }

    @Override
    public void setLong(String key, long value) {
        putValue(key, value);
    }

    @Override
    public void setFloat(String key, float value) {
        putValue(key, value);
    }

    @Override
    public void setDouble(String key, double value) {
        putValue(key, value);
    }

    @Override
    public void setBool(String key, boolean value) {
        putValue(key, value);
    }

    @Override
    public <T> void set(ConfigOption<T> option, T value) {
        if (AgentConfigOptions.TRACE_LOG_TARGETS.equals(option)) {
            putValue(option.getKey(), traceLogTargetConfig(value));
            return;
        }
        if (value == null && option.getDefaultValue() != null) {
            return;
        }
        if (Duration.class.equals(option.getType())) {
            final Duration parsed;
            try {
                parsed = DurationFormat.parse(value);
            } catch (IllegalArgumentException e) {
                throw invalidDuration(option.getKey(), e);
            }
            DurationFormat.validateConsumedDuration(option.getKey(), parsed);
            putValue(option.getKey(), DurationFormat.format(parsed));
            return;
        }
        putValue(option.getKey(), value);
    }

    @Override
    public Integer getInt(String key, Integer defaultValue) {
        return Optional.ofNullable(confData.get(key))
                .map(Object::toString)
                .map(Integer::parseInt)
                .orElse(defaultValue);
    }

    @Override
    public Long getLong(String key, Long defaultValue) {
        return Optional.ofNullable(confData.get(key))
                .map(Object::toString)
                .map(Long::parseLong)
                .orElse(defaultValue);
    }

    @Override
    public Float getFloat(String key, Float defaultValue) {
        return Optional.ofNullable(confData.get(key))
                .map(Object::toString)
                .map(Float::parseFloat)
                .orElse(defaultValue);
    }

    @Override
    public Double getDouble(String key, Double defaultValue) {
        return Optional.ofNullable(confData.get(key))
                .map(Object::toString)
                .map(Double::parseDouble)
                .orElse(defaultValue);
    }

    @Override
    public Boolean getBool(String key, Boolean defaultValue) {
        return Optional.ofNullable(confData.get(key))
                .map(Object::toString)
                .map(Boolean::valueOf)
                .orElse(defaultValue);
    }

    @Override
    public String getStr(String key, String defaultValue) {
        return Optional.ofNullable(confData.get(key)).map(Object::toString).orElse(defaultValue);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(ConfigOption<T> option) {
        if (AgentConfigOptions.TRACE_LOG_TARGETS.equals(option)) {
            return (T)
                    TraceLogTarget.parseTargets(
                            confData.getOrDefault(option.getKey(), option.getDefaultValue()));
        }
        Object rawValue = confData.get(option.getKey());
        if (rawValue == null) {
            return option.getDefaultValue();
        }

        Class<T> targetType = option.getType();

        if (targetType.isAssignableFrom(rawValue.getClass())) {
            if (Duration.class.equals(targetType)) {
                DurationFormat.validateConsumedDuration(option.getKey(), (Duration) rawValue);
            }
            return targetType.cast(rawValue);
        } else if (String.class.equals(targetType)) {
            return targetType.cast(rawValue.toString());
        } else if (Integer.class.equals(targetType)) {
            return targetType.cast(Integer.parseInt(rawValue.toString()));
        } else if (Long.class.equals(targetType)) {
            return targetType.cast(Long.parseLong(rawValue.toString()));
        } else if (Float.class.equals(targetType)) {
            return targetType.cast(Float.parseFloat(rawValue.toString()));
        } else if (Double.class.equals(targetType)) {
            return targetType.cast(Double.parseDouble(rawValue.toString()));
        } else if (Boolean.class.equals(targetType)) {
            return targetType.cast(Boolean.parseBoolean(rawValue.toString()));
        } else if (Duration.class.equals(targetType)) {
            final Duration parsed;
            try {
                parsed = DurationFormat.parse(rawValue);
            } catch (IllegalArgumentException e) {
                throw invalidDuration(option.getKey(), e);
            }
            return targetType.cast(
                    DurationFormat.validateConsumedDuration(option.getKey(), parsed));
        } else if (targetType.isEnum()) {
            return ConfigurationUtils.convertValue(rawValue, targetType);
        } else {
            throw new ClassCastException(
                    "Unsupported type conversion from "
                            + rawValue.getClass().getName()
                            + " to "
                            + targetType.getName());
        }
    }

    private void putValue(String key, Object value) {
        confData.put(key, value);
    }

    private static Object normalizeDurationValue(Object value) {
        if (value instanceof Duration) {
            return DurationFormat.format(DurationFormat.parse(value));
        }
        return value;
    }

    private static IllegalArgumentException invalidDuration(
            String key, IllegalArgumentException cause) {
        return new IllegalArgumentException(
                "Invalid duration for '" + key + "': " + cause.getMessage(), cause);
    }

    private static List<Map<String, Object>> traceLogTargetConfig(Object value) {
        return TraceLogTarget.parseTargets(value).stream()
                .map(TraceLogTarget::toConfig)
                .collect(Collectors.toList());
    }
}
