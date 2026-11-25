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
package org.apache.flink.agents.api.chat.contextmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.ConfigOption;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Declaration rules, defaults and the map form of {@link ContextManagementPolicy}. */
class ContextManagementPolicyTest {

    /** A configuration backed by a map of option keys, as the agent configuration is. */
    private static final class MapConfiguration implements ReadableConfiguration {
        private final Map<String, Object> values = new HashMap<>();

        MapConfiguration with(ConfigOption<?> option, Object value) {
            values.put(option.getKey(), value);
            return this;
        }

        @Override
        public Integer getInt(String key, Integer defaultValue) {
            return (Integer) values.getOrDefault(key, defaultValue);
        }

        @Override
        public Long getLong(String key, Long defaultValue) {
            return (Long) values.getOrDefault(key, defaultValue);
        }

        @Override
        public Float getFloat(String key, Float defaultValue) {
            return (Float) values.getOrDefault(key, defaultValue);
        }

        @Override
        public Double getDouble(String key, Double defaultValue) {
            return (Double) values.getOrDefault(key, defaultValue);
        }

        @Override
        public Boolean getBool(String key, Boolean defaultValue) {
            return (Boolean) values.getOrDefault(key, defaultValue);
        }

        @Override
        public String getStr(String key, String defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : value.toString();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(ConfigOption<T> option) {
            return (T) values.getOrDefault(option.getKey(), option.getDefaultValue());
        }
    }

    @Test
    void aPolicyWithNothingSetIsDisabled() {
        ContextManagementPolicy disabled = ContextManagementPolicy.disabled();

        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.toMap()).isEmpty();
        assertThat(ContextManagementPolicy.builder().build()).isEqualTo(disabled);
        assertThat(disabled.getToolResultMaxChars()).isNull();
        assertThat(disabled.getToolResultKeepLastRounds()).isNull();
        assertThat(disabled.getMaxInputTokens()).isNull();
        assertThat(disabled.getSummarizer()).isNull();
    }

    @Test
    void theBuilderKeepsWhatWasSet() {
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder()
                        .toolResultMaxChars(20_000)
                        .toolResultKeepLastRounds(3)
                        .toolResultMaskStepRounds(5)
                        .maxInputTokens(100_000)
                        .summarizationTriggerRatio(0.7)
                        .summarizationKeepLastRounds(2)
                        .summarizer("small")
                        .build();

        assertThat(policy.isEnabled()).isTrue();
        assertThat(policy.getToolResultMaxChars()).isEqualTo(20_000);
        assertThat(policy.getToolResultKeepLastRounds()).isEqualTo(3);
        assertThat(policy.getToolResultMaskStepRounds()).isEqualTo(5);
        assertThat(policy.getMaxInputTokens()).isEqualTo(100_000);
        assertThat(policy.getSummarizationTriggerRatio()).isEqualTo(0.7);
        assertThat(policy.getSummarizationKeepLastRounds()).isEqualTo(2);
        assertThat(policy.getSummarizer()).isEqualTo("small");
    }

    @Test
    void unsetTuningValuesFallBackToTheirDefaults() {
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder()
                        .toolResultKeepLastRounds(3)
                        .maxInputTokens(100_000)
                        .build();

        assertThat(policy.getToolResultMaskStepRounds())
                .isEqualTo(ContextManagementPolicy.DEFAULT_TOOL_RESULT_MASK_STEP_ROUNDS)
                .isEqualTo(1);
        assertThat(policy.getSummarizationTriggerRatio())
                .isEqualTo(ContextManagementPolicy.DEFAULT_SUMMARIZATION_TRIGGER_RATIO)
                .isEqualTo(0.8);
        assertThat(policy.getSummarizationKeepLastRounds())
                .isEqualTo(ContextManagementPolicy.DEFAULT_SUMMARIZATION_KEEP_LAST_ROUNDS)
                .isEqualTo(3);
        // Defaults are not written to the map form: it carries what was declared.
        assertThat(policy.toMap())
                .containsOnlyKeys("tool_result_keep_last_rounds", "max_input_tokens");
    }

    @Test
    void theMapFormRoundTrips() {
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder()
                        .toolResultMaxChars(20_000)
                        .toolResultKeepLastRounds(3)
                        .toolResultMaskStepRounds(5)
                        .maxInputTokens(100_000)
                        .summarizationTriggerRatio(0.7)
                        .summarizationKeepLastRounds(2)
                        .summarizer("small")
                        .build();

        Map<String, Object> map = policy.toMap();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("tool_result_max_chars", 20_000);
        expected.put("tool_result_keep_last_rounds", 3);
        expected.put("tool_result_mask_step_rounds", 5);
        expected.put("max_input_tokens", 100_000);
        expected.put("summarization_trigger_ratio", 0.7);
        expected.put("summarization_keep_last_rounds", 2);
        expected.put("summarizer", "small");
        assertThat(map).isEqualTo(expected);
        assertThat(ContextManagementPolicy.fromMap(map)).isEqualTo(policy);
        assertThat(ContextManagementPolicy.fromMap(map).hashCode()).isEqualTo(policy.hashCode());
    }

    @Test
    void fromMapAcceptsTheNumberTypesJsonProduces() {
        Map<String, Object> map = new HashMap<>();
        map.put("tool_result_max_chars", 20_000L);
        map.put("max_input_tokens", 100_000L);
        map.put("summarization_trigger_ratio", 1);

        ContextManagementPolicy policy = ContextManagementPolicy.fromMap(map);

        assertThat(policy.getToolResultMaxChars()).isEqualTo(20_000);
        assertThat(policy.getMaxInputTokens()).isEqualTo(100_000);
        assertThat(policy.getSummarizationTriggerRatio()).isEqualTo(1.0);
    }

    @Test
    void fromMapIgnoresNullValuesAndRejectsUnknownKeysAndWrongTypes() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("tool_result_max_chars", null);
        assertThat(ContextManagementPolicy.fromMap(withNull))
                .isEqualTo(ContextManagementPolicy.disabled());

        assertThatThrownBy(() -> ContextManagementPolicy.fromMap(Map.of("max_chars", 500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_chars")
                .hasMessageContaining("tool_result_max_chars");
        assertThatThrownBy(
                        () -> ContextManagementPolicy.fromMap(Map.of("max_input_tokens", "many")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_input_tokens")
                .hasMessageContaining("many");
        assertThatThrownBy(
                        () -> ContextManagementPolicy.fromMap(Map.of("tool_result_max_chars", 2.5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool_result_max_chars")
                .hasMessageContaining("2.5");
        assertThatThrownBy(() -> ContextManagementPolicy.fromMap(Map.of("summarizer", 7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summarizer");
    }

    @Test
    void valuesOutOfRangeAreRejectedWithTheNameAndTheValue() {
        assertThatThrownBy(() -> ContextManagementPolicy.builder().toolResultMaxChars(99).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool_result_max_chars")
                .hasMessageContaining("at least 100")
                .hasMessageContaining("99");
        assertThatThrownBy(
                        () -> ContextManagementPolicy.builder().toolResultKeepLastRounds(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool_result_keep_last_rounds")
                .hasMessageContaining("at least 1");
        assertThatThrownBy(
                        () ->
                                ContextManagementPolicy.builder()
                                        .toolResultKeepLastRounds(2)
                                        .toolResultMaskStepRounds(0)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool_result_mask_step_rounds")
                .hasMessageContaining("at least 1");
        assertThatThrownBy(() -> ContextManagementPolicy.builder().maxInputTokens(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_input_tokens")
                .hasMessageContaining("at least 1");
        for (double ratio : new double[] {0.0, -0.5, 1.01, Double.NaN}) {
            assertThatThrownBy(
                            () ->
                                    ContextManagementPolicy.builder()
                                            .maxInputTokens(1000)
                                            .summarizationTriggerRatio(ratio)
                                            .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("summarization_trigger_ratio")
                    .hasMessageContaining("greater than 0 and at most 1");
        }
        assertThatThrownBy(
                        () ->
                                ContextManagementPolicy.builder()
                                        .maxInputTokens(1000)
                                        .summarizationKeepLastRounds(0)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summarization_keep_last_rounds")
                .hasMessageContaining("at least 1");
        // A name of white space only is blank, by the same list of characters as on the Python
        // side.
        for (String blank :
                new String[] {"", " ", "\t\n", "\u00a0", "\u2003", "\u3000", "\u2028", "\u0085 "}) {
            assertThatThrownBy(
                            () ->
                                    ContextManagementPolicy.builder()
                                            .maxInputTokens(1000)
                                            .summarizer(blank)
                                            .build())
                    .as("the name '%s'", blank)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("summarizer")
                    .hasMessageContaining("blank");
        }
        for (String name : new String[] {"small", " small ", "\u200b", "\u001f", "\u2003x"}) {
            assertThat(
                            ContextManagementPolicy.builder()
                                    .maxInputTokens(1000)
                                    .summarizer(name)
                                    .build()
                                    .getSummarizer())
                    .isEqualTo(name);
        }
    }

    @Test
    void aTuningValueWithoutTheSettingItTunesIsRejected() {
        assertThatThrownBy(
                        () -> ContextManagementPolicy.builder().toolResultMaskStepRounds(4).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool_result_mask_step_rounds")
                .hasMessageContaining("tool_result_keep_last_rounds");
        assertThatThrownBy(
                        () ->
                                ContextManagementPolicy.builder()
                                        .summarizationTriggerRatio(0.5)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summarization_trigger_ratio")
                .hasMessageContaining("max_input_tokens");
        assertThatThrownBy(
                        () ->
                                ContextManagementPolicy.builder()
                                        .summarizationKeepLastRounds(2)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summarization_keep_last_rounds")
                .hasMessageContaining("max_input_tokens");
        assertThatThrownBy(() -> ContextManagementPolicy.builder().summarizer("small").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summarizer")
                .hasMessageContaining("max_input_tokens");
    }

    @Test
    void theExecutionOptionsAreUnsetByDefaultAndGiveADisabledPolicy() {
        assertThat(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS.getKey())
                .isEqualTo("context-management.tool-results.max-chars");
        assertThat(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS.getKey())
                .isEqualTo("context-management.tool-results.keep-last-rounds");
        assertThat(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS.getKey())
                .isEqualTo("context-management.tool-results.mask-step-rounds");
        assertThat(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS.getKey())
                .isEqualTo("context-management.max-input-tokens");
        assertThat(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO.getKey())
                .isEqualTo("context-management.summarization.trigger-ratio");
        assertThat(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS.getKey())
                .isEqualTo("context-management.summarization.keep-last-rounds");
        assertThat(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL.getKey())
                .isEqualTo("context-management.summarization.model");
        for (ConfigOption<?> option :
                new ConfigOption<?>[] {
                    AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS,
                    AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                    AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS,
                    AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS,
                    AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO,
                    AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                    AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL
                }) {
            assertThat(option.getDefaultValue()).as(option.getKey()).isNull();
        }

        assertThat(ContextManagementPolicy.fromConfiguration(new MapConfiguration()))
                .isEqualTo(ContextManagementPolicy.disabled());
    }

    @Test
    void fromConfigurationReadsEveryExecutionOption() {
        MapConfiguration config =
                new MapConfiguration()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 5000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS,
                                4)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS,
                                2)
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 64_000)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO,
                                0.9)
                        .with(
                                AgentExecutionOptions
                                        .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                1)
                        .with(
                                AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL,
                                "summarizer-model");

        assertThat(ContextManagementPolicy.fromConfiguration(config))
                .isEqualTo(
                        ContextManagementPolicy.builder()
                                .toolResultMaxChars(5000)
                                .toolResultKeepLastRounds(4)
                                .toolResultMaskStepRounds(2)
                                .maxInputTokens(64_000)
                                .summarizationTriggerRatio(0.9)
                                .summarizationKeepLastRounds(1)
                                .summarizer("summarizer-model")
                                .build());
    }

    @Test
    void anInvalidExecutionOptionIsReportedByItsConfigurationKey() {
        MapConfiguration config =
                new MapConfiguration()
                        .with(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 10);

        assertThatThrownBy(() -> ContextManagementPolicy.fromConfiguration(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context-management.tool-results.max-chars")
                .hasMessageContaining("at least 100")
                .hasMessageContaining("10");

        MapConfiguration orphan =
                new MapConfiguration()
                        .with(
                                AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL,
                                "small");
        assertThatThrownBy(() -> ContextManagementPolicy.fromConfiguration(orphan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context-management.summarization.model")
                .hasMessageContaining("context-management.max-input-tokens");
    }

    @Test
    void jacksonWritesTheMapFormAndReadsItBack() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder()
                        .toolResultMaxChars(2000)
                        .maxInputTokens(8000)
                        .summarizationTriggerRatio(0.75)
                        .build();

        String json = mapper.writeValueAsString(policy);

        assertThat(mapper.readValue(json, Map.class))
                .isEqualTo(
                        Map.of(
                                "tool_result_max_chars",
                                2000,
                                "max_input_tokens",
                                8000,
                                "summarization_trigger_ratio",
                                0.75));
        assertThat(mapper.readValue(json, ContextManagementPolicy.class)).isEqualTo(policy);
    }
}
