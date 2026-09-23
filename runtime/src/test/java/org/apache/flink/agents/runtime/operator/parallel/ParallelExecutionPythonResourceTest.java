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
package org.apache.flink.agents.runtime.operator.parallel;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.python.PythonVectorStore;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.runtime.PythonMCPResourceDiscovery;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.apache.flink.util.function.ThrowingRunnable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pemja.core.PythonInterpreter;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression tests for Python resource use on parallel action workers. */
class ParallelExecutionPythonResourceTest {

    private static final Object KEY = "key";
    private static final String CREATE_RESOURCE = "python_java_utils.create_resource";
    private static final String FROM_JAVA_RESOURCE = "python_java_utils.from_java_resource";

    @Test
    @Timeout(15)
    void pythonObjectsReturnedToJavaAreConsumedAndClosedOnTheirOwningWorker() throws Exception {
        PythonInterpreter ownerInterpreter = mock(PythonInterpreter.class);
        PythonInterpreter actionInterpreter = mock(PythonInterpreter.class);
        PyObject vectorStoreHandle = mock(PyObject.class);
        PyObject pythonDocument = mock(PyObject.class);
        AtomicReference<Thread> resultThread = new AtomicReference<>();
        AtomicReference<Thread> accessThread = new AtomicReference<>();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        AtomicReference<Thread> interpreterCloseThread = new AtomicReference<>();

        doAnswer(
                        invocation -> {
                            resultThread.set(Thread.currentThread());
                            return List.of(pythonDocument);
                        })
                .when(actionInterpreter)
                .invoke("python_java_utils.call_method", vectorStoreHandle, "get", Map.of());
        when(pythonDocument.getAttr("content"))
                .thenAnswer(
                        invocation -> {
                            accessThread.compareAndSet(null, Thread.currentThread());
                            return "content";
                        });
        when(pythonDocument.getAttr("metadata", Map.class))
                .thenAnswer(
                        invocation -> {
                            accessThread.compareAndSet(null, Thread.currentThread());
                            return Map.of("source", "test");
                        });
        when(pythonDocument.getAttr("id"))
                .thenAnswer(
                        invocation -> {
                            accessThread.compareAndSet(null, Thread.currentThread());
                            return "doc-1";
                        });
        doAnswer(
                        invocation -> {
                            closeThread.set(Thread.currentThread());
                            return null;
                        })
                .when(pythonDocument)
                .close();
        doAnswer(
                        invocation -> {
                            interpreterCloseThread.set(Thread.currentThread());
                            return null;
                        })
                .when(actionInterpreter)
                .close();

        try (PythonInterpreterManager interpreterManager =
                new PythonInterpreterManager(ownerInterpreter, () -> actionInterpreter, 1)) {
            PythonResourceAdapterImpl adapter =
                    new PythonResourceAdapterImpl(
                            mock(ResourceContext.class), interpreterManager, null);
            PythonVectorStore vectorStore =
                    new PythonVectorStore(
                            adapter,
                            vectorStoreHandle,
                            new ResourceDescriptor("test.module", "TestVectorStore", Map.of()),
                            mock(ResourceContext.class));

            runOnParallelWorker(
                    () -> vectorStore.get(null, null, null, null, Map.of()),
                    interpreterManager::releaseCurrentThreadInterpreter);

            assertThat(resultThread.get()).isNotNull();
            assertThat(accessThread.get())
                    .as("PyObject attributes must be read on the interpreter-owning worker")
                    .isSameAs(resultThread.get());
            assertThat(closeThread.get())
                    .as("PyObject references must be closed on the interpreter-owning worker")
                    .isSameAs(resultThread.get());
            assertThat(interpreterCloseThread.get())
                    .as("worker interpreter must be closed before the manager")
                    .isSameAs(resultThread.get());
        }
    }

    @Test
    @Timeout(15)
    void pythonResourceOpenCanReenterResourceCacheOnParallelWorker() throws Exception {
        PythonInterpreter ownerInterpreter = mock(PythonInterpreter.class);
        PythonInterpreter actionInterpreter = mock(PythonInterpreter.class);
        PyObject modelHandle = mock(PyObject.class);
        PythonResourceProvider modelProvider =
                new PythonResourceProvider(
                        "model",
                        ResourceType.CHAT_MODEL,
                        new ResourceDescriptor("test.module", "TestChatModel", Map.of()));
        Resource connection =
                new Resource() {
                    @Override
                    public ResourceType getResourceType() {
                        return ResourceType.CHAT_MODEL_CONNECTION;
                    }
                };
        ResourceProvider connectionProvider =
                new ResourceProvider("connection", ResourceType.CHAT_MODEL_CONNECTION) {
                    @Override
                    public Resource provide(ResourceContext resourceContext) {
                        return connection;
                    }
                };
        Map<ResourceType, Map<String, ResourceProvider>> providers = new HashMap<>();
        providers.put(ResourceType.CHAT_MODEL, Map.of("model", modelProvider));
        providers.put(ResourceType.CHAT_MODEL_CONNECTION, Map.of("connection", connectionProvider));
        ResourceCache resourceCache = new ResourceCache(providers);

        try (PythonInterpreterManager interpreterManager =
                new PythonInterpreterManager(ownerInterpreter, () -> actionInterpreter, 1)) {
            PythonResourceAdapterImpl adapter =
                    new PythonResourceAdapterImpl(
                            resourceCache.getResourceContext(), interpreterManager, null);
            PythonMCPResourceDiscovery.discoverPythonMCPResources(
                    providers, adapter, resourceCache);

            Map<String, Object> createArguments = new HashMap<>();
            createArguments.put("resource_context", null);
            when(actionInterpreter.invoke(
                            CREATE_RESOURCE, "test.module", "TestChatModel", createArguments))
                    .thenReturn(modelHandle);
            Map<String, Object> conversionArguments = new HashMap<>();
            conversionArguments.put("j_resource", connection);
            conversionArguments.put("j_resource_adapter", null);
            conversionArguments.put("resource_context", null);
            when(actionInterpreter.invoke(
                            FROM_JAVA_RESOURCE,
                            ResourceType.CHAT_MODEL_CONNECTION.getValue(),
                            conversionArguments))
                    .thenReturn(new Object());
            doAnswer(
                            invocation -> {
                                assertThat(Thread.holdsLock(resourceCache))
                                        .as(
                                                "Python resource open must run on the worker that"
                                                        + " owns the ResourceCache monitor")
                                        .isTrue();
                                assertThat(
                                                adapter.getResource(
                                                        "connection",
                                                        ResourceType.CHAT_MODEL_CONNECTION
                                                                .getValue()))
                                        .isNotNull();
                                return null;
                            })
                    .when(actionInterpreter)
                    .invoke("python_java_utils.call_method", modelHandle, "open", Map.of());

            runOnParallelWorker(
                    () -> resourceCache.getResource("model", ResourceType.CHAT_MODEL),
                    interpreterManager::releaseCurrentThreadInterpreter);
        }
    }

    private static void runOnParallelWorker(
            ThrowingRunnable<? extends Exception> action, Runnable threadCleanup) throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        Queue<ParallelExecutionTask> works = new ConcurrentLinkedQueue<>();
        works.add(new TestTask(action));

        try (ParallelExecutionCoordinator coordinator =
                new ParallelExecutionCoordinator(
                        lock,
                        (mail, description) -> mails.add(mail),
                        works::poll,
                        threadCleanup,
                        1,
                        3_600_000L)) {
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();

            ThrowingRunnable<? extends Exception> mail = mails.poll(5, TimeUnit.SECONDS);
            assertThat(mail).isNotNull();
            mail.run();
        }
    }

    private static final class TestTask implements ParallelExecutionTask {
        private final ThrowingRunnable<? extends Exception> action;
        private volatile boolean done;

        private TestTask(ThrowingRunnable<? extends Exception> action) {
            this.action = action;
        }

        @Override
        public void setup(Object key, long recordIndex, long taskIndex) {}

        @Override
        public void restoreContext() {}

        @Override
        public void execute() {
            try {
                action.run();
                done = true;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public void commit() {}

        @Override
        public void finishGroup(@Nullable ParallelExecutionTask lastCommitted) {}
    }
}
