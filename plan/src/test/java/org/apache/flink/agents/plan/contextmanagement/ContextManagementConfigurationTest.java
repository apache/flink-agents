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
package org.apache.flink.agents.plan.contextmanagement;

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.contextmanagement.ContextManagementPolicy;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The agent's default context management policy as the agent configuration declares it. */
class ContextManagementConfigurationTest {

    @Test
    void anAgentConfigurationWithoutTheOptionsDeclaresNoPolicy() {
        assertThat(ContextManagementPolicy.fromConfiguration(new AgentConfiguration()))
                .isEqualTo(ContextManagementPolicy.disabled());
    }

    @Test
    void theOptionsAreReadFromTheTextAndNumberFormsAConfigurationFileGives() {
        Map<String, Object> declared = new HashMap<>();
        declared.put("context-management.tool-results.max-chars", "20000");
        declared.put("context-management.tool-results.keep-last-rounds", 3);
        declared.put("context-management.tool-results.mask-step-rounds", "5");
        declared.put("context-management.max-input-tokens", "100000");
        // A whole number is a valid ratio.
        declared.put("context-management.summarization.trigger-ratio", 1);
        declared.put("context-management.summarization.keep-last-rounds", "2");
        declared.put("context-management.summarization.model", "small");

        ContextManagementPolicy policy =
                ContextManagementPolicy.fromConfiguration(new AgentConfiguration(declared));

        assertThat(policy)
                .isEqualTo(
                        ContextManagementPolicy.builder()
                                .toolResultMaxChars(20_000)
                                .toolResultKeepLastRounds(3)
                                .toolResultMaskStepRounds(5)
                                .maxInputTokens(100_000)
                                .summarizationTriggerRatio(1.0)
                                .summarizationKeepLastRounds(2)
                                .summarizer("small")
                                .build());
    }

    @Test
    void theOptionsSetThroughTheirConstantsAreRead() {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 5000);
        config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO, 0.75);
        config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 64_000);

        ContextManagementPolicy policy = ContextManagementPolicy.fromConfiguration(config);

        assertThat(policy.getToolResultMaxChars()).isEqualTo(5000);
        assertThat(policy.getMaxInputTokens()).isEqualTo(64_000);
        assertThat(policy.getSummarizationTriggerRatio()).isEqualTo(0.75);
    }

    @Test
    void aValueOfTheWrongTypeIsReportedByItsConfigurationKey() {
        AgentConfiguration notANumber =
                new AgentConfiguration(Map.of("context-management.max-input-tokens", "many"));
        assertThatThrownBy(() -> ContextManagementPolicy.fromConfiguration(notANumber))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context-management.max-input-tokens must be an integer")
                .hasMessageContaining("many");

        AgentConfiguration notARatio =
                new AgentConfiguration(
                        Map.of(
                                "context-management.max-input-tokens",
                                1000,
                                "context-management.summarization.trigger-ratio",
                                "most"));
        assertThatThrownBy(() -> ContextManagementPolicy.fromConfiguration(notARatio))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "context-management.summarization.trigger-ratio must be a number")
                .hasMessageContaining("most");
    }

    @Test
    void aNumberThatWasNotWrittenAsOneIsRejected() {
        // A boolean is no number and a number with a fraction is no integer, whether the
        // configuration holds the value or its text.
        for (Object written : new Object[] {true, 1.9, "1.9", "true", " 3"}) {
            AgentConfiguration config =
                    new AgentConfiguration(
                            Map.of("context-management.tool-results.keep-last-rounds", written));
            assertThatThrownBy(() -> ContextManagementPolicy.fromConfiguration(config))
                    .as("written as %s", written)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(
                            "context-management.tool-results.keep-last-rounds must be an"
                                    + " integer");
        }
        AgentConfiguration notARatio =
                new AgentConfiguration(
                        Map.of(
                                "context-management.max-input-tokens",
                                1000,
                                "context-management.summarization.trigger-ratio",
                                true));
        assertThatThrownBy(() -> ContextManagementPolicy.fromConfiguration(notARatio))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "context-management.summarization.trigger-ratio must be a number");
    }

    @Test
    void aNumberIsWrittenTheSameWayForJavaAndPython() {
        // Java and Python each parse more texts as a number than a decimal number, and not the
        // same ones: 0.8d is a number in Java only and 0.8_0 in Python only. One syntax decides,
        // so both sides accept and reject the same configuration.
        String ratio = "context-management.summarization.trigger-ratio";
        String budget = "context-management.max-input-tokens";
        Map<Object, Double> ratios = new LinkedHashMap<>();
        for (Object written : new Object[] {"0.8", ".8", "0.80", "+0.8", "8e-1", "8E-1", 0.8}) {
            ratios.put(written, 0.8);
        }
        for (Object written : new Object[] {"1", "1.", 1}) {
            ratios.put(written, 1.0);
        }
        ratios.forEach(
                (written, value) ->
                        assertThat(
                                        ContextManagementPolicy.fromConfiguration(
                                                        new AgentConfiguration(
                                                                Map.of(
                                                                        budget, 1000, ratio,
                                                                        written)))
                                                .getSummarizationTriggerRatio())
                                .as("written as %s", written)
                                .isEqualTo(value));
        for (String written :
                new String[] {
                    "0.8d",
                    "0.8f",
                    "0.8_0",
                    "0x1p-1",
                    " 0.8",
                    "0.8 ",
                    "0,8",
                    "NaN",
                    "nan",
                    "inf",
                    "Infinity",
                    "\u0660.\u0668",
                    ""
                }) {
            AgentConfiguration config =
                    new AgentConfiguration(Map.of(budget, 1000, ratio, written));
            assertThatThrownBy(() -> ContextManagementPolicy.fromConfiguration(config))
                    .as("written as '%s'", written)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ratio + " must be a number");
        }

        for (Object written : new Object[] {"1000", "+1000", 1000}) {
            assertThat(
                            ContextManagementPolicy.fromConfiguration(
                                            new AgentConfiguration(Map.of(budget, written)))
                                    .getMaxInputTokens())
                    .as("written as %s", written)
                    .isEqualTo(1000);
        }
        for (String written :
                new String[] {"\u0661\u0660\u0660\u0660", "1_000", "0x3e8", "1e3", "1000.0", ""}) {
            AgentConfiguration config = new AgentConfiguration(Map.of(budget, written));
            assertThatThrownBy(() -> ContextManagementPolicy.fromConfiguration(config))
                    .as("written as '%s'", written)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(budget + " must be an integer");
        }
    }

    @Test
    void aValueOutOfRangeIsReportedByItsConfigurationKey() {
        AgentConfiguration config =
                new AgentConfiguration(
                        Map.of("context-management.tool-results.keep-last-rounds", "0"));

        assertThatThrownBy(() -> ContextManagementPolicy.fromConfiguration(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "context-management.tool-results.keep-last-rounds must be at least 1")
                .hasMessageContaining("got 0");
    }
}
