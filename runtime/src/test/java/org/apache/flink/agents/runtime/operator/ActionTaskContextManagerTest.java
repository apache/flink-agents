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
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.api.common.state.MapState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

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
                                            /* pythonRunnerContext */ null,
                                            /* longTermMemory */ null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PythonRunnerContextImpl has not been initialized");
        }
    }

    @Test
    void createAndSetRunnerContextBuildsFreshMemoryContextOnFirstCall() throws Exception {
        try (ActionTaskContextManager mgr = new ActionTaskContextManager(1)) {
            ActionTask t = new JavaActionTask("k", new InputEvent(1L), TestActions.noopAction());
            invokeCreateAndSetRunnerContext(mgr, t);

            // Production path: createAndSetRunnerContext at ActionTaskContextManager.java:210-218
            // — the else branch builds a fresh MemoryContext when the map has no entry.
            assertThat(t.getRunnerContext()).isInstanceOf(JavaRunnerContextImpl.class);
            assertThat(t.getRunnerContext().getMemoryContext()).isNotNull();
        }
    }

    @Test
    void createAndSetRunnerContextReusesExistingMemoryContext() throws Exception {
        try (ActionTaskContextManager mgr = new ActionTaskContextManager(1)) {
            Action action = TestActions.noopAction();
            ActionTask from = new JavaActionTask("k", new InputEvent(1L), action);
            ActionTask to = new JavaActionTask("k", new InputEvent(2L), action);

            // Step 1: createAndSetRunnerContext(from) — runner context now carries a fresh
            // MemoryContext, but the map (actionTaskMemoryContexts) is still empty (production
            // code at lines 210-218 only reads from the map, never writes).
            invokeCreateAndSetRunnerContext(mgr, from);
            RunnerContextImpl.MemoryContext fromMemCtx = from.getRunnerContext().getMemoryContext();
            assertThat(fromMemCtx).isNotNull();

            // Step 2: transferContexts populates the map entry for `to` via the private
            // putMemoryContext (ActionTaskContextManager.java:266-286). DEM null is OK because
            // from has no DurableExecutionContext.
            mgr.transferContexts(from, to, new DurableExecutionManager(null));

            // Step 3: createAndSetRunnerContext(to) — production code at lines 211-212 reads
            // the map for `to` and reuses fromMemCtx (the if-branch of the reuse check).
            invokeCreateAndSetRunnerContext(mgr, to);

            // The runner context is shared (single Java JavaRunnerContextImpl instance), but
            // its memoryContext was switched to the entry that was in the map for `to`. Verify
            // the same MemoryContext instance is now wired on the runner context.
            assertThat(to.getRunnerContext().getMemoryContext()).isSameAs(fromMemCtx);
        }
    }

    @Test
    void transferContextsCopiesMemoryAndContinuationToNewTask() throws Exception {
        try (ActionTaskContextManager mgr = new ActionTaskContextManager(1)) {
            Action action = TestActions.noopAction();
            ActionTask from = new JavaActionTask("k", new InputEvent(1L), action);
            ActionTask to = new JavaActionTask("k", new InputEvent(2L), action);

            // Populate `from`'s runner context with a MemoryContext and ContinuationContext.
            invokeCreateAndSetRunnerContext(mgr, from);
            RunnerContextImpl.MemoryContext fromMemCtx = from.getRunnerContext().getMemoryContext();
            assertThat(fromMemCtx).isNotNull();

            // transferContexts (ActionTaskContextManager.java:266-286) copies but does NOT
            // remove from source. The from-side continuation map is never populated (the
            // continuation lives on from's runner context until transfer copies it over for
            // `to`). Operator-side cleanup of `from`'s entries is the operator's
            // responsibility — see ActionExecutionOperator.java:366-369.
            mgr.transferContexts(from, to, new DurableExecutionManager(null));

            // (a) The memory context entry for `to` is the same instance fromTask holds.
            RunnerContextImpl.MemoryContext toMemCtx = mgr.removeMemoryContext(to);
            assertThat(toMemCtx).isSameAs(fromMemCtx);

            // After remove, the map no longer has `to`'s entry.
            assertThat(mgr.removeMemoryContext(to)).isNull();

            // (b) Continuation context routed to `to`.
            assertThat(mgr.hasContinuationContext(to)).isTrue();

            // (c) The `from`-side continuation map entry was never populated by the transfer
            // — the source carries its continuation on its runner context, not on the
            // manager's map.
            assertThat(mgr.hasContinuationContext(from)).isFalse();
        }
    }

    @Test
    void transferContextsRoutesDurableContextThroughManager() throws Exception {
        try (ActionTaskContextManager mgr = new ActionTaskContextManager(1)) {
            Action action = TestActions.noopAction();
            InputEvent event = new InputEvent(1L);
            ActionTask from = new JavaActionTask("k", event, action);
            ActionTask to = new JavaActionTask("k", new InputEvent(2L), action);

            invokeCreateAndSetRunnerContext(mgr, from);

            // Spy on DEM backed by a real InMemoryActionStateStore so spied internals don't
            // NPE. The store doesn't really need to be exercised — we only verify the
            // putDurableContext call site at ActionTaskContextManager.java:271-273.
            DurableExecutionManager spyDem =
                    spy(new DurableExecutionManager(new InMemoryActionStateStore(false)));

            // Attach a DurableExecutionContext to `from`'s runner context. The persister is
            // the DEM itself (DurableExecutionManager implements ActionStatePersister at
            // DurableExecutionManager.java:78). ActionState ctor needs an Event so getCallResults()
            // returns a non-null list inside the DurableExecutionContext ctor.
            ActionState actionState = new ActionState(event);
            RunnerContextImpl.DurableExecutionContext durableCtx =
                    new RunnerContextImpl.DurableExecutionContext(
                            "k", 0L, action, event, actionState, spyDem);
            from.getRunnerContext().setDurableExecutionContext(durableCtx);

            mgr.transferContexts(from, to, spyDem);

            // The durable-context branch routes via the DEM's putDurableContext, satisfying
            // the no-manager-to-manager-references design constraint (DEM passed as a
            // parameter, not held as a field).
            verify(spyDem)
                    .putDurableContext(
                            eq(to), any(RunnerContextImpl.DurableExecutionContext.class));
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        // Not using try-with-resources here because we want to call close() explicitly twice.
        ActionTaskContextManager mgr = new ActionTaskContextManager(1);
        ActionTask t = new JavaActionTask("k", new InputEvent(1L), TestActions.noopAction());
        invokeCreateAndSetRunnerContext(mgr, t);

        // First close() shuts down the runner context and the continuation executor
        // (ActionTaskContextManager.java:319-330). The second close() must be a no-op:
        // runnerContext is nulled and ContinuationActionExecutor.close() is backed by
        // ExecutorService.shutdownNow() which is itself idempotent.
        mgr.close();
        mgr.close();
    }

    /**
     * Shared helper: install a runner context on {@code task} using mocked collaborators. Used by
     * tests that need a fully wired runner context but do not care about the collaborator details.
     */
    @SuppressWarnings("unchecked")
    private static void invokeCreateAndSetRunnerContext(
            ActionTaskContextManager mgr, ActionTask task) {
        AgentPlan plan = newEmptyAgentPlan();
        ResourceCache cache = mock(ResourceCache.class);
        FlinkAgentsMetricGroupImpl metricGroup =
                mock(FlinkAgentsMetricGroupImpl.class, RETURNS_DEEP_STUBS);
        MapState<String, MemoryObjectImpl.MemoryItem> sensoryMem = mock(MapState.class);
        MapState<String, MemoryObjectImpl.MemoryItem> shortTermMem = mock(MapState.class);
        mgr.createAndSetRunnerContext(
                task,
                "k",
                "k",
                plan,
                cache,
                metricGroup,
                "job",
                () -> {},
                sensoryMem,
                shortTermMem,
                /* pythonRunnerContext */ null,
                /* longTermMemory */ null);
    }

    private static AgentPlan newEmptyAgentPlan() {
        return new AgentPlan(new HashMap<>(), new HashMap<>());
    }
}
