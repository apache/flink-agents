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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.MediaModelConnection;
import org.apache.flink.agents.api.annotation.MediaModelSetup;
import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.media.AsyncJobModel;
import org.apache.flink.agents.api.media.AsyncJobPolling;
import org.apache.flink.agents.api.media.BaseImageGenerationModelSetup;
import org.apache.flink.agents.api.media.BaseMediaModelConnection;
import org.apache.flink.agents.api.media.BaseSpeechSynthesisModelSetup;
import org.apache.flink.agents.api.media.ImageGenerationRequest;
import org.apache.flink.agents.api.media.MediaJob;
import org.apache.flink.agents.api.media.MediaJobState;
import org.apache.flink.agents.api.media.MediaJobStatus;
import org.apache.flink.agents.api.media.MediaJobTimeoutException;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.media.SpeechSynthesisRequest;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.yaml.YamlLoader;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.resource.python.PythonMediaModelConnection;
import org.apache.flink.agents.plan.subagent.InternalSubagentPlans;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.apache.flink.agents.runtime.PythonMCPResourceDiscovery;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.async.AsyncExecutorThreadFactory;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pemja.core.PythonInterpreter;
import pemja.core.PythonInterpreterConfig;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies cancellation-aware Java polling across real Pemja and durable execution. */
@Timeout(30)
public class MediaModelCancellationBridgeTest {
    private PythonInterpreterManager manager;
    private PythonResourceAdapterImpl adapter;
    private ResourceCache cache;
    private PyObject pyConnection;
    private BaseImageGenerationModelSetup imageSetup;
    private AsyncExecutorThreadFactory threadFactory;

    public static void noop(Event event, RunnerContext ctx) {}

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("python/flink_agents"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        PythonInterpreterConfig config =
                PythonInterpreterConfig.newBuilder()
                        .setExcType(PythonInterpreterConfig.ExecType.MULTI_THREAD)
                        .setPythonExec(System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"))
                        .addPythonPaths(root.resolve("python").toString())
                        .build();
        manager =
                new PythonInterpreterManager(
                        new PythonInterpreter(config), () -> new PythonInterpreter(config));
        Agent agent = new Agent();
        agent.addResource(
                "recordingConnection",
                ResourceType.MEDIA_MODEL_CONNECTION,
                PythonResourceDescriptor.Builder.newBuilder(
                                "flink_agents.runtime.tests.media_cancellation_bridge.RecordingMediaModelConnection")
                        .build());
        agent.addResource(
                "recordingImage",
                ResourceType.MEDIA_MODEL,
                PythonResourceDescriptor.Builder.newBuilder(
                                "flink_agents.runtime.tests.media_cancellation_bridge.RecordingImageGenerationModelSetup")
                        .addInitialArgument("connection", "recordingConnection")
                        .addInitialArgument("model", "img-model")
                        .build());
        AgentPlan plan = new AgentPlan(agent);
        cache = new ResourceCache(plan.getResourceProviders());
        adapter = new PythonResourceAdapterImpl(cache.getResourceContext(), manager, null);
        adapter.open();
        PythonMCPResourceDiscovery.discoverPythonMCPResources(
                plan.getResourceProviders(), adapter, cache);
        PythonMediaModelConnection connection =
                (PythonMediaModelConnection)
                        cache.getResource(
                                "recordingConnection", ResourceType.MEDIA_MODEL_CONNECTION);
        pyConnection = (PyObject) connection.getPythonResource();
        imageSetup =
                (BaseImageGenerationModelSetup)
                        cache.getResource("recordingImage", ResourceType.MEDIA_MODEL);
        threadFactory = new AsyncExecutorThreadFactory(manager::releaseCurrentThreadInterpreter);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (threadFactory != null) {
                threadFactory.awaitThreadExit();
            }
        } finally {
            Thread.interrupted();
            if (cache != null) {
                cache.close();
            }
            if (adapter != null) {
                adapter.close();
            }
            if (manager != null) {
                manager.close();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"wait", "generate"})
    void interruptsPollingPromptlyAcrossPemjaWithoutCancelingOrPersisting(String mode)
            throws Exception {
        adapter.callMethod(pyConnection, "reset", Map.of());
        BaseImageGenerationModelSetup image = imageSetup;
        @SuppressWarnings("unchecked")
        AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> async =
                (AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>>) image;
        MediaJob job =
                "wait".equals(mode) ? async.submit(ImageGenerationRequest.of("a cat")) : null;

        ActionState actionState = new ActionState(new InputEvent("test"));
        AtomicInteger persistCount = new AtomicInteger();
        RunnerContextImpl context = durableContext(actionState, persistCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        AtomicLong elapsedMillis = new AtomicLong();

        Thread worker =
                threadFactory.newThread(
                        () -> {
                            long start = System.nanoTime();
                            try {
                                context.durableExecute(
                                        durableCall(
                                                "media:cancel",
                                                () ->
                                                        "wait".equals(mode)
                                                                ? async.wait(
                                                                        job,
                                                                        Duration.ofSeconds(2),
                                                                        Duration.ofSeconds(5))
                                                                : image.generate(
                                                                        ImageGenerationRequest.of(
                                                                                "a cat"),
                                                                        Duration.ofSeconds(2))));
                            } catch (Throwable t) {
                                failure.set(t);
                                interruptPreserved.set(Thread.interrupted());
                            } finally {
                                elapsedMillis.set(
                                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                            }
                        });
        worker.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (counts().get("fetch").intValue() < 1) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }
        Thread.sleep(50);
        worker.interrupt();
        worker.join(2000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(elapsedMillis.get()).isLessThan(1000L);
        assertThat(failure.get()).isInstanceOf(CancellationException.class);
        assertThat(CancellationUtils.isInterruption(failure.get())).isTrue();
        assertThat(interruptPreserved.get()).isTrue();
        assertThat(persistCount.get()).isZero();
        assertThat(actionState.getCallResultCount()).isZero();
        assertThat(counts().get("submit").intValue()).isEqualTo(1);
        assertThat(counts().get("cancel").intValue()).isZero();
    }

    @Test
    void businessTimeoutRemainsPersistedAcrossPemja() throws Exception {
        adapter.callMethod(pyConnection, "reset", Map.of());
        BaseImageGenerationModelSetup image = imageSetup;
        @SuppressWarnings("unchecked")
        AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> async =
                (AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>>) image;
        MediaJob job = async.submit(ImageGenerationRequest.of("a cat"));

        ActionState actionState = new ActionState(new InputEvent("test"));
        AtomicInteger persistCount = new AtomicInteger();
        RunnerContextImpl context = durableContext(actionState, persistCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker =
                threadFactory.newThread(
                        () -> {
                            try {
                                context.durableExecute(
                                        durableCall(
                                                "media:timeout",
                                                () ->
                                                        async.wait(
                                                                job,
                                                                Duration.ofMillis(60),
                                                                Duration.ofMillis(15))));
                            } catch (Throwable t) {
                                failure.set(t);
                            }
                        });
        worker.start();
        worker.join(5000);
        assertThat(worker.isAlive()).isFalse();

        assertThat(failure.get()).isInstanceOf(MediaJobTimeoutException.class);
        MediaJobTimeoutException timeout = (MediaJobTimeoutException) failure.get();
        assertThat(timeout.getStatus()).isEqualTo(MediaJobStatus.PENDING);
        assertThat(timeout.getTimeout()).isEqualTo(Duration.ofMillis(60));
        assertThat(CancellationUtils.isCancellation(timeout)).isFalse();
        assertThat(persistCount.get()).isEqualTo(1);
        assertThat(actionState.getCallResultCount()).isEqualTo(1);
        assertThat(actionState.getCallResults().get(0).isSuccess()).isFalse();
        assertThat(counts().get("cancel").intValue()).isZero();
    }

    /**
     * Java connection without Python backing, used to verify Python setups fail cleanly on open().
     */
    public static class JavaOnlyMediaConnection extends BaseMediaModelConnection {
        public JavaOnlyMediaConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }
    }

    /** Java connection recording submit, fetch, and cancel counts for setup delegation tests. */
    public static class RecordingJavaMediaConnection extends BaseMediaModelConnection {
        private final String connectionTag;
        private final AtomicInteger submitCount = new AtomicInteger();
        private final AtomicInteger fetchCount = new AtomicInteger();
        private final AtomicInteger cancelCount = new AtomicInteger();
        private volatile String fetchOutcome = "pending";

        public RecordingJavaMediaConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
            Object tag =
                    descriptor.getInitialArguments() != null
                            ? descriptor.getInitialArguments().get("connection_tag")
                            : null;
            this.connectionTag = tag instanceof String ? (String) tag : "recording-java";
        }

        public void setFetchOutcome(String outcome) {
            this.fetchOutcome = outcome;
        }

        public void reset() {
            submitCount.set(0);
            fetchCount.set(0);
            cancelCount.set(0);
            fetchOutcome = "pending";
        }

        public Map<String, Integer> getCounts() {
            return Map.of(
                    "submit", submitCount.get(),
                    "fetch", fetchCount.get(),
                    "cancel", cancelCount.get());
        }

        MediaJob recordSubmit() {
            int id = submitCount.incrementAndGet();
            return new MediaJob("job-" + id, 1L, null);
        }

        MediaJobState<MediaResult<ImageBlock>> recordFetch(MediaJob job) {
            fetchCount.incrementAndGet();
            if ("succeeded".equals(fetchOutcome)) {
                return new MediaJobState<>(
                        job,
                        MediaJobStatus.SUCCEEDED,
                        new MediaResult<>(
                                List.of(
                                        ImageBlock.fromUrl(
                                                "image/png",
                                                "https://example.test/"
                                                        + connectionTag
                                                        + "/"
                                                        + job.getJobId()
                                                        + ".png")),
                                null,
                                null,
                                null,
                                Map.of("connection_tag", connectionTag)),
                        null,
                        null);
            }
            return new MediaJobState<>(job, MediaJobStatus.PENDING, null, null, null);
        }

        void recordCancel() {
            cancelCount.incrementAndGet();
        }
    }

    /** Java image generation setup delegating directly to {@link RecordingJavaMediaConnection}. */
    public static class RecordingJavaImageSetup extends BaseImageGenerationModelSetup
            implements AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> {
        @Nullable private RecordingJavaMediaConnection javaConnection;

        public RecordingJavaImageSetup(
                ResourceDescriptor descriptor, @Nullable ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public void open() throws Exception {
            super.open();
            BaseMediaModelConnection resolved = getConnection();
            if (!(resolved instanceof RecordingJavaMediaConnection)) {
                throw new IllegalStateException(
                        "Expected RecordingJavaMediaConnection, got "
                                + resolved.getClass().getName());
            }
            this.javaConnection = (RecordingJavaMediaConnection) resolved;
        }

        @Override
        public void close() throws Exception {
            this.javaConnection = null;
            super.close();
        }

        private RecordingJavaMediaConnection requireConnection() {
            if (javaConnection == null) {
                throw new IllegalStateException("Setup not opened");
            }
            return javaConnection;
        }

        @Override
        public MediaResult<ImageBlock> generate(
                ImageGenerationRequest request, @Nullable Duration timeout) {
            if (timeout != null) {
                AsyncJobPolling.validatePositiveDuration(timeout, "timeout");
            }
            MediaJob job = submit(request);
            return wait(job, timeout == null ? DEFAULT_WAIT_TIMEOUT : timeout);
        }

        @Override
        public MediaJob submit(ImageGenerationRequest request) {
            return requireConnection().recordSubmit();
        }

        @Override
        public MediaJobState<MediaResult<ImageBlock>> fetch(MediaJob job) {
            return requireConnection().recordFetch(job);
        }

        @Override
        public void cancel(MediaJob job) {
            requireConnection().recordCancel();
        }
    }

    /** Java speech synthesis setup resolving {@link RecordingJavaMediaConnection}. */
    public static class RecordingJavaSpeechSetup extends BaseSpeechSynthesisModelSetup {
        private boolean opened;

        public RecordingJavaSpeechSetup(
                ResourceDescriptor descriptor, @Nullable ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public void open() throws Exception {
            super.open();
            if (!(getConnection() instanceof RecordingJavaMediaConnection)) {
                throw new IllegalStateException("Expected RecordingJavaMediaConnection");
            }
            opened = true;
        }

        @Override
        public void close() throws Exception {
            opened = false;
            super.close();
        }

        @Override
        public MediaResult<AudioBlock> synthesize(
                SpeechSynthesisRequest request, @Nullable Duration timeout) {
            if (!opened) {
                throw new IllegalStateException("Setup not opened");
            }
            return new MediaResult<>(
                    List.of(AudioBlock.fromUrl("audio/wav", "https://example.test/speech.wav")),
                    null,
                    null,
                    null,
                    Map.of("text", request.getText()));
        }
    }

    /** Agent declaring both Java and Python MEDIA_MODEL setups bound to their own connections. */
    public static class CrossLanguageSetupsAgent extends Agent {
        @MediaModelConnection
        public static ResourceDescriptor javaConnection() {
            return ResourceDescriptor.Builder.newBuilder(
                            RecordingJavaMediaConnection.class.getName())
                    .build();
        }

        @MediaModelConnection
        public static ResourceDescriptor recordingConnection() {
            return PythonResourceDescriptor.Builder.newBuilder(
                            "flink_agents.runtime.tests.media_cancellation_bridge.RecordingMediaModelConnection")
                    .build();
        }

        @MediaModelSetup
        public static ResourceDescriptor javaImage() {
            return ResourceDescriptor.Builder.newBuilder(RecordingJavaImageSetup.class.getName())
                    .addInitialArgument("connection", "javaConnection")
                    .addInitialArgument("model", "img-java")
                    .build();
        }

        @MediaModelSetup
        public static ResourceDescriptor javaSpeech() {
            return ResourceDescriptor.Builder.newBuilder(RecordingJavaSpeechSetup.class.getName())
                    .addInitialArgument("connection", "javaConnection")
                    .addInitialArgument("model", "tts-java")
                    .build();
        }

        @MediaModelSetup
        public static ResourceDescriptor pythonImage() {
            return PythonResourceDescriptor.Builder.newBuilder(
                            "flink_agents.runtime.tests.media_cancellation_bridge.RecordingImageGenerationModelSetup")
                    .addInitialArgument("connection", "recordingConnection")
                    .addInitialArgument("model", "img-python")
                    .build();
        }

        @MediaModelSetup
        public static ResourceDescriptor pythonSpeech() {
            return PythonResourceDescriptor.Builder.newBuilder(
                            "flink_agents.runtime.tests.media_cancellation_bridge.RecordingSpeechSynthesisModelSetup")
                    .addInitialArgument("connection", "recordingConnection")
                    .addInitialArgument("model", "tts-python")
                    .build();
        }
    }

    @ParameterizedTest
    @CsvSource({"javaImage, javaSpeech", "pythonImage, pythonSpeech"})
    void mediaModelSetupsResolveAcrossPemjaWithAsyncFidelityAndInterruption(
            String imageSetupName, String speechSetupName) throws Exception {
        AgentPlan plan = new AgentPlan(new CrossLanguageSetupsAgent());
        ResourceCache cache = new ResourceCache(plan.getResourceProviders());
        PythonResourceAdapterImpl cacheAdapter =
                new PythonResourceAdapterImpl(cache.getResourceContext(), manager, null);
        cacheAdapter.open();
        PythonMCPResourceDiscovery.discoverPythonMCPResources(
                plan.getResourceProviders(), cacheAdapter, cache);
        try {
            Resource imageResource = cache.getResource(imageSetupName, ResourceType.MEDIA_MODEL);
            Resource speechResource = cache.getResource(speechSetupName, ResourceType.MEDIA_MODEL);
            assertThat(imageResource.getResourceType()).isEqualTo(ResourceType.MEDIA_MODEL);
            assertThat(imageResource.getResourceContext()).isSameAs(cache.getResourceContext());
            assertThat(imageResource).isInstanceOf(BaseImageGenerationModelSetup.class);
            assertThat(imageResource).isInstanceOf(AsyncJobModel.class);
            assertThat(speechResource).isInstanceOf(BaseSpeechSynthesisModelSetup.class);
            assertThat(speechResource).isNotInstanceOf(AsyncJobModel.class);
            assertThat(speechResource.getResourceContext()).isSameAs(cache.getResourceContext());

            MediaResult<AudioBlock> speechResult =
                    ((BaseSpeechSynthesisModelSetup) speechResource).synthesize("hello setup");
            assertThat(speechResult.getContent()).hasSize(1);
            assertThat(speechResult.getMetadata()).containsEntry("text", "hello setup");

            boolean isJava = "javaImage".equals(imageSetupName);
            RecordingJavaMediaConnection javaConn =
                    isJava
                            ? (RecordingJavaMediaConnection)
                                    cache.getResource(
                                            "javaConnection", ResourceType.MEDIA_MODEL_CONNECTION)
                            : null;
            PythonMediaModelConnection pyConn =
                    isJava
                            ? null
                            : (PythonMediaModelConnection)
                                    cache.getResource(
                                            "recordingConnection",
                                            ResourceType.MEDIA_MODEL_CONNECTION);
            if (isJava) {
                javaConn.reset();
            } else {
                adapter.callMethod(pyConn.getPythonResource(), "reset", Map.of());
            }

            @SuppressWarnings("unchecked")
            AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> async =
                    (AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>>) imageResource;
            MediaJob job = async.submit(ImageGenerationRequest.of("setup prompt"));
            Map<String, Number> afterSubmit =
                    isJava
                            ? Map.copyOf(javaConn.getCounts())
                            : (Map<String, Number>)
                                    adapter.callMethod(
                                            pyConn.getPythonResource(), "get_counts", Map.of());
            assertThat(afterSubmit.get("submit").intValue()).isEqualTo(1);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interruptPreserved = new AtomicBoolean();
            Thread worker =
                    threadFactory.newThread(
                            () -> {
                                try {
                                    async.wait(job, Duration.ofSeconds(5), Duration.ofSeconds(5));
                                } catch (Throwable t) {
                                    failure.set(t);
                                    interruptPreserved.set(Thread.interrupted());
                                }
                            });
            worker.start();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                Map<String, Number> recorded =
                        isJava
                                ? Map.copyOf(javaConn.getCounts())
                                : (Map<String, Number>)
                                        adapter.callMethod(
                                                pyConn.getPythonResource(), "get_counts", Map.of());
                if (recorded.get("fetch").intValue() >= 1) {
                    break;
                }
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(10);
            }
            Thread.sleep(30);
            worker.interrupt();
            worker.join(2000);

            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(CancellationException.class);
            assertThat(CancellationUtils.isInterruption(failure.get())).isTrue();
            assertThat(interruptPreserved.get()).isTrue();
        } finally {
            try {
                cache.close();
            } finally {
                cacheAdapter.close();
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void yamlSubagentScopedConnectionsAndOmittedTypeWorkAcrossJavaAndPythonActions(
            @TempDir Path tempDir) throws Exception {
        Path yamlPath = tempDir.resolve("subagent_media.yaml");
        Files.writeString(
                yamlPath,
                "agents:\n"
                        + "  - name: child_agent\n"
                        + "    media_model_connections:\n"
                        + "      - name: recording\n"
                        + "        clazz: flink_agents.runtime.tests.media_cancellation_bridge.RecordingMediaModelConnection\n"
                        + "        connection_tag: child-override\n"
                        + "      - name: child_only\n"
                        + "        clazz: flink_agents.runtime.tests.media_cancellation_bridge.RecordingMediaModelConnection\n"
                        + "        connection_tag: child-only\n"
                        + "      - name: java_recording\n"
                        + "        clazz: "
                        + RecordingJavaMediaConnection.class.getName()
                        + "\n"
                        + "        type: java\n"
                        + "        connection_tag: child-override\n"
                        + "      - name: java_child_only\n"
                        + "        clazz: "
                        + RecordingJavaMediaConnection.class.getName()
                        + "\n"
                        + "        type: java\n"
                        + "        connection_tag: child-only\n"
                        + "    media_model_setups:\n"
                        + "      - name: child_override_image\n"
                        + "        clazz: flink_agents.runtime.tests.media_cancellation_bridge.RecordingImageGenerationModelSetup\n"
                        + "        connection: recording\n"
                        + "        model: img-child\n"
                        + "      - name: child_only_image\n"
                        + "        clazz: flink_agents.runtime.tests.media_cancellation_bridge.RecordingImageGenerationModelSetup\n"
                        + "        connection: child_only\n"
                        + "        model: img-child-only\n"
                        + "      - name: java_child_override_image\n"
                        + "        clazz: "
                        + RecordingJavaImageSetup.class.getName()
                        + "\n"
                        + "        type: java\n"
                        + "        connection: java_recording\n"
                        + "        model: img-java-child\n"
                        + "      - name: java_child_only_image\n"
                        + "        clazz: "
                        + RecordingJavaImageSetup.class.getName()
                        + "\n"
                        + "        type: java\n"
                        + "        connection: java_child_only\n"
                        + "        model: img-java-child-only\n"
                        + "  - name: root_agent\n"
                        + "    media_model_connections:\n"
                        + "      - name: recording\n"
                        + "        clazz: flink_agents.runtime.tests.media_cancellation_bridge.RecordingMediaModelConnection\n"
                        + "        connection_tag: root\n"
                        + "      - name: java_recording\n"
                        + "        clazz: "
                        + RecordingJavaMediaConnection.class.getName()
                        + "\n"
                        + "        type: java\n"
                        + "        connection_tag: root\n"
                        + "    media_model_setups:\n"
                        + "      - name: root_image\n"
                        + "        clazz: flink_agents.runtime.tests.media_cancellation_bridge.RecordingImageGenerationModelSetup\n"
                        + "        connection: recording\n"
                        + "        model: img-root\n"
                        + "    subagents:\n"
                        + "      - name: child\n"
                        + "        agent: child_agent\n"
                        + "        description: Child subagent with scoped media connections\n");

        Agent rootAgent = YamlLoader.buildAgents(yamlPath).getAgents().get("root_agent");
        AgentPlan rootPlan = new AgentPlan(rootAgent);
        AgentPlan childPlan =
                InternalSubagentPlans.plan(
                                rootPlan.getResourceProviders()
                                        .get(ResourceType.AGENT)
                                        .get("child"))
                        .orElseThrow();

        ResourceCache rootCache = new ResourceCache(rootPlan.getResourceProviders());
        PythonResourceAdapterImpl cacheAdapter =
                new PythonResourceAdapterImpl(rootCache.getResourceContext(), manager, null);
        cacheAdapter.open();
        PythonMCPResourceDiscovery.discoverPythonMCPResources(
                rootPlan.getResourceProviders(), cacheAdapter, rootCache);
        ResourceCache childCache =
                new ResourceCache(
                        childPlan.getResourceProviders(),
                        Thread.currentThread().getContextClassLoader(),
                        rootCache);
        try {
            Resource rootModel = rootCache.getResource("root_image", ResourceType.MEDIA_MODEL);
            Resource childOverridePyModel =
                    childCache.getResource("child_override_image", ResourceType.MEDIA_MODEL);
            Resource childOnlyPyModel =
                    childCache.getResource("child_only_image", ResourceType.MEDIA_MODEL);
            Resource overrideModel =
                    childCache.getResource("java_child_override_image", ResourceType.MEDIA_MODEL);
            Resource childOnlyModel =
                    childCache.getResource("java_child_only_image", ResourceType.MEDIA_MODEL);
            assertThat(rootModel.getResourceContext()).isSameAs(rootCache.getResourceContext());
            assertThat(childOverridePyModel.getResourceContext())
                    .isSameAs(childCache.getResourceContext());
            assertThat(childOnlyPyModel.getResourceContext())
                    .isSameAs(childCache.getResourceContext());
            assertThat(overrideModel.getResourceContext())
                    .isSameAs(childCache.getResourceContext());
            assertThat(childOnlyModel.getResourceContext())
                    .isSameAs(childCache.getResourceContext());

            PythonMediaModelConnection rootPyConnection =
                    (PythonMediaModelConnection)
                            rootCache.getResource("recording", ResourceType.MEDIA_MODEL_CONNECTION);
            PythonMediaModelConnection childOverridePyConnection =
                    (PythonMediaModelConnection)
                            childCache.getResource(
                                    "recording", ResourceType.MEDIA_MODEL_CONNECTION);
            PythonMediaModelConnection childOnlyPyConnection =
                    (PythonMediaModelConnection)
                            childCache.getResource(
                                    "child_only", ResourceType.MEDIA_MODEL_CONNECTION);
            assertThat(childOverridePyConnection).isNotSameAs(rootPyConnection);

            cacheAdapter.callMethod(rootPyConnection.getPythonResource(), "reset", Map.of());
            cacheAdapter.callMethod(
                    childOverridePyConnection.getPythonResource(), "reset", Map.of());
            cacheAdapter.callMethod(childOnlyPyConnection.getPythonResource(), "reset", Map.of());
            cacheAdapter.callMethod(
                    childOverridePyConnection.getPythonResource(),
                    "set_fetch_outcome",
                    Map.of("outcome", "succeeded"));
            cacheAdapter.callMethod(
                    childOnlyPyConnection.getPythonResource(),
                    "set_fetch_outcome",
                    Map.of("outcome", "succeeded"));

            MediaResult<ImageBlock> pyOverrideResult =
                    ((BaseImageGenerationModelSetup) childOverridePyModel).generate("cat");
            MediaResult<ImageBlock> pyOnlyResult =
                    ((BaseImageGenerationModelSetup) childOnlyPyModel).generate("dog");
            assertThat(pyOverrideResult.getMetadata())
                    .containsEntry("connection_tag", "child-override");
            assertThat(pyOnlyResult.getMetadata()).containsEntry("connection_tag", "child-only");

            @SuppressWarnings("unchecked")
            Map<String, Number> rootPyCounts =
                    (Map<String, Number>)
                            cacheAdapter.callMethod(
                                    rootPyConnection.getPythonResource(), "get_counts", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Number> overridePyCounts =
                    (Map<String, Number>)
                            cacheAdapter.callMethod(
                                    childOverridePyConnection.getPythonResource(),
                                    "get_counts",
                                    Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Number> onlyPyCounts =
                    (Map<String, Number>)
                            cacheAdapter.callMethod(
                                    childOnlyPyConnection.getPythonResource(),
                                    "get_counts",
                                    Map.of());
            assertThat(rootPyCounts.get("submit").intValue()).isZero();
            assertThat(overridePyCounts.get("submit").intValue()).isEqualTo(1);
            assertThat(onlyPyCounts.get("submit").intValue()).isEqualTo(1);

            RecordingJavaMediaConnection rootJavaConnection =
                    (RecordingJavaMediaConnection)
                            rootCache.getResource(
                                    "java_recording", ResourceType.MEDIA_MODEL_CONNECTION);
            RecordingJavaMediaConnection childOverrideJavaConnection =
                    (RecordingJavaMediaConnection)
                            childCache.getResource(
                                    "java_recording", ResourceType.MEDIA_MODEL_CONNECTION);
            RecordingJavaMediaConnection childOnlyJavaConnection =
                    (RecordingJavaMediaConnection)
                            childCache.getResource(
                                    "java_child_only", ResourceType.MEDIA_MODEL_CONNECTION);
            assertThat(childOverrideJavaConnection).isNotSameAs(rootJavaConnection);

            rootJavaConnection.reset();
            childOverrideJavaConnection.reset();
            childOnlyJavaConnection.reset();
            childOverrideJavaConnection.setFetchOutcome("succeeded");
            childOnlyJavaConnection.setFetchOutcome("succeeded");

            MediaResult<ImageBlock> overrideResult =
                    ((BaseImageGenerationModelSetup) overrideModel).generate("cat");
            MediaResult<ImageBlock> onlyResult =
                    ((BaseImageGenerationModelSetup) childOnlyModel).generate("dog");
            assertThat(overrideResult.getMetadata())
                    .containsEntry("connection_tag", "child-override");
            assertThat(onlyResult.getMetadata()).containsEntry("connection_tag", "child-only");

            assertThat(rootJavaConnection.getCounts().get("submit")).isZero();
            assertThat(childOverrideJavaConnection.getCounts().get("submit")).isEqualTo(1);
            assertThat(childOnlyJavaConnection.getCounts().get("submit")).isEqualTo(1);

            ObjectMapper mapper = new ObjectMapper();
            String rootPlanJson = mapper.writeValueAsString(rootPlan);
            String childPlanJson = mapper.writeValueAsString(childPlan);
            PyObject probe =
                    adapter.initPythonResource(
                            "flink_agents.runtime.tests.media_cancellation_bridge",
                            "YamlPlanScopeProbe",
                            new HashMap<>());
            try (probe) {
                Map<String, Object> pyReport =
                        (Map<String, Object>)
                                adapter.callMethod(
                                        probe,
                                        "verify_scopes",
                                        Map.of(
                                                "root_plan_json",
                                                rootPlanJson,
                                                "child_plan_json",
                                                childPlanJson));
                assertThat(((Number) pyReport.get("root_submits")).intValue()).isZero();
                assertThat(((Number) pyReport.get("child_submits")).intValue()).isEqualTo(1);
                assertThat(((Number) pyReport.get("child_only_submits")).intValue()).isEqualTo(1);
                assertThat(pyReport.get("override_tag")).isEqualTo("child-override");
                assertThat(pyReport.get("only_tag")).isEqualTo("child-only");
            }
        } finally {
            try {
                childCache.close();
            } finally {
                try {
                    rootCache.close();
                } finally {
                    cacheAdapter.close();
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void durableReplayAndConveniencePathAcrossPemja() throws Exception {
        adapter.callMethod(pyConnection, "reset", Map.of());
        adapter.callMethod(pyConnection, "set_fetch_outcome", Map.of("outcome", "succeeded"));
        BaseImageGenerationModelSetup image = imageSetup;
        AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> async =
                (AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>>) image;

        ActionState actionState = new ActionState(new InputEvent("test"));
        AtomicInteger persistCount = new AtomicInteger();
        RunnerContextImpl context = durableContext(actionState, persistCount);

        MediaJob job =
                context.durableExecute(
                        new DurableCallable<MediaJob>() {
                            @Override
                            public String getId() {
                                return "poster-image:submit";
                            }

                            @Override
                            public Class<MediaJob> getResultClass() {
                                return MediaJob.class;
                            }

                            @Override
                            public MediaJob call() {
                                return async.submit(ImageGenerationRequest.of("a cat"));
                            }
                        });
        MediaResult<ImageBlock> result =
                context.durableExecute(
                        durableCall(
                                "poster-image:wait",
                                () ->
                                        async.wait(
                                                job,
                                                Duration.ofSeconds(5),
                                                Duration.ofMillis(20))));
        assertThat(result.getContent()).hasExactlyElementsOfTypes(ImageBlock.class);
        assertThat(counts().get("submit").intValue()).isEqualTo(1);
        assertThat(persistCount.get()).isEqualTo(2);

        ActionState restored =
                ActionStateSerde.deserialize(ActionStateSerde.serialize(actionState));
        RunnerContextImpl replay = durableContext(restored, persistCount);
        MediaJob replayedJob =
                replay.durableExecute(
                        new DurableCallable<MediaJob>() {
                            @Override
                            public String getId() {
                                return "poster-image:submit";
                            }

                            @Override
                            public Class<MediaJob> getResultClass() {
                                return MediaJob.class;
                            }

                            @Override
                            public MediaJob call() {
                                throw new AssertionError("submit should replay");
                            }
                        });
        MediaResult<ImageBlock> replayedResult =
                replay.durableExecute(
                        durableCall(
                                "poster-image:wait",
                                () -> {
                                    throw new AssertionError("wait should replay");
                                }));
        assertThat(replayedJob).usingRecursiveComparison().isEqualTo(job);
        assertThat(replayedResult.getContent()).hasExactlyElementsOfTypes(ImageBlock.class);
        assertThat(counts().get("submit").intValue()).isEqualTo(1);

        // Convenience generate() runs through Java submit + AsyncJobPolling.wait(fetch).
        MediaResult<ImageBlock> convenience = image.generate("convenience");
        assertThat(convenience.getContent()).hasExactlyElementsOfTypes(ImageBlock.class);
        assertThat(counts().get("submit").intValue()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptDuringTerminalFetchIsNotJournaledAcrossPemja() throws Exception {
        adapter.callMethod(pyConnection, "reset", Map.of());
        adapter.callMethod(pyConnection, "set_fetch_outcome", Map.of("outcome", "failed"));
        adapter.callMethod(pyConnection, "set_fetch_delay", Map.of("seconds", 0.2));
        BaseImageGenerationModelSetup image = imageSetup;
        AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> async =
                (AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>>) image;
        MediaJob job = async.submit(ImageGenerationRequest.of("a cat"));

        ActionState actionState = new ActionState(new InputEvent("test"));
        AtomicInteger persistCount = new AtomicInteger();
        RunnerContextImpl context = durableContext(actionState, persistCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();

        Thread worker =
                threadFactory.newThread(
                        () -> {
                            try {
                                context.durableExecute(
                                        durableCall(
                                                "media:terminal-interrupt",
                                                () ->
                                                        async.wait(
                                                                job,
                                                                Duration.ofSeconds(5),
                                                                Duration.ofSeconds(5))));
                            } catch (Throwable t) {
                                failure.set(t);
                                interruptPreserved.set(Thread.interrupted());
                            }
                        });
        worker.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (counts().get("fetch").intValue() < 1) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }
        worker.interrupt();
        worker.join(2000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(CancellationException.class);
        assertThat(CancellationUtils.isInterruption(failure.get())).isTrue();
        assertThat(interruptPreserved.get()).isTrue();
        assertThat(persistCount.get()).isZero();
        assertThat(actionState.getCallResultCount()).isZero();
        assertThat(counts().get("cancel").intValue()).isZero();
    }

    @Test
    void pythonSetupRejectsJavaConnectionOnOpen() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "javaOnly",
                ResourceType.MEDIA_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(JavaOnlyMediaConnection.class.getName())
                        .build());
        agent.addResource(
                "customSetup",
                ResourceType.MEDIA_MODEL,
                new PythonResourceDescriptor(
                        "flink_agents.runtime.tests.media_cancellation_bridge",
                        "RecordingImageGenerationModelSetup",
                        Map.of("connection", "javaOnly", "model", "img")));
        AgentPlan plan = new AgentPlan(agent);
        ResourceCache cache = new ResourceCache(plan.getResourceProviders());
        PythonResourceAdapterImpl cacheAdapter =
                new PythonResourceAdapterImpl(cache.getResourceContext(), manager, null);
        cacheAdapter.open();
        PythonMCPResourceDiscovery.discoverPythonMCPResources(
                plan.getResourceProviders(), cacheAdapter, cache);
        try {
            assertThatThrownBy(() -> cache.getResource("customSetup", ResourceType.MEDIA_MODEL))
                    .hasMessageContaining(
                            "No Java resource mapping found for media_model_connection");
        } finally {
            try {
                cache.close();
            } finally {
                cacheAdapter.close();
            }
        }
    }

    private RunnerContextImpl durableContext(ActionState actionState, AtomicInteger persistCount)
            throws Exception {
        RunnerContextImpl context =
                new RunnerContextImpl(
                        null,
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "test",
                        TypeInformation.of(Event.class)
                                .createSerializer(new SerializerConfigImpl()));
        Action action =
                new Action(
                        "noop",
                        new JavaFunction(
                                MediaModelCancellationBridgeTest.class,
                                "noop",
                                new Class[] {Event.class, RunnerContext.class}),
                        List.of(InputEvent.EVENT_TYPE));
        context.setDurableExecutionContext(
                new RunnerContextImpl.DurableExecutionContext(
                        "key",
                        1L,
                        action,
                        new InputEvent("test"),
                        actionState,
                        (key, seq, act, event, state) -> persistCount.incrementAndGet()));
        return context;
    }

    @SuppressWarnings("unchecked")
    private static <T> DurableCallable<T> durableCall(String id, Callable<T> call) {
        return new DurableCallable<T>() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Class<T> getResultClass() {
                return (Class<T>) MediaResult.class;
            }

            @Override
            public T call() throws Exception {
                return call.call();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Number> counts() {
        return (Map<String, Number>) adapter.callMethod(pyConnection, "get_counts", Map.of());
    }
}
