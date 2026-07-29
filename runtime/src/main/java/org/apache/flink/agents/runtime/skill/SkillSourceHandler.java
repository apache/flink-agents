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

package org.apache.flink.agents.runtime.skill;

import java.io.IOException;
import java.util.Map;

/**
 * Opens a {@link SkillRepository} from a scheme-specific {@code params} map. Implementations are
 * registered in {@link SkillSourceRegistry} keyed by scheme.
 *
 * <p>{@code classLoader} is the Flink user-code class loader threaded down from the operator; the
 * {@code classpath} handler uses it to resolve resources. Other handlers ignore it. Threading the
 * class loader explicitly avoids depending on {@link Thread#getContextClassLoader} at the moment
 * {@link SkillManager} is lazily constructed — that context can be the Python interpreter thread's
 * CL or an async-pool CL depending on which action triggered the lookup first.
 */
@FunctionalInterface
public interface SkillSourceHandler {

    SkillRepository open(Map<String, String> params, ClassLoader classLoader) throws IOException;

    /**
     * Human-readable description of the source location, embedded in {@link SkillOrigin} for
     * logging and duplicate-name diagnostics. Default returns the raw {@code params} — handlers
     * registered with a custom describer via {@link SkillSourceRegistry#register(String,
     * SkillSourceHandler, java.util.function.Function)} narrow it to the relevant param (e.g.
     * {@code path} for local sources).
     */
    default String describeLocation(Map<String, String> params) {
        return params.toString();
    }
}
