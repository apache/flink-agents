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
package org.apache.flink.agents.runtime.utils;

import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.util.List;
import java.util.Map;

/** Model scope for tests that send tool requests directly without invoking a chat model. */
public class ToolCallTestModel extends BaseChatModelSetup {
    public ToolCallTestModel(ResourceDescriptor descriptor, ResourceContext context) {
        super(descriptor, context);
    }

    public static ResourceDescriptor withTools(String... names) {
        return ResourceDescriptor.Builder.newBuilder(ToolCallTestModel.class.getName())
                .addInitialArgument("tools", List.of(names))
                .build();
    }

    @Override
    public void open() {
        // These tests only need the configured callable names, not a model connection.
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of();
    }
}
