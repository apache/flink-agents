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
package org.apache.flink.agents.plan.resourceprovider;

import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Tests for {@link JavaSerializableResourceProvider}. */
class JavaSerializableResourceProviderTest {

    @Test
    void provideBindsResourceContextToRegisteredInstance() throws Exception {
        Prompt prompt = Prompt.fromText("Hello {name}");
        JavaSerializableResourceProvider provider =
                JavaSerializableResourceProvider.createResourceProvider(
                        "greeting", ResourceType.PROMPT, prompt);
        ResourceContext context = mock(ResourceContext.class);

        Resource provided = provider.provide(context);

        assertThat(provided).isSameAs(prompt);
        assertThat(provided.getResourceContext()).isSameAs(context);
    }

    @Test
    void provideBindsResourceContextToDeserializedInstance() throws Exception {
        JavaSerializableResourceProvider registered =
                JavaSerializableResourceProvider.createResourceProvider(
                        "greeting", ResourceType.PROMPT, Prompt.fromText("Hello {name}"));
        JavaSerializableResourceProvider restored =
                new JavaSerializableResourceProvider(
                        registered.getName(),
                        registered.getType(),
                        registered.getModule(),
                        registered.getClazz(),
                        registered.getSerializedResource());
        ResourceContext context = mock(ResourceContext.class);

        Resource provided = restored.provide(context);

        assertThat(provided).isInstanceOf(Prompt.class);
        assertThat(provided.getResourceContext()).isSameAs(context);
    }
}
