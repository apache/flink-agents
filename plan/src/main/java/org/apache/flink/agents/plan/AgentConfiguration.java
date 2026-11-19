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

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.configuration.ConfigOption;
import org.apache.flink.agents.api.configuration.Configuration;
import org.apache.flink.agents.api.configuration.DurationUtils;
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
        confData.put(key, value);
    }

    @Override
    public void setInt(String key, int value) {
        confData.put(key, value);
    }

    @Override
    public void setLong(String key, long value) {
        confData.put(key, value);
    }

    @Override
    public void setFloat(String key, float value) {
        confData.put(key, value);
    }

    @Override
    public void setDouble(String key, double value) {
        confData.put(key, value);
    }

    @Override
    public void setBool(String key, boolean value) {
        confData.put(key, value);
    }

    @Override
    public <T> void set(ConfigOption<T> option, T value) {
        if (AgentConfigOptions.TRACE_LOG_TARGETS.equals(option)) {
            confData.put(option.getKey(), traceLogTargetConfig(value));
            return;
        }
        if (value == null && option.getDefaultValue() != null) {
            return;
        }
        if (Duration.class.equals(option.getType())) {
            final Duration parsed;
            try {
                parsed = DurationUtils.parse(value);
            } catch (IllegalArgumentException e) {
                throw invalidDuration(option.getKey(), e);
            }
            confData.put(option.getKey(), DurationUtils.format(parsed));
            return;
        }
        confData.put(option.getKey(), value);
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

        // Strings and Duration objects share parse(), including microsecond alignment.
        // Retry-wait and short-term memory TTL rules are applied at job submission.
        if (Duration.class.equals(targetType)) {
            final Duration parsed;
            try {
                parsed = DurationUtils.parse(rawValue);
            } catch (IllegalArgumentException e) {
                throw invalidDuration(option.getKey(), e);
            }
            return targetType.cast(parsed);
        } else if (targetType.isAssignableFrom(rawValue.getClass())) {
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

    private static Object normalizeDurationValue(Object value) {
        if (value instanceof Duration) {
            return DurationUtils.format(DurationUtils.parse(value));
        }
        return value;
    }

    private static IllegalArgumentException invalidDuration(
            String key, IllegalArgumentException cause) {
        return new IllegalArgumentException(
                "Invalid duration for '" + key + "': " + cause.getMessage(), cause);
    }

    /**
     * Checks the retry-wait and short-term memory TTL durations on {@code agentPlan} before an
     * operator is constructed.
     *
     * <p>Both values are read through {@link #get(ConfigOption)} and then converted with {@link
     * Duration#toMillis()}. A non-positive retry wait means no waiting. A positive retry wait is
     * truncated toward zero, and a truncated result of zero is allowed. A non-positive TTL disables
     * expiration. A positive TTL must convert to a number of milliseconds greater than zero, so
     * {@code 500 us} fails instead of disabling TTL, while {@code 1500 us} is kept and later
     * truncated to {@code 1 ms}. {@code toMillis()} also rejects a duration that overflows a long.
     * Async batch timeout is not checked and keeps its microsecond duration.
     *
     * @throws IllegalArgumentException if a positive TTL converts to {@code 0} ms, or if either
     *     positive duration overflows {@link Duration#toMillis()}
     */
    public static void validateExecutionOptions(AgentPlan agentPlan) {
        if (agentPlan == null) {
            throw new IllegalArgumentException("An agent plan is required.");
        }
        AgentConfiguration config = agentPlan.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("An agent plan configuration is required.");
        }
        validateRetryWait(config.get(AgentExecutionOptions.RETRY_WAIT_INTERVAL));
        validateShortTermMemoryTtl(config.get(AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL));
    }

    private static void validateRetryWait(Duration duration) {
        if (!DurationUtils.isPositive(duration)) {
            return;
        }
        millisAfterTruncation(AgentExecutionOptions.RETRY_WAIT_INTERVAL.getKey(), duration);
    }

    private static void validateShortTermMemoryTtl(Duration duration) {
        if (!DurationUtils.isPositive(duration)) {
            return;
        }
        String optionKey = AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL.getKey();
        long millis = millisAfterTruncation(optionKey, duration);
        if (millis <= 0) {
            throw new IllegalArgumentException(
                    optionKey
                            + " must be greater than 0 ms after conversion to milliseconds,"
                            + " or zero or negative to disable TTL, got "
                            + DurationUtils.format(duration));
        }
    }

    /** {@link Duration#toMillis()} detects overflow as well as truncating toward zero. */
    private static long millisAfterTruncation(String optionKey, Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    optionKey
                            + " overflows when converted to milliseconds, got "
                            + DurationUtils.format(duration),
                    e);
        }
    }

    private static List<Map<String, Object>> traceLogTargetConfig(Object value) {
        return TraceLogTarget.parseTargets(value).stream()
                .map(TraceLogTarget::toConfig)
                .collect(Collectors.toList());
    }
}
