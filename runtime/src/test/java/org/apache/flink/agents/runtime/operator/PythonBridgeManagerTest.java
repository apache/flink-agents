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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract tests for {@link PythonBridgeManager}. */
class PythonBridgeManagerTest {

    @Test
    void openIsNoOpWhenPlanHasNeitherPythonActionsNorResources() throws Exception {
        // Java-only plan: one Java action, no resources.
        Action javaAction = TestActions.noopAction();
        Map<String, Action> actions = Map.of(javaAction.getName(), javaAction);
        Map<String, List<Action>> byEvent = Map.of(InputEvent.class.getName(), List.of(javaAction));
        AgentPlan plan = new AgentPlan(actions, byEvent);

        try (PythonBridgeManager bridge = new PythonBridgeManager()) {
            bridge.open(
                    plan,
                    /* resourceCache */ null,
                    new ExecutionConfig(),
                    /* distributedCache */ null,
                    /* tmpDirs */ new String[] {System.getProperty("java.io.tmpdir")},
                    /* jobId */ new JobID(),
                    /* metricGroup */ null,
                    /* mailboxThreadChecker */ () -> {},
                    /* jobIdentifier */ "job-1");

            // No-op contract: nothing initialized, no Pemja interpreter created.
            assertThat(bridge.isInitialized()).isFalse();
            assertThat(bridge.getPythonActionExecutor()).isNull();
            assertThat(bridge.getPythonRunnerContext()).isNull();
        }
    }
}
