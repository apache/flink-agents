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
