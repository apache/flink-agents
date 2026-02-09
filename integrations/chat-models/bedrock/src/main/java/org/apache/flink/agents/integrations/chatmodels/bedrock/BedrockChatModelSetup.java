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
package org.apache.flink.agents.integrations.chatmodels.bedrock;

import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Chat model setup for AWS Bedrock Converse API.
 *
 * <p>Supported parameters:
 * <ul>
 *   <li><b>connection</b> (required): name of the BedrockChatModelConnection resource</li>
 *   <li><b>model</b> (required): Bedrock model ID (e.g. us.anthropic.claude-sonnet-4-20250514-v1:0)</li>
 *   <li><b>temperature</b> (optional): sampling temperature (default 0.1)</li>
 *   <li><b>prompt</b> (optional): prompt resource name</li>
 *   <li><b>tools</b> (optional): list of tool resource names</li>
 * </ul>
 */
public class BedrockChatModelSetup extends BaseChatModelSetup {

    private final Double temperature;

    public BedrockChatModelSetup(
            ResourceDescriptor descriptor,
            BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.temperature = Optional.ofNullable(descriptor.<Number>getArgument("temperature"))
                .map(Number::doubleValue)
                .orElse(0.1);
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        if (model != null) {
            params.put("model", model);
        }
        params.put("temperature", temperature);
        return params;
    }
}
