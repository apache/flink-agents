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

package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.runtime.PythonMCPResourceDiscovery;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionCoordinator;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionLock;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionTask;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.apache.flink.util.function.ThrowingRunnable;
import pemja.core.PythonInterpreter;
import pemja.core.PythonInterpreterConfig;

import javax.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Child-process scenario for {@link PythonResourceWorkerLifetimeE2ETest}. */
public final class PythonResourceWorkerLifetimeScenario {

    static final String SUCCESS_MARKER = "PYTHON_RESOURCE_WORKER_LIFETIME_OK";

    private static final String RESOURCE_NAME = "lifetimeModel";
    private static final String DEPENDENCY_RESOURCE_NAME = "lifetimeDependency";
    private static final long WORKER_IDLE_TIMEOUT_MILLIS = 200L;

    private PythonResourceWorkerLifetimeScenario() {}

    public static void main(String[] args) throws Exception {
        runScenario();
        System.out.println(SUCCESS_MARKER);
    }

    private static void runScenario() throws Exception {
        PythonInterpreterConfig interpreterConfig = createInterpreterConfig();
        PythonInterpreter ownerInterpreter = new PythonInterpreter(interpreterConfig);
        ParallelExecutionLock executionLock = new ParallelExecutionLock();

        try (PythonInterpreterManager interpreterManager =
                new PythonInterpreterManager(
                        ownerInterpreter,
                        () -> new PythonInterpreter(interpreterConfig),
                        1,
                        executionLock::checkReentrant)) {
            Map<ResourceType, Map<String, ResourceProvider>> providers = createProviders();
            ResourceCache resourceCache = new ResourceCache(providers);
            PythonResourceAdapterImpl adapter =
                    new PythonResourceAdapterImpl(
                            resourceCache.getResourceContext(), interpreterManager, null);
            adapter.open();
            PythonMCPResourceDiscovery.discoverPythonMCPResources(
                    providers, adapter, resourceCache);

            try {
                executeWorkerLifetimeScenario(
                        executionLock, interpreterManager, resourceCache, adapter);
            } finally {
                try {
                    resourceCache.close();
                } finally {
                    adapter.close();
                }
            }
        }
    }

    private static void executeWorkerLifetimeScenario(
            ParallelExecutionLock executionLock,
            PythonInterpreterManager interpreterManager,
            ResourceCache resourceCache,
            PythonResourceAdapterImpl adapter)
            throws Exception {
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        Queue<ParallelExecutionTask> tasks = new ConcurrentLinkedQueue<>();
        CountDownLatch residentWorkerStarted = new CountDownLatch(1);
        CountDownLatch releaseResidentWorker = new CountDownLatch(1);
        CountDownLatch resourceCreated = new CountDownLatch(1);
        CountDownLatch resourceReused = new CountDownLatch(1);
        CountDownLatch surplusWorkerCleanedUp = new CountDownLatch(1);

        ParallelExecutionCoordinator coordinator =
                new ParallelExecutionCoordinator(
                        executionLock,
                        (mail, description) -> mails.add(mail),
                        tasks::poll,
                        () -> {
                            interpreterManager.releaseCurrentThreadInterpreter();
                            surplusWorkerCleanedUp.countDown();
                        },
                        2,
                        WORKER_IDLE_TIMEOUT_MILLIS);
        try {
            tasks.add(
                    ScenarioTask.blocking(
                            executionLock, residentWorkerStarted, releaseResidentWorker));
            addTask(executionLock, coordinator, "resident");
            await(residentWorkerStarted, "resident worker did not start");

            tasks.add(
                    ScenarioTask.running(
                            () -> {
                                resourceCache.getResource(RESOURCE_NAME, ResourceType.CHAT_MODEL);
                                resourceCreated.countDown();
                            }));
            addTask(executionLock, coordinator, "creator");
            await(resourceCreated, "surplus worker did not create the Python resource");
            runOneMail(mails);

            await(
                    surplusWorkerCleanedUp,
                    "surplus worker did not retire and release its interpreter");

            tasks.add(
                    ScenarioTask.running(
                            () -> {
                                Resource cached =
                                        resourceCache.getResource(
                                                RESOURCE_NAME, ResourceType.CHAT_MODEL);
                                Object result =
                                        adapter.callMethod(
                                                ((PythonResourceWrapper) cached)
                                                        .getPythonResource(),
                                                "touch",
                                                Map.of());
                                if (!"alive".equals(result)) {
                                    throw new AssertionError(
                                            "Unexpected cached resource result: " + result);
                                }
                                resourceReused.countDown();
                            }));
            addTask(executionLock, coordinator, "reuser");
            await(resourceReused, "replacement worker did not reuse the cached resource");
            runOneMail(mails);

            releaseResidentWorker.countDown();
            runOneMail(mails);
        } finally {
            releaseResidentWorker.countDown();
            coordinator.close();
        }
    }

    private static Map<ResourceType, Map<String, ResourceProvider>> createProviders() {
        ResourceDescriptor descriptor =
                new PythonResourceDescriptor(
                        "resource_lifetime_fixture",
                        "LifetimeChatModelSetup",
                        Map.of("connection", "unused", "model", "unused"));
        PythonResourceProvider provider =
                new PythonResourceProvider(RESOURCE_NAME, ResourceType.CHAT_MODEL, descriptor);
        ResourceDescriptor dependencyDescriptor =
                new PythonResourceDescriptor(
                        "resource_lifetime_fixture",
                        "LifetimeDependencySetup",
                        Map.of("connection", "unused", "model", "unused"));
        PythonResourceProvider dependencyProvider =
                new PythonResourceProvider(
                        DEPENDENCY_RESOURCE_NAME, ResourceType.CHAT_MODEL, dependencyDescriptor);
        Map<ResourceType, Map<String, ResourceProvider>> providers = new HashMap<>();
        providers.put(
                ResourceType.CHAT_MODEL,
                Map.of(RESOURCE_NAME, provider, DEPENDENCY_RESOURCE_NAME, dependencyProvider));
        return providers;
    }

    private static PythonInterpreterConfig createInterpreterConfig() throws Exception {
        URL fixtureDirectory =
                PythonResourceWorkerLifetimeScenario.class.getClassLoader().getResource("python");
        if (fixtureDirectory == null) {
            throw new IllegalStateException("Python test fixture directory was not found.");
        }

        List<String> pythonPaths = new ArrayList<>();
        pythonPaths.add(Paths.get(fixtureDirectory.toURI()).toString());
        String inheritedPythonPath = System.getenv("PYTHONPATH");
        if (inheritedPythonPath != null && !inheritedPythonPath.isBlank()) {
            pythonPaths.add(inheritedPythonPath);
        }

        return PythonInterpreterConfig.newBuilder()
                .setExcType(PythonInterpreterConfig.ExecType.MULTI_THREAD)
                .setPythonExec(findPythonExecutable())
                .addPythonPaths(pythonPaths.toArray(new String[0]))
                .build();
    }

    private static String findPythonExecutable() throws Exception {
        String virtualEnvironment = System.getenv("VIRTUAL_ENV");
        if (virtualEnvironment != null) {
            Path candidate = Path.of(virtualEnvironment, "bin", "python");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }

        Process process =
                new ProcessBuilder("python3", "-c", "import sys; print(sys.executable)")
                        .redirectErrorStream(true)
                        .start();
        String executable;
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            executable = reader.readLine();
        }
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("Unable to resolve the active Python executable.");
        }
        if (executable == null || executable.isBlank()) {
            throw new IllegalStateException("Python executable lookup returned no path.");
        }
        return executable.trim();
    }

    private static void addTask(
            ParallelExecutionLock executionLock,
            ParallelExecutionCoordinator coordinator,
            Object key)
            throws InterruptedException {
        executionLock.acquireByMain();
        try {
            coordinator.addTask(key);
        } finally {
            executionLock.release();
        }
    }

    private static void runOneMail(BlockingQueue<ThrowingRunnable<? extends Exception>> mails)
            throws Exception {
        ThrowingRunnable<? extends Exception> mail = mails.poll(10, TimeUnit.SECONDS);
        if (mail == null) {
            throw new AssertionError("Timed out waiting for mailbox completion.");
        }
        mail.run();
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError(message);
        }
    }

    private static final class ScenarioTask implements ParallelExecutionTask {
        private final ThrowingRunnable<? extends Exception> action;
        private final ParallelExecutionLock executionLock;
        private final CountDownLatch started;
        private final CountDownLatch release;

        private long recordIndex;
        private long taskIndex;
        private volatile boolean done;
        private Throwable failure;

        private ScenarioTask(ThrowingRunnable<? extends Exception> action) {
            this(action, null, null, null);
        }

        private ScenarioTask(
                ThrowingRunnable<? extends Exception> action,
                ParallelExecutionLock executionLock,
                CountDownLatch started,
                CountDownLatch release) {
            this.action = action;
            this.executionLock = executionLock;
            this.started = started;
            this.release = release;
        }

        private static ScenarioTask running(ThrowingRunnable<? extends Exception> action) {
            return new ScenarioTask(action);
        }

        private static ScenarioTask blocking(
                ParallelExecutionLock executionLock,
                CountDownLatch started,
                CountDownLatch release) {
            return new ScenarioTask(() -> {}, executionLock, started, release);
        }

        @Override
        public void setup(Object key, long recordIndex, long taskIndex) {
            this.recordIndex = recordIndex;
            this.taskIndex = taskIndex;
        }

        @Override
        public void restoreContext() {}

        @Override
        public void execute() {
            try {
                if (executionLock == null) {
                    action.run();
                } else {
                    started.countDown();
                    executionLock.release();
                    try {
                        await(release, "resident worker was not released");
                    } finally {
                        executionLock.acquireByWorker(recordIndex, taskIndex);
                    }
                }
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                done = true;
            }
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public void commit() throws Exception {
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            if (failure != null) {
                throw new RuntimeException(failure);
            }
        }

        @Override
        public void finishGroup(@Nullable ParallelExecutionTask lastCommitted) {}
    }
}
