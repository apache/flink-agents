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

package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.plan.AgentPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fail-fast guard of {@link InternalSubagentSetup}: resolving an internal call without stackful
 * suspension would block the mailbox and deadlock, so it fails immediately instead.
 *
 * <p>The guard only trips when continuations are unavailable, which in this build means JDK 20 or
 * below; on JDK 21+ the multi-release executor provides continuations, so the class is disabled
 * there via {@code @EnabledForJreRange}.
 */
public class InternalSubagentGuardTest {

    @Test
    @EnabledForJreRange(max = JRE.JAVA_20)
    void resolvingWithoutContinuationsFailsFast() throws Exception {
        InternalSubagentSetup setup =
                new InternalSubagentSetup("scope", new AgentPlan(new Agent()));

        assertThatThrownBy(() -> setup.prepare(null, "prompt", "session", "call"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stackful suspension");
    }
}
