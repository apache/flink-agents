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

package org.apache.flink.agents.api.chat.model.routing;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * Deployable description of a {@link RoutingStrategy}: the fully-qualified class name plus its
 * construction arguments. Carried in the router's {@code ResourceDescriptor} so the strategy is
 * plan-serializable and reconstructed on the TaskManagers by name — not shipped as a live closure.
 *
 * <p>Built-ins are produced by the {@link Strategies} factory (which fills in the class name);
 * there is no magic-string strategy keyword.
 */
public final class RoutingStrategyDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String clazz;
    private final Map<String, Object> arguments;

    public RoutingStrategyDescriptor(String clazz, Map<String, Object> arguments) {
        if (clazz == null || clazz.isEmpty()) {
            throw new IllegalArgumentException("Strategy class must be non-null and non-empty.");
        }
        this.clazz = clazz;
        this.arguments = arguments == null ? Collections.emptyMap() : arguments;
    }

    public String getClazz() {
        return clazz;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }
}
