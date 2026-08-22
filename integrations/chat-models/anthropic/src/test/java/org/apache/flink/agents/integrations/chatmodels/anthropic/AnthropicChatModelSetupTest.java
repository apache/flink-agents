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

package org.apache.flink.agents.integrations.chatmodels.anthropic;

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link AnthropicChatModelSetup}. */
class AnthropicChatModelSetupTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor.Builder base() {
        return ResourceDescriptor.Builder.newBuilder(AnthropicChatModelSetup.class.getName())
                .addInitialArgument("connection", "conn");
    }

    @Test
    @DisplayName("getParameters applies the documented defaults")
    void testGetParametersDefaults() {
        Map<String, Object> params =
                new AnthropicChatModelSetup(base().build(), NOOP).getParameters();

        assertThat(params).containsEntry("model", "claude-sonnet-4-5");
        assertThat(params).containsEntry("temperature", 0.1d);
        assertThat(params).containsEntry("max_tokens", 1024L);
        assertThat(params).containsEntry("strict_tools", false);
        // json_prefill is opt-in: it steers the model with a technique several models reject
        // outright, so a setup that does not ask for it must not send it.
        assertThat(params).containsEntry("json_prefill", false);
    }

    @Test
    @DisplayName("getParameters honors an explicit json_prefill")
    void testGetParametersHonorsExplicitJsonPrefill() {
        // Pins that the argument is read rather than the default being emitted unconditionally.
        Map<String, Object> params =
                new AnthropicChatModelSetup(
                                base().addInitialArgument("json_prefill", true).build(), NOOP)
                        .getParameters();

        assertThat(params).containsEntry("json_prefill", true);
    }
}
