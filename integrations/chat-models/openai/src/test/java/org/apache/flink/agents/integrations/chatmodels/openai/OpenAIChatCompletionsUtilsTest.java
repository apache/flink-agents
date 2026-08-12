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

package org.apache.flink.agents.integrations.chatmodels.openai;

import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for shared OpenAI connection argument parsing. */
class OpenAIChatCompletionsUtilsTest {

    @Test
    void testMaxRetriesRejectsFractionalBigDecimal() {
        assertThatThrownBy(
                        () ->
                                OpenAIChatCompletionsUtils.parseMaxRetries(
                                        descriptor("max_retries", new BigDecimal("2.0000000000000000000000001"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_retries");
    }

    @Test
    void testPositiveTimeoutBelowNanosecondDoesNotDisableTimeout() {
        assertThat(
                        OpenAIChatCompletionsUtils.parseTimeout(
                                descriptor("timeout", new BigDecimal("0.0000000001"))))
                .isEqualTo(Duration.ofNanos(1));
    }

    @Test
    void testTimeoutRejectsNonFiniteValues() {
        assertThatThrownBy(
                        () ->
                                OpenAIChatCompletionsUtils.parseTimeout(
                                        descriptor("timeout", Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    private static ResourceDescriptor descriptor(String argumentName, Number value) {
        return ResourceDescriptor.Builder.newBuilder(OpenAICompletionsConnection.class.getName())
                .addInitialArgument(argumentName, value)
                .build();
    }
}
