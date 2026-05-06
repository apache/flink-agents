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

package org.apache.flink.agents.runtime.resource;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Default {@link ResourceContext} implementation that delegates resource lookup to a {@link
 * BiFunction} (typically the underlying {@code ResourceCache::getResource}).
 *
 * <p>Mirrors the Python {@code flink_agents.runtime.resource_context.ResourceContextImpl}. Skill
 * methods return safe defaults; callers without skills configured see empty values.
 */
public class ResourceContextImpl implements ResourceContext {

    private final BiFunction<String, ResourceType, Resource> getResource;

    public ResourceContextImpl(BiFunction<String, ResourceType, Resource> getResource) {
        this.getResource = getResource;
    }

    @Override
    public Resource getResource(String name, ResourceType type) throws Exception {
        try {
            return getResource.apply(name, type);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    @Override
    public String generateAvailableSkillsPrompt(List<String> skillNames) throws Exception {
        return "";
    }

    @Override
    public List<String> getSkillDirs(List<String> skillNames) throws Exception {
        return Collections.emptyList();
    }
}
