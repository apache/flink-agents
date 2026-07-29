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

package org.apache.flink.agents.integrations.chatmodels.ollama;

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link OllamaChatModelSetup}. */
class OllamaChatModelSetupTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    @Test
    void getModel_returnsValueFromDescriptor() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                        .addInitialArgument("connection", "dummy-connection")
                        .addInitialArgument("model", "qwen3:4b")
                        .build();
        OllamaChatModelSetup setup = new OllamaChatModelSetup(desc, NOOP);

        assertThat(setup.getModel()).isEqualTo("qwen3:4b");
    }

    @Test
    void getParameters_includesModelFromDescriptor() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                        .addInitialArgument("connection", "dummy-connection")
                        .addInitialArgument("model", "qwen3:4b")
                        .build();
        OllamaChatModelSetup setup = new OllamaChatModelSetup(desc, NOOP);

        assertThat(setup.getParameters()).containsEntry("model", "qwen3:4b");
    }
}
