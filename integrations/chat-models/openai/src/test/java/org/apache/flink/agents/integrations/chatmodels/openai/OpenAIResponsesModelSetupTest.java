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

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link OpenAIResponsesModelSetup}. */
class OpenAIResponsesModelSetupTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor.Builder base() {
        return ResourceDescriptor.Builder.newBuilder(OpenAIResponsesModelSetup.class.getName())
                .addInitialArgument("connection", "conn");
    }

    @Test
    @DisplayName("Default model applies when the model argument is omitted")
    void testDefaultModel() {
        OpenAIResponsesModelSetup setup = new OpenAIResponsesModelSetup(base().build(), NOOP);

        assertThat(setup.getParameters()).containsEntry("model", "gpt-4o");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Constructor rejects a blank model instead of defaulting it")
    void testBlankModelRejected(String model) {
        ResourceDescriptor desc = base().addInitialArgument("model", model).build();

        assertThatThrownBy(() -> new OpenAIResponsesModelSetup(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chat model setup model must not be blank.");
    }
}
