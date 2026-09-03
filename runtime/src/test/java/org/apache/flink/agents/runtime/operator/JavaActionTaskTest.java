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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryRef;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.CachedMemoryStore;
import org.apache.flink.agents.runtime.memory.ForTestMemoryMapState;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link JavaActionTask}. */
public class JavaActionTaskTest {

    private static final String ACTION_NAME = "capture-attachment";
    private static final String KEY = "key";
    private static final long SEQUENCE_NUMBER = 0L;

    private static Event invokedEvent;
    private static Object invokedAttachment;

    private ContinuationActionExecutor continuationExecutor;
    private DurableExecutionManager durableExecutionManager;
    private JavaRunnerContextImpl runnerContext;
    private Action action;
    private TypeSerializer<Event> eventSerializer;

    @BeforeEach
    void setUp() throws Exception {
        invokedEvent = null;
        invokedAttachment = null;
        continuationExecutor = new ContinuationActionExecutor(1);
        durableExecutionManager = new DurableExecutionManager(new InMemoryActionStateStore(false));
        runnerContext = createContext();
        action = createAction();
        eventSerializer =
                TypeInformation.of(Event.class)
                        .createSerializer(new ExecutionConfig().getSerializerConfig());
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            continuationExecutor.close();
        } finally {
            durableExecutionManager.close();
        }
    }

    public static void captureAttachment(InputEvent event, RunnerContext context) throws Exception {
        invokedEvent = event;
        invokedAttachment = event.getAttachment("payload");
        event.setAttachment("payload", "mutated-by-action");
        context.durableExecute(
                new TestDurableCallable<>(
                        "persist-attachment-state", String.class, () -> "persisted"));
    }

    @Test
    void resolvesAttachmentsWithoutMutatingDurableEvent() throws Exception {
        Map<String, Object> payload = Map.of("value", "original");
        MemoryRef reference = runnerContext.getSensoryMemory().set("attachment.payload", payload);
        InputEvent runtimeEvent = new InputEvent(1L);
        runtimeEvent.setAttachment("payload", reference);
        JavaActionTask task = new JavaActionTask(KEY, runtimeEvent, action);
        task.setRunnerContext(runnerContext);
        task.setEventSerializer(eventSerializer);
        durableExecutionManager.maybeInitActionState(KEY, SEQUENCE_NUMBER, action, runtimeEvent);
        ActionState actionState = getPersistedState(runtimeEvent);
        durableExecutionManager.setupDurableExecutionContext(task, actionState, SEQUENCE_NUMBER);

        task.invoke(getClass().getClassLoader(), null);

        ActionState persistedState = getPersistedState(runtimeEvent);
        assertThat(invokedEvent).isInstanceOf(InputEvent.class).isNotSameAs(runtimeEvent);
        assertThat(((InputEvent) invokedEvent).getInput()).isEqualTo(1L);
        assertThat(invokedAttachment).isSameAs(payload);
        assertThat(persistedState.getCallResultCount()).isOne();
        assertThat(persistedState.getTaskEvent().getAttachment("payload")).isSameAs(reference);
    }

    private JavaRunnerContextImpl createContext() throws Exception {
        JavaRunnerContextImpl context =
                new JavaRunnerContextImpl(
                        new FlinkAgentsMetricGroupImpl(
                                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup()),
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "test-job",
                        continuationExecutor);
        context.setContinuationContext(new ContinuationContext());
        context.switchActionContext(
                ACTION_NAME,
                new RunnerContextImpl.MemoryContext(
                        new CachedMemoryStore(new ForTestMemoryMapState<>()),
                        new CachedMemoryStore(new ForTestMemoryMapState<>())),
                KEY,
                "observation",
                false);
        return context;
    }

    private Action createAction() throws Exception {
        return new Action(
                ACTION_NAME,
                new JavaFunction(
                        JavaActionTaskTest.class,
                        "captureAttachment",
                        new Class<?>[] {InputEvent.class, RunnerContext.class}),
                List.of(InputEvent.EVENT_TYPE));
    }

    private ActionState getPersistedState(Event event) throws Exception {
        return durableExecutionManager.maybeGetActionState(KEY, SEQUENCE_NUMBER, action, event);
    }

    private static final class TestDurableCallable<T> implements DurableCallable<T> {

        private final String id;
        private final Class<T> resultClass;
        private final Callable<T> callSupplier;

        private TestDurableCallable(String id, Class<T> resultClass, Callable<T> callSupplier) {
            this.id = id;
            this.resultClass = resultClass;
            this.callSupplier = callSupplier;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Class<T> getResultClass() {
            return resultClass;
        }

        @Override
        public T call() throws Exception {
            return callSupplier.call();
        }
    }
}
