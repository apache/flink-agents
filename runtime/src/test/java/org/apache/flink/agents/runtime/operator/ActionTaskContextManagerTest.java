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
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract tests for {@link ActionTaskContextManager}. */
class ActionTaskContextManagerTest {

    @Test
    void perTaskMapsAreIsolatedAcrossPutGetRemove() throws Exception {
        try (ActionTaskContextManager mgr = new ActionTaskContextManager(1)) {
            Action action = TestActions.noopAction();
            ActionTask t1 = new JavaActionTask("k", new InputEvent(1L), action);
            ActionTask t2 = new JavaActionTask("k", new InputEvent(2L), action);

            ContinuationContext c1 = new ContinuationContext();
            mgr.putContinuationContext(t1, c1);
            mgr.putPythonAwaitableRef(t2, "ref-2");

            // Cross-task isolation: each map only carries the entry it was given.
            assertThat(mgr.getContinuationContext(t1)).isSameAs(c1);
            assertThat(mgr.getContinuationContext(t2)).isNull();
            assertThat(mgr.getPythonAwaitableRef(t1)).isNull();
            assertThat(mgr.getPythonAwaitableRef(t2)).isEqualTo("ref-2");
            assertThat(mgr.hasContinuationContext(t1)).isTrue();
            assertThat(mgr.hasContinuationContext(t2)).isFalse();

            // Remove and re-check
            mgr.removeContinuationContext(t1);
            mgr.removePythonAwaitableRef(t2);
            assertThat(mgr.hasContinuationContext(t1)).isFalse();
            assertThat(mgr.getPythonAwaitableRef(t2)).isNull();
        }
    }

    @Test
    void createOrGetRunnerContextThrowsWhenPythonContextRequestedButNull() throws Exception {
        try (ActionTaskContextManager mgr = new ActionTaskContextManager(1)) {
            assertThatThrownBy(
                            () ->
                                    mgr.createOrGetRunnerContext(
                                            /* isJava */ false,
                                            /* agentPlan */ null,
                                            /* resourceCache */ null,
                                            /* metricGroup */ null,
                                            /* jobIdentifier */ "job",
                                            /* mailboxThreadChecker */ () -> {},
                                            /* pythonRunnerContext */ null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PythonRunnerContextImpl has not been initialized");
        }
    }
}
