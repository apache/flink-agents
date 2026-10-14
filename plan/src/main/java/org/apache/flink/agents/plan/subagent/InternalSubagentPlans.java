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

package org.apache.flink.agents.plan.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.InternalSubagentResourceDescriptorBuilder;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Compilation and traversal of child plans carried by ordinary resource descriptors. */
public final class InternalSubagentPlans {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InternalSubagentPlans() {}

    public static boolean isInternal(ResourceDescriptor descriptor) {
        return "java".equals(descriptor.getLanguage())
                && InternalSubagentResourceDescriptorBuilder.SETUP_CLASS.equals(
                        descriptor.getClazz());
    }

    public static ResourceDescriptor compile(
            String name,
            ResourceDescriptor descriptor,
            InternalSubagentCompilationHelper.Compiler compiler)
            throws Exception {
        if (!isInternal(descriptor)) {
            return descriptor;
        }
        Map<String, Object> args =
                descriptor.getInitialArguments() == null
                        ? new HashMap<>()
                        : new HashMap<>(descriptor.getInitialArguments());
        Object agent = args.remove(InternalSubagentResourceDescriptorBuilder.AGENT);
        if (!(agent instanceof Agent)
                || args.containsKey(InternalSubagentResourceDescriptorBuilder.PLAN)) {
            throw new IllegalArgumentException(
                    "Internal sub-agent '"
                            + name
                            + "' requires an Agent declaration, not a compiled plan");
        }
        AgentPlan child =
                InternalSubagentCompilationHelper.getOrCompile((Agent) agent, name, compiler);
        args.put(
                InternalSubagentResourceDescriptorBuilder.PLAN,
                MAPPER.convertValue(child, Map.class));
        args.put(InternalSubagentResourceDescriptorBuilder.SCOPE, name);
        return new ResourceDescriptor(InternalSubagentResourceDescriptorBuilder.SETUP_CLASS, args);
    }

    public static AgentPlan decode(ResourceDescriptor descriptor) {
        Object data = descriptor.getArgument(InternalSubagentResourceDescriptorBuilder.PLAN);
        if (!(data instanceof Map)) {
            throw new IllegalArgumentException(
                    "Internal sub-agent requires a compiled plan object");
        }
        return MAPPER.convertValue(data, AgentPlan.class);
    }

    public static Optional<AgentPlan> plan(ResourceProvider provider) {
        if (provider.getType() == ResourceType.AGENT && provider instanceof JavaResourceProvider) {
            ResourceDescriptor descriptor = ((JavaResourceProvider) provider).getDescriptor();
            if (isInternal(descriptor)) {
                return Optional.of(decode(descriptor));
            }
        }
        return Optional.empty();
    }
}
