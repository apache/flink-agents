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
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.AgentRunBeginEvent;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.eventlog.EventLogWriter;
import org.apache.flink.agents.runtime.lifecycle.ComponentExecutionListener;
import org.apache.flink.agents.runtime.lifecycle.PythonTaskLifecycleListener;
import org.apache.flink.agents.runtime.lifecycle.TaskLifecycleListener;
import org.apache.flink.agents.runtime.memory.Mem0LongTermMemory;
import org.apache.flink.agents.runtime.memory.MemoryEventBuilder;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.memory.MemoryUpdateReplayer;
import org.apache.flink.agents.runtime.metrics.BuiltInMetrics;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionCoordinator;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionLock;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionTask;
import org.apache.flink.agents.runtime.python.operator.PythonActionTask;
import org.apache.flink.agents.runtime.python.resource.PythonRuntimeResource;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.trace.EventLogComponentExecutionListener;
import org.apache.flink.agents.runtime.trace.EventLogTaskLifecycleListener;
import org.apache.flink.agents.runtime.trace.ExecutionEventLogger;
import org.apache.flink.agents.runtime.utils.EventUtil;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.OperatorSnapshotFutures;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxExecutorImpl;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxProcessor;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.function.ThrowingRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.JOB_IDENTIFIER;
import static org.apache.flink.agents.plan.actions.Utils.supportAsync;
import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * An operator that executes the actions defined in the agent. Upon receiving data from the
 * upstream, it first wraps the data into an {@link InputEvent}. It then invokes the corresponding
 * action that is interested in the {@link InputEvent}, and collects the output event produced by
 * the action.
 *
 * <p>For events of type {@link OutputEvent}, the data contained in the event is sent downstream.
 * For all other event types, the process is repeated: the event triggers the corresponding action,
 * and the resulting output event is collected for further processing.
 */
public class ActionExecutionOperator<IN, OUT> extends AbstractStreamOperator<OUT>
        implements OneInputStreamOperator<IN, OUT>, BoundedOneInput {

    private static final long serialVersionUID = 1L;
    private static final String AGENT_RUN_BEGIN_ACTION_NAME = "agent_run_begin_action";

    private static final Logger LOG = LoggerFactory.getLogger(ActionExecutionOperator.class);

    /** Idle time after which a surplus parallel-execution worker thread retires (min pool is 1). */
    private static final long WORKER_IDLE_TIMEOUT_MS = 60_000L;

    private static final String INTERNAL_NOOP_INPUT_ACTION_NAME =
            "__flink_agents_internal_noop_input_action";

    private static final Action INTERNAL_NOOP_INPUT_ACTION = createInternalNoopInputAction();

    private static Action createInternalNoopInputAction() {
        try {
            return new Action(
                    INTERNAL_NOOP_INPUT_ACTION_NAME,
                    new JavaFunction(
                            InternalNoopInputAction.class,
                            "run",
                            new Class<?>[] {Event.class, RunnerContext.class}),
                    Collections.singletonList(InputEvent.EVENT_TYPE));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static boolean isInternalNoopInputAction(Action action) {
        return INTERNAL_NOOP_INPUT_ACTION_NAME.equals(action.getName());
    }

    public static final class InternalNoopInputAction {
        public static void run(Event event, RunnerContext ctx) {}

        private InternalNoopInputAction() {}
    }

    static boolean shouldEnableParallelExecutionWithoutCoroutine(
            AgentPlan agentPlan, boolean continuationSupported) {
        return !continuationSupported
                && agentPlan.getConfig().get(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED)
                && agentPlan.getActions().values().stream()
                        .allMatch(action -> action.getExec() instanceof JavaFunction)
                && (!usesPythonResource(agentPlan) || supportAsync());
    }

    /** Whether the plan declares any Python resource. */
    private static boolean usesPythonResource(AgentPlan agentPlan) {
        return agentPlan.getResourceProviders().values().stream()
                .flatMap(providersByName -> providersByName.values().stream())
                .anyMatch(provider -> provider instanceof PythonResourceProvider);
    }

    private final AgentPlan agentPlan;

    private transient ResourceCache resourceCache;

    private transient PythonBridgeManager pythonBridge;

    private transient FlinkAgentsMetricGroupImpl metricGroup;

    private transient BuiltInMetrics builtInMetrics;

    private final transient MailboxExecutor mailboxExecutor;

    private transient ActionTaskContextManager contextManager;

    private transient boolean parallelExecutionWithoutCoroutineEnabled;

    @Nullable private transient ParallelExecutionLock parallelExecutionLock;

    @Nullable private transient ParallelExecutionCoordinator executionCoordinator;

    /**
     * Number of input records currently being processed (one per active key), compared against
     * {@link #maxInFlightInputRecords} to apply input backpressure. Maintained on the mailbox
     * thread alongside {@code addProcessingKey}/{@code removeProcessingKey} and rebuilt from the
     * recovered processing keys.
     */
    private transient int inFlightInputRecords;

    /**
     * Configured cap on concurrently processed input records; see {@link #inFlightInputRecords}.
     */
    private int maxInFlightInputRecords;

    // Long-term memory backed by Mem0; non-null only when LongTermMemoryOptions.Mem0 is configured.
    private transient Mem0LongTermMemory ltm;

    // We need to check whether the current thread is the mailbox thread using the mailbox
    // processor.
    // TODO: This is a temporary workaround. In the future, we should add an interface in
    // MailboxExecutor to check whether a thread is a mailbox thread, rather than using reflection
    // to obtain the MailboxProcessor instance and make the determination.
    private transient MailboxProcessor mailboxProcessor;

    private final transient EventRouter<IN, OUT> eventRouter;

    private final transient ExecutionEventLogger executionEventLogger;

    private final transient EventLogWriter eventLogWriter;

    private final transient DurableExecutionManager durableExecManager;

    private transient OperatorStateManager stateManager;

    private transient TypeSerializer<Event> eventSerializer;

    // Each job can only have one identifier and this identifier must be consistent across restarts.
    // We cannot use job id as the identifier here because user may change job id by
    // creating a savepoint, stop the job and then resume from savepoint.
    // We use this identifier to control the visibility for long-term memory.
    // Inspired by Apache Paimon.
    private transient String jobIdentifier;

    private final boolean inputIsJava;
    private final boolean pythonKeyIsPickled;
    private final boolean agentRunBeginEventEnabled;

    // Broadcast targets for the per-record/per-action lifecycle events.
    private transient List<TaskLifecycleListener> taskLifecycleListeners = new ArrayList<>();

    // Broadcast targets for component execution reports, injected per action execution.
    private transient List<ComponentExecutionListener> componentExecutionListeners =
            new ArrayList<>();

    public ActionExecutionOperator(
            AgentPlan agentPlan,
            Boolean inputIsJava,
            boolean pythonKeyIsPickled,
            ProcessingTimeService processingTimeService,
            MailboxExecutor mailboxExecutor,
            ActionStateStore actionStateStore) {
        this.agentPlan = agentPlan;
        this.processingTimeService = processingTimeService;
        this.mailboxExecutor = mailboxExecutor;
        this.inputIsJava = inputIsJava;
        this.pythonKeyIsPickled = pythonKeyIsPickled;
        this.eventLogWriter = EventLogWriter.create(agentPlan);
        this.eventRouter = new EventRouter<>(agentPlan, inputIsJava, eventLogWriter);
        this.executionEventLogger = ExecutionEventLogger.forEventLogWriter(eventLogWriter);
        this.durableExecManager = new DurableExecutionManager(actionStateStore);
        this.agentRunBeginEventEnabled =
                Boolean.TRUE.equals(
                        agentPlan.getConfig().get(AgentExecutionOptions.AGENT_RUN_BEGIN_EVENT));
        OperatorUtils.setChainStrategy(this, ChainingStrategy.ALWAYS);
    }

    @Override
    public void setup(
            StreamTask<?, ?> containingTask,
            StreamConfig config,
            Output<StreamRecord<OUT>> output) {
        super.setup(containingTask, config, output);
    }

    @Override
    public void open() throws Exception {
        super.open();

        eventSerializer =
                TypeInformation.of(Event.class)
                        .createSerializer(getExecutionConfig().getSerializerConfig());

        stateManager.initializeKeyedStates(getRuntimeContext(), agentPlan.getConfig());
        stateManager.initializeOperatorStates(getOperatorStateBackend());

        // ResourceCache constructs its own long-lived ResourceContextImpl internally; on
        // close() the cache cascades close to it and to the cached SkillManager, covering
        // Flink failover when the JVM does not exit. The user-code class loader is threaded
        // down so classpath: skill sources resolve against the Flink user JAR regardless of
        // which thread (mailbox / Python interpreter / async pool) later triggers the lazy
        // SkillManager construction.
        resourceCache =
                new ResourceCache(
                        agentPlan.getResourceProviders(),
                        getRuntimeContext().getUserCodeClassLoader());

        metricGroup = new FlinkAgentsMetricGroupImpl(getMetricGroup());
        builtInMetrics =
                new BuiltInMetrics(
                        metricGroup,
                        agentPlan,
                        toolName -> resourceCache.hasResource(toolName, ResourceType.TOOL));

        eventRouter.open(builtInMetrics);

        int maxParallelism = getRuntimeContext().getTaskInfo().getMaxNumberOfParallelSubtasks();
        durableExecManager.maybeInitActionStateStore(
                agentPlan.getConfig(), maxParallelism, getActionStateKeySerializer());
        durableExecManager.initRecoveryMarkerState(getOperatorStateBackend());
        durableExecManager.initializeKeyedStates(getRuntimeContext());

        int numAsyncThreads = agentPlan.getConfig().get(AgentExecutionOptions.NUM_ASYNC_THREADS);
        checkArgument(
                numAsyncThreads > 0,
                "%s must be positive, but was %s",
                AgentExecutionOptions.NUM_ASYNC_THREADS.getKey(),
                numAsyncThreads);

        maxInFlightInputRecords =
                agentPlan.getConfig().get(AgentExecutionOptions.MAX_IN_FLIGHT_INPUT_RECORDS);
        checkArgument(
                maxInFlightInputRecords > 0,
                "%s must be positive, but was %s",
                AgentExecutionOptions.MAX_IN_FLIGHT_INPUT_RECORDS.getKey(),
                maxInFlightInputRecords);

        parallelExecutionWithoutCoroutineEnabled =
                shouldEnableParallelExecutionWithoutCoroutine(
                        agentPlan, ContinuationActionExecutor.isContinuationSupported());
        if (parallelExecutionWithoutCoroutineEnabled) {
            parallelExecutionLock = new ParallelExecutionLock();
        } else {
            // JDK21 cooperative-continuation path: there is no worker pool and no lock. All
            // engine-shared sections (waitInFlightEventsFinished) must guard on the null lock.
            parallelExecutionLock = null;
        }

        // init PythonActionExecutor and PythonResourceAdapter
        pythonBridge = new PythonBridgeManager();
        // On the parallel engine the execution invariant is "holds the ParallelExecutionLock",
        // not "is the physical mailbox thread".
        pythonBridge.open(
                agentPlan,
                resourceCache,
                getExecutionConfig(),
                getRuntimeContext().getDistributedCache(),
                getContainingTask().getEnvironment().getTaskManagerInfo().getTmpDirectories(),
                getRuntimeContext().getJobInfo().getJobId(),
                metricGroup,
                parallelExecutionWithoutCoroutineEnabled
                        ? parallelExecutionLock::checkReentrant
                        : this::checkMailboxThread,
                jobIdentifier,
                getRuntimeContext().getUserCodeClassLoader());

        if (parallelExecutionWithoutCoroutineEnabled) {
            executionCoordinator =
                    new ParallelExecutionCoordinator(
                            parallelExecutionLock,
                            mailboxExecutor::execute,
                            Work::new,
                            pythonBridge::releaseCurrentThreadInterpreter,
                            numAsyncThreads,
                            WORKER_IDLE_TIMEOUT_MS);
        }

        // Capture the wired Mem0 long-term memory, if any, so it can be plumbed into the Java
        // runner context created by ActionTaskContextManager.
        ltm = pythonBridge.getLongTermMemory();

        if (taskLifecycleListeners == null) {
            taskLifecycleListeners = new ArrayList<>();
        }
        if (componentExecutionListeners == null) {
            componentExecutionListeners = new ArrayList<>();
        }

        registerBuiltInLifecycleListeners();
        registerSubagentSetups();

        // The continuation executor only borrows the lock on the worker-pool path; on every other
        // path it is null so executeAsync keeps its synchronous fallback.
        contextManager =
                new ActionTaskContextManager(
                        this,
                        durableExecManager,
                        numAsyncThreads,
                        pythonBridge::releaseCurrentThreadInterpreter,
                        parallelExecutionLock);

        mailboxProcessor = getMailboxProcessor();

        eventLogWriter.open(getRuntimeContext(), builtInMetrics);

        // Initialize user event listeners from configuration
        eventRouter.initEventListeners(getRuntimeContext());

        // Since an operator restart may change the key range it manages due to changes in
        // parallelism,
        // and {@link tryProcessActionTaskForKey} mails might be lost,
        // it is necessary to reprocess all keys to ensure correctness.
        if (parallelExecutionWithoutCoroutineEnabled) {
            runWithParallelExecutionLock(this::tryResumeProcessActionTasks);
        } else {
            tryResumeProcessActionTasks();
        }
    }

    @Override
    public void processWatermark(Watermark mark) throws Exception {
        if (parallelExecutionWithoutCoroutineEnabled) {
            runWithParallelExecutionLock(() -> processWatermarkInternal(mark));
        } else {
            processWatermarkInternal(mark);
        }
    }

    private void processWatermarkInternal(Watermark mark) throws Exception {
        eventRouter.getKeySegmentQueue().addWatermark(mark);
        eventRouter.processEligibleWatermarks(super::processWatermark);
    }

    /**
     * On the JDK11 parallel engine, overridden to skip the framework's pre-{@link #processElement}
     * key switch: the framework performs it on the mailbox thread but <em>outside</em> our mailbox
     * lock, and a coordinator worker may be executing an action under the lock with a different
     * current key that an off-lock switch would corrupt. On that engine we re-establish the
     * record's key under the lock inside {@link #processElement}. On the normal engine there are no
     * such workers, so we defer to the framework's switch. ({@link
     * org.apache.flink.streaming.api.operators.Input#setKeyContextElement} default-delegates here,
     * so this covers the one-input path.)
     */
    @Override
    @SuppressWarnings("rawtypes")
    public void setKeyContextElement1(StreamRecord record) throws Exception {
        if (!parallelExecutionWithoutCoroutineEnabled) {
            super.setKeyContextElement1(record);
        }
    }

    @Override
    public void processElement(StreamRecord<IN> record) throws Exception {
        if (parallelExecutionWithoutCoroutineEnabled) {
            runWithParallelExecutionLock(
                    () -> {
                        // Soft backpressure BEFORE establishing the key: yields may change the
                        // current key, so the record's key is set after the last yield. Release
                        // around yield (non-reentrant lock); re-acquire in finally for the outer
                        // release.
                        while (inFlightInputRecords >= maxInFlightInputRecords) {
                            parallelExecutionLock.release();
                            try {
                                mailboxExecutor.yield();
                            } finally {
                                parallelExecutionLock.acquireByMain();
                            }
                        }
                        super.setKeyContextElement1(record);
                        processElementInternal(record);
                    });
        } else {
            processElementInternal(record);
        }
    }

    private void processElementInternal(StreamRecord<IN> record) throws Exception {
        // Every admitted input record consumes one unit of in-flight budget, regardless of whether
        // it activates a new key or queues behind an in-flight record for an already-active key.
        // The matching decrement happens when that record's processing finishes.
        inFlightInputRecords++;
        IN input = record.getValue();
        LOG.debug("Receive an element {}", input);

        // wrap to InputEvent first
        Event inputEvent =
                eventRouter.wrapToInputEvent(input, pythonBridge.getPythonActionExecutor());
        if (record.hasTimestamp()) {
            inputEvent.setSourceTimestamp(record.getTimestamp());
        }
        builtInMetrics.markInputEventReceived(inputEvent);

        Object key = getCurrentKey();
        try {
            eventRouter.getKeySegmentQueue().addKeyToLastSegment(key);

            boolean currentKeyBusy =
                    parallelExecutionWithoutCoroutineEnabled
                            ? executionCoordinator.hasOutstanding(getCurrentKey())
                            : stateManager.hasMoreActionTasks();
            if (currentKeyBusy) {
                // If there are already actions being processed for the current key, the newly
                // incoming event should be queued and processed later. Therefore, we add it to
                // pendingInputEventsState.
                enqueuePendingInputEvent(inputEvent);
                return;
            }
        } catch (Exception e) {
            builtInMetrics.markInputEventFailed(inputEvent);
            throw e;
        }

        // Otherwise, the new event is processed immediately. Its failures are attributed to the
        // input run created by processInputEvent.
        processInputEvent(key, inputEvent);
    }

    /** Resolves one context key for an input and reuses it for the entire agent run. */
    private void processInputEvent(Object key, Event inputEvent) throws Exception {
        final String contextKey;
        final ExecutionTraceContext traceContext;
        try {
            contextKey = resolveContextKey(key);
            traceContext = ExecutionTraceContext.forInputRun(contextKey, agentPlan.getAgentName());
        } catch (Exception e) {
            builtInMetrics.markInputEventFailed(inputEvent);
            throw e;
        }
        builtInMetrics.markInputRunStarted(inputEvent, traceContext);
        try {
            processEvent(key, contextKey, inputEvent, traceContext);
        } catch (Exception e) {
            builtInMetrics.markInputRunFailed(traceContext.getInputRunId());
            throw e;
        }
    }

    private void processEvent(
            Object key, String contextKey, Event event, ExecutionTraceContext traceContext)
            throws Exception {
        eventRouter.notifyEventProcessed(event, traceContext);

        boolean isInputEvent = EventUtil.isInputEvent(event);
        if (EventUtil.isOutputEvent(event)) {
            // If the event is an OutputEvent, we send it downstream.
            OUT outputData =
                    eventRouter.getOutputFromOutputEvent(
                            event, pythonBridge.getPythonActionExecutor());
            if (event.hasSourceTimestamp()) {
                output.collect(
                        eventRouter
                                .getReusedStreamRecord()
                                .replace(outputData, event.getSourceTimestamp()));
            } else {
                eventRouter.getReusedStreamRecord().eraseTimestamp();
                output.collect(eventRouter.getReusedStreamRecord().replace(outputData));
            }
        } else {
            boolean freshRecordRound = false;
            if (isInputEvent) {
                // If the event is an InputEvent, we mark that the key is currently being processed.
                if (!stateManager.hasMoreActionTasks()) {
                    // No tasks in flight for this key: this input record starts a fresh record
                    // processing round.
                    freshRecordRound = true;
                }
                stateManager.addProcessingKey(key);
                stateManager.initOrIncSequenceNumber();
                tryEmitAgentRunBeginEvent(key, contextKey, event, traceContext);
            }
            // We then obtain the triggered action and add ActionTasks to the waiting processing
            // queue.
            List<Action> triggerActions = eventRouter.getActionsTriggeredBy(event);
            if (triggerActions != null && !triggerActions.isEmpty()) {
                for (Action triggerAction : triggerActions) {
                    enqueueActionTask(
                            createActionTask(
                                    key,
                                    triggerAction,
                                    event,
                                    stateManager.getSequenceNumber(),
                                    traceContext));
                    if (freshRecordRound) {
                        notifyRecordStart(key);
                        freshRecordRound = false;
                    }
                    if (parallelExecutionWithoutCoroutineEnabled) {
                        // Add one task node per queued task; a worker pulls+prepares it later.
                        executionCoordinator.addTask(key);
                    }
                }
            } else if (isInputEvent && parallelExecutionWithoutCoroutineEnabled) {
                stateManager.addActionTask(
                        createActionTask(
                                key,
                                INTERNAL_NOOP_INPUT_ACTION,
                                event,
                                stateManager.getSequenceNumber(),
                                traceContext));
                executionCoordinator.addTask(key);
            }
        }

        if (isInputEvent && !parallelExecutionWithoutCoroutineEnabled) {
            // Kick mail for the normal engine only: it has no workers, so processActionTaskForKey
            // runs from the mail. The parallel engine's addTask above already dispatched permits.
            mailboxExecutor.submit(
                    () -> tryProcessActionTaskForKey(key, contextKey, traceContext.getInputRunId()),
                    "process action task");
        }
    }

    /**
     * Attempts to emit an {@link AgentRunBeginEvent} for the input before any action triggered by
     * that input executes.
     */
    private void tryEmitAgentRunBeginEvent(
            Object key, String contextKey, Event inputEvent, ExecutionTraceContext traceContext)
            throws Exception {
        if (!agentRunBeginEventEnabled) {
            return;
        }
        Map<String, Object> stm = new LinkedHashMap<>();
        Iterable<Map.Entry<String, MemoryObjectImpl.MemoryItem>> entries =
                stateManager.getShortTermMemState().entries();
        if (entries != null) {
            for (Map.Entry<String, MemoryObjectImpl.MemoryItem> entry : entries) {
                MemoryObjectImpl.MemoryItem item = entry.getValue();
                if (item != null
                        && item.isValue()
                        && !MemoryObjectImpl.ROOT_KEY.equals(entry.getKey())) {
                    try {
                        stm.put(entry.getKey(), MemoryEventBuilder.normalizeValue(item.getValue()));
                    } catch (Exception | LinkageError e) {
                        LOG.warn(
                                "Skipping non-JSON-compatible STM value in AgentRunBeginEvent ({})",
                                e.getClass().getSimpleName());
                    }
                }
            }
        }
        final AgentRunBeginEvent beginEvent;
        try {
            beginEvent = new AgentRunBeginEvent(contextKey, stm);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn(
                    "Skipping AgentRunBeginEvent because its value snapshot is not JSON-compatible ({})",
                    e.getClass().getSimpleName());
            return;
        }
        if (inputEvent.hasSourceTimestamp()) {
            beginEvent.setSourceTimestamp(inputEvent.getSourceTimestamp());
        }
        beginEvent.setUpstreamEventId(inputEvent.getId());
        beginEvent.setUpstreamActionName(AGENT_RUN_BEGIN_ACTION_NAME);
        processEvent(key, contextKey, beginEvent, traceContext);
    }

    /** Kick mail used only on the normal engine to drive queued-task processing for a key. */
    private void tryProcessActionTaskForKey(
            Object key, String contextKey, @Nullable String inputRunId) {
        try {
            processActionTaskForKey(key, contextKey, inputRunId);
        } catch (Throwable t) {
            // MailboxExecutor.submit() stores task failures in its Future. Catch Throwable and
            // rethrow via execute() so Errors fail the task instead of leaving the key in-flight.
            mailboxExecutor.execute(
                    () ->
                            ExceptionUtils.rethrow(
                                    new ActionTaskExecutionException(
                                            "Failed to execute action task", t)),
                    "throw exception in mailbox");
        }
    }

    private void maybeFinishCurrentInput(Object key, @Nullable Work lastCommitted)
            throws Exception {
        if (lastCommitted == null) {
            // This drain committed nothing: the head is still running, or an earlier mail already
            // drained and retired the input (stale mail). Only the tail-committing mail retires.
            return;
        }
        setCurrentKey(key);
        if (stateManager.hasMoreActionTasks() || executionCoordinator.hasOutstanding(key)) {
            return;
        }

        lastCommitted.actionTask.getRunnerContext().clearSensoryMemory();
        durableExecManager.updateLastCompletedSequenceNumber(lastCommitted.sequenceNumber);
        // Mirror the serial path: notify record finished. Noop rounds stay silent.
        if (!isInternalNoopInputAction(lastCommitted.actionTask.action)) {
            notifyRecordFinished(key);
        }
        int removedCount = stateManager.removeProcessingKey(key);
        inFlightInputRecords--;
        checkState(
                removedCount == 1,
                "Current processing key count for key "
                        + key
                        + " should be 1, but got "
                        + removedCount);
        checkState(
                eventRouter.getKeySegmentQueue().removeKey(key),
                "Current key" + key + " is missing from the segmentedQueue.");
        eventRouter.processEligibleWatermarks(super::processWatermark);
        builtInMetrics.markInputRunCompleted(
                lastCommitted.actionTask.getTraceContext().getInputRunId());
        Event pendingInputEvent = pollNextPendingInputEvent();
        if (pendingInputEvent != null) {
            processInputEvent(key, pendingInputEvent);
        }
    }

    private void processActionTaskForKey(Object key, String contextKey, @Nullable String inputRunId)
            throws Exception {
        String currentInputRunId = inputRunId;
        try {
            // 1. Get an action task for the key.
            setCurrentKey(key);

            ActionTask actionTask = pollNextActionTask();
            if (actionTask == null) {
                int removedCount = stateManager.removeProcessingKey(key);
                checkState(
                        removedCount == 1,
                        "Current processing key count for key "
                                + key
                                + " should be 1, but got "
                                + removedCount);
                checkState(
                        eventRouter.getKeySegmentQueue().removeKey(key),
                        "Current key" + key + " is missing from the segmentedQueue.");
                eventRouter.processEligibleWatermarks(super::processWatermark);
                builtInMetrics.markInputRunCompleted(currentInputRunId);
                return;
            }
            currentInputRunId = actionTask.getTraceContext().getInputRunId();
            processActionTask(key, contextKey, actionTask);
        } catch (Exception e) {
            builtInMetrics.markInputRunFailed(currentInputRunId);
            throw e;
        }
    }

    private void processActionTask(Object key, String contextKey, ActionTask actionTask)
            throws Exception {
        // 2. Invoke the action task.
        contextManager.createAndSetRunnerContext(
                actionTask,
                contextKey,
                agentPlan,
                resourceCache,
                metricGroup,
                jobIdentifier,
                this::checkMailboxThread,
                stateManager.getSensoryMemState(),
                stateManager.getShortTermMemState(),
                pythonBridge.getPythonRunnerContext(),
                ltm,
                this::createComponentListeners);
        notifyActionPrepared(actionTask);

        long sequenceNumber = stateManager.getSequenceNumber();
        boolean isFinished;
        List<Event> outputEvents;
        Optional<ActionTask> generatedActionTaskOpt = Optional.empty();
        ActionState actionState =
                durableExecManager.maybeGetActionState(
                        key, sequenceNumber, actionTask.action, actionTask.event);

        // Check if action is already completed
        if (actionState != null && actionState.isCompleted()) {
            // Action has completed, skip execution and replay memory/events
            // TODO: unlike the executed path below (and the parallel engine's Work.commit), this
            //  replay path never calls contextManager.removeBundle(actionTask); the bundle lingers
            //  until close. Bounded during recovery, but asymmetric.
            LOG.debug(
                    "Skipping already completed action: {} for key: {}",
                    actionTask.action.getName(),
                    key);
            isFinished = true;
            outputEvents = actionTask.finalizeOutputEvents(actionState.getOutputEvents());
            MemoryUpdateReplayer.replay(
                    actionTask.getRunnerContext().getShortTermMemory(),
                    actionState.getShortTermMemoryUpdates());
            MemoryUpdateReplayer.replay(
                    actionTask.getRunnerContext().getSensoryMemory(),
                    actionState.getSensoryMemoryUpdates());
            notifyActionReused(actionTask);
            contextManager.removeContexts(actionTask);
        } else {
            // Initialize ActionState if not exists, or use existing one for recovery
            if (actionState == null) {
                durableExecManager.maybeInitActionState(
                        key, sequenceNumber, actionTask.action, actionTask.event);
                actionState =
                        durableExecManager.maybeGetActionState(
                                key, sequenceNumber, actionTask.action, actionTask.event);
            }

            try {
                notifyActionStarted(actionTask);
                // Set up durable execution context for fine-grained recovery
                durableExecManager.setupDurableExecutionContext(
                        actionTask, actionState, sequenceNumber);

                ActionTask.ActionTaskResult actionTaskResult;
                try {
                    if (actionTask instanceof JavaActionTask) {
                        ((JavaActionTask) actionTask).setEventSerializer(eventSerializer);
                    }
                    actionTaskResult =
                            actionTask.invoke(
                                    getRuntimeContext().getUserCodeClassLoader(),
                                    this.pythonBridge.getPythonActionExecutor());
                } catch (Throwable actionFailure) {
                    try {
                        actionTask.getRunnerContext().discardMemoryObservation();
                    } catch (Throwable discardFailure) {
                        if (discardFailure != actionFailure) {
                            actionFailure.addSuppressed(discardFailure);
                        }
                    }
                    ExceptionUtils.rethrowException(actionFailure);
                    throw new AssertionError("Unreachable after rethrowing action failure");
                }

                // We remove the contexts record from the map after the task is processed. It
                // will be recreated by transferContexts below if the action task has a generated
                // action task, meaning it is not finished.
                contextManager.removeContexts(actionTask);
                durableExecManager.removeDurableContext(actionTask);
                if (actionTaskResult.isFinished()) {
                    // Notify before persisting the result, so listeners observe the task
                    // before its completion becomes durable.
                    notifyActionFinishing(actionTask);
                }
                durableExecManager.maybePersistTaskResult(
                        key,
                        sequenceNumber,
                        actionTask.action,
                        actionTask.event,
                        actionTask.getRunnerContext(),
                        actionTaskResult);
                isFinished = actionTaskResult.isFinished();
                outputEvents = actionTaskResult.getOutputEvents();
                generatedActionTaskOpt = actionTaskResult.getGeneratedActionTask();
                if (isFinished) {
                    notifyActionFinished(actionTask);
                }
            } catch (Throwable t) {
                notifyActionFailed(actionTask, t);
                ExceptionUtils.rethrowException(t);
                // Unreachable; required for Java definite-assignment analysis.
                return;
            }
        }

        for (Event actionOutputEvent : outputEvents) {
            processEvent(key, contextKey, actionOutputEvent, actionTask.getTraceContext());
        }

        boolean currentInputEventFinished = false;
        if (isFinished) {
            builtInMetrics.markActionExecuted(actionTask.action.getName());
            currentInputEventFinished = !stateManager.hasMoreActionTasks();

            // Persist memory to the Flink state when the action task is finished.
            actionTask.getRunnerContext().persistMemory();
        } else {
            checkState(
                    generatedActionTaskOpt.isPresent(),
                    "ActionTask not finished, but the generated action task is null.");

            // If the action task is not finished, we should get a new action task to continue the
            // execution.
            ActionTask generatedActionTask = generatedActionTaskOpt.get();

            // If the action task is not finished, we keep the contexts in memory for the
            // next generated ActionTask to be invoked.
            contextManager.transferContexts(actionTask, generatedActionTask, durableExecManager);
            notifyActionTransferred(actionTask, generatedActionTask);

            enqueueActionTask(generatedActionTask);
        }

        // 3. Process the next InputEvent or next action task
        if (currentInputEventFinished) {
            notifyRecordFinished(key);
            // Clean up sensory memory when a single run finished.
            actionTask.getRunnerContext().clearSensoryMemory();
            durableExecManager.updateLastCompletedSequenceNumber(sequenceNumber);

            // Once all sub-events and actions related to the current InputEvent are completed,
            // we can proceed to process the next InputEvent.
            int removedCount = stateManager.removeProcessingKey(key);
            inFlightInputRecords--;
            checkState(
                    removedCount == 1,
                    "Current processing key count for key "
                            + key
                            + " should be 1, but got "
                            + removedCount);
            checkState(
                    eventRouter.getKeySegmentQueue().removeKey(key),
                    "Current key" + key + " is missing from the segmentedQueue.");
            eventRouter.processEligibleWatermarks(super::processWatermark);
            builtInMetrics.markInputRunCompleted(actionTask.getTraceContext().getInputRunId());
            Event pendingInputEvent = pollNextPendingInputEvent();
            if (pendingInputEvent != null) {
                processInputEvent(key, pendingInputEvent);
            }
        } else if (stateManager.hasMoreActionTasks()) {
            // If the current key has additional action tasks remaining, we should submit a new mail
            // to continue processing them.
            mailboxExecutor.submit(
                    () ->
                            tryProcessActionTaskForKey(
                                    key, contextKey, actionTask.getTraceContext().getInputRunId()),
                    "process action task");
        }
    }

    @Override
    public void endInput() throws Exception {
        waitInFlightEventsFinished();
    }

    @VisibleForTesting
    public void waitInFlightEventsFinished() throws Exception {
        checkMailboxThread();
        while (true) {
            boolean hasProcessingKeys;
            if (parallelExecutionLock != null) {
                // Worker-pool engine: read under the lock so concurrent worker commits settle.
                parallelExecutionLock.acquireByMain();
                try {
                    hasProcessingKeys = stateManager.hasProcessingKeys();
                } finally {
                    parallelExecutionLock.release();
                }
            } else {
                hasProcessingKeys = stateManager.hasProcessingKeys();
            }
            if (!hasProcessingKeys) {
                return;
            }
            mailboxExecutor.yield();
        }
    }

    @Override
    public void close() throws Exception {
        // Close every component even when an earlier one fails, so a failing close cannot leak
        // the components behind it or skip super.close(). The first failure is rethrown with
        // the later ones suppressed. Order is preserved: the resource cache must close before
        // pythonInterpreter since cached resources may hold Python references.
        //
        // The ladder catches Throwable, not Exception, and IOUtils.closeAll is deliberately not
        // used: both stop at the first non-Exception Throwable without closing what follows,
        // which is the very leak this method has to avoid.
        Throwable firstFailure = null;
        for (AutoCloseable closeable :
                new AutoCloseable[] {
                    executionCoordinator,
                    resourceCache,
                    contextManager,
                    pythonBridge,
                    eventLogWriter,
                    durableExecManager
                }) {
            if (closeable == null) {
                continue;
            }
            try {
                closeable.close();
            } catch (Throwable t) {
                firstFailure = ExceptionUtils.firstOrSuppressed(t, firstFailure);
            }
        }

        try {
            super.close();
        } catch (Throwable t) {
            firstFailure = ExceptionUtils.firstOrSuppressed(t, firstFailure);
        }

        if (firstFailure != null) {
            ExceptionUtils.rethrowException(firstFailure);
        }
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);

        int maxParallelism = getRuntimeContext().getTaskInfo().getMaxNumberOfParallelSubtasks();
        durableExecManager.maybeInitActionStateStore(
                agentPlan.getConfig(), maxParallelism, getActionStateKeySerializer());

        stateManager = new OperatorStateManager();

        // Drop action-state records owned by other subtasks during rebuild. UnionListState
        // broadcasts every subtask's recovery marker, so a naive replay would load all keys into
        // every subtask's cache, where the foreign ones are never pruned (orphan-state leak).
        //
        // The ownership filter operates on the key-group embedded in the action-state record key.
        // The key-group was computed from the original typed key via
        // KeyGroupRangeAssignment.assignToKeyGroup, which matches how Flink assigns keyed-state
        // ownership. This avoids the type-dependent hashing mismatch that would occur if ownership
        // were reconstructed from the string form of the business key (e.g., Long(1) hashes to
        // key-group 86 while String("1") hashes to 54).
        KeyGroupRange currentSubtaskKeyGroupRange =
                stateManager.getCurrentSubtaskKeyGroupRange(maxParallelism, getRuntimeContext());
        IntPredicate ownershipFilter = currentSubtaskKeyGroupRange::contains;

        durableExecManager.handleRecovery(getOperatorStateBackend(), ownershipFilter);

        // Resolve the agent's stable job identifier:
        //  - If the user set it via AgentConfigOptions.JOB_IDENTIFIER, use that.
        //  - Otherwise fall back to the current Flink JobID, cached in operator
        //    state so the value remains stable across job restarts (Flink
        //    generates a fresh JobID on each restart).
        jobIdentifier = agentPlan.getConfig().get(JOB_IDENTIFIER);
        if (jobIdentifier == null) {
            String initialJobIdentifier = getRuntimeContext().getJobInfo().getJobId().toString();
            jobIdentifier =
                    StateUtils.getSingleValueFromState(
                            context, "identifier_state", String.class, initialJobIdentifier);
        }
    }

    private TypeSerializer<?> getActionStateKeySerializer() {
        return getKeyedStateBackend().getKeySerializer();
    }

    @Override
    public void prepareSnapshotPreBarrier(long checkpointId) throws Exception {
        if (!parallelExecutionWithoutCoroutineEnabled) {
            super.prepareSnapshotPreBarrier(checkpointId);
            return;
        }
        // Quiesce pulled-but-uncommitted tasks before the barrier (their results only live in
        // memory and would be lost on restore): pause dispatch, then yield without the lock
        // until everything in flight has committed.
        try {
            executionCoordinator.startDraining();
            while (!executionCoordinator.isQuiesced()) {
                mailboxExecutor.yield();
            }
            super.prepareSnapshotPreBarrier(checkpointId);
        } catch (Exception e) {
            // A failed pre-barrier never reaches snapshotState's stopDraining; resume dispatch.
            try {
                executionCoordinator.stopDraining();
            } catch (Exception resumeFailure) {
                e.addSuppressed(resumeFailure);
            }
            throw e;
        }
    }

    @Override
    public OperatorSnapshotFutures snapshotState(
            long checkpointId,
            long timestamp,
            CheckpointOptions checkpointOptions,
            CheckpointStreamFactory storageLocation)
            throws Exception {
        if (!parallelExecutionWithoutCoroutineEnabled) {
            return super.snapshotState(checkpointId, timestamp, checkpointOptions, storageLocation);
        }
        try {
            return super.snapshotState(checkpointId, timestamp, checkpointOptions, storageLocation);
        } finally {
            // Resume dispatch only after the synchronous snapshot capture: a worker woken any
            // earlier could pull a queued task out of keyed state before it is captured.
            executionCoordinator.stopDraining();
        }
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        if (parallelExecutionWithoutCoroutineEnabled) {
            runWithParallelExecutionLock(() -> snapshotStateInternal(context));
        } else {
            snapshotStateInternal(context);
        }
    }

    private void snapshotStateInternal(StateSnapshotContext context) throws Exception {
        durableExecManager.snapshotRecoveryMarker();
        durableExecManager.snapshotLastCompletedSequenceNumbers(
                getKeyedStateBackend(), context.getCheckpointId());

        super.snapshotState(context);
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws Exception {
        if (parallelExecutionWithoutCoroutineEnabled) {
            runWithParallelExecutionLock(() -> notifyCheckpointCompleteInternal(checkpointId));
        } else {
            notifyCheckpointCompleteInternal(checkpointId);
        }
    }

    private void notifyCheckpointCompleteInternal(long checkpointId) throws Exception {
        durableExecManager.notifyCheckpointComplete(checkpointId);
        super.notifyCheckpointComplete(checkpointId);
    }

    @Override
    public void notifyCheckpointAborted(long checkpointId) throws Exception {
        if (parallelExecutionWithoutCoroutineEnabled) {
            runWithParallelExecutionLock(() -> notifyCheckpointAbortedInternal(checkpointId));
        } else {
            notifyCheckpointAbortedInternal(checkpointId);
        }
    }

    private void notifyCheckpointAbortedInternal(long checkpointId) throws Exception {
        durableExecManager.notifyCheckpointAborted(checkpointId);
        super.notifyCheckpointAborted(checkpointId);
    }

    private MailboxProcessor getMailboxProcessor() throws Exception {
        Field field = MailboxExecutorImpl.class.getDeclaredField("mailboxProcessor");
        field.setAccessible(true);
        return (MailboxProcessor) field.get(mailboxExecutor);
    }

    private void checkMailboxThread() {
        checkState(
                mailboxProcessor.isMailboxThread(),
                "Expected to be running on the task mailbox thread, but was not.");
    }

    private void notifyActionStarted(ActionTask actionTask) {
        if (actionTask.hasExecutionStartedEventEmitted()) {
            return;
        }
        for (TaskLifecycleListener listener : taskLifecycleListeners) {
            listener.onActionStarted(actionTask);
        }
        actionTask.markExecutionStartedEventEmitted();
    }

    private void registerBuiltInLifecycleListeners() {
        addTaskLifecycleListener(
                new EventLogTaskLifecycleListener(
                        (eventContext, event, traceContext) ->
                                observeExecutionEvent(
                                        traceContext.getEntityName(),
                                        eventContext,
                                        event,
                                        traceContext)));
    }

    /**
     * Builds the component execution listeners of one action execution: the per-execution event log
     * adapter first, followed by the globally registered listeners.
     */
    private List<ComponentExecutionListener> createComponentListeners(ActionTask actionTask) {
        List<ComponentExecutionListener> listeners = new ArrayList<>();
        listeners.add(
                new EventLogComponentExecutionListener(
                        actionTask.getTraceContext(),
                        (eventContext, event, traceContext) ->
                                observeExecutionEvent(
                                        actionTask.getAction().getName(),
                                        eventContext,
                                        event,
                                        traceContext)));
        listeners.addAll(componentExecutionListeners);
        return listeners;
    }

    private void observeExecutionEvent(
            String actionName,
            EventContext eventContext,
            Event event,
            ExecutionTraceContext traceContext) {
        executionEventLogger.emit(eventContext, event, traceContext);
        builtInMetrics.markExecutionEvent(actionName, eventContext, event, traceContext);
    }

    /**
     * Materializes every sub-agent setup, in either language, and registers the ones that observe
     * the task lifecycle. A Java setup joins this operator's listeners directly; a Python setup
     * lives in the Python runtime, so it joins the Python runtime's listeners and this operator
     * notifies them through a single bridge listener.
     *
     * <p>Runs while the operator opens, after the Python bridge is up, because the Python runtime
     * materializes the setups it owns.
     */
    private void registerSubagentSetups() throws Exception {
        boolean pythonSetupRegistered = false;
        for (Resource setup : resourceCache.eagerMaterialize(ResourceType.AGENT)) {
            if (setup instanceof PythonRuntimeResource) {
                pythonSetupRegistered |=
                        pythonBridge
                                .getPythonActionExecutor()
                                .addTaskLifecycleListener(
                                        ((PythonRuntimeResource) setup).getPythonResource());
            } else if (setup instanceof TaskLifecycleListener) {
                addTaskLifecycleListener((TaskLifecycleListener) setup);
            }
        }
        if (pythonSetupRegistered) {
            addTaskLifecycleListener(
                    new PythonTaskLifecycleListener(pythonBridge.getPythonActionExecutor()));
        }
    }

    /**
     * Registers a listener to be notified of per-record/per-action lifecycle events. The
     * registration itself is not part of the operator state, so it must happen before records are
     * processed.
     */
    public void addTaskLifecycleListener(TaskLifecycleListener listener) {
        taskLifecycleListeners.add(listener);
    }

    /**
     * Registers a listener to be notified of component execution reports of every action execution.
     * The registration itself is not part of the operator state, so it must happen before records
     * are processed.
     */
    public void addComponentExecutionListener(ComponentExecutionListener listener) {
        componentExecutionListeners.add(listener);
    }

    private void notifyRecordStart(Object key) {
        for (TaskLifecycleListener listener : taskLifecycleListeners) {
            listener.onRecordStart(key);
        }
    }

    private void notifyActionPrepared(ActionTask task) {
        for (TaskLifecycleListener listener : taskLifecycleListeners) {
            listener.onActionPrepared(task);
        }
    }

    private void notifyActionTransferred(ActionTask from, ActionTask to) {
        for (TaskLifecycleListener listener : taskLifecycleListeners) {
            listener.onActionTransferred(from, to);
        }
    }

    private void notifyActionFinishing(ActionTask task) {
        for (TaskLifecycleListener listener : taskLifecycleListeners) {
            listener.onActionFinishing(task);
        }
    }

    private void notifyActionFinished(ActionTask task) {
        for (TaskLifecycleListener listener : taskLifecycleListeners) {
            listener.onActionFinished(task);
        }
    }

    private void notifyActionReused(ActionTask task) {
        for (TaskLifecycleListener listener : taskLifecycleListeners) {
            listener.onActionReused(task);
        }
    }

    private void notifyActionFailed(ActionTask task, Throwable error) {
        for (TaskLifecycleListener listener : taskLifecycleListeners) {
            try {
                listener.onActionFailed(task, error);
            } catch (Throwable listenerError) {
                if (listenerError != error) {
                    error.addSuppressed(listenerError);
                }
            }
        }
    }

    private void notifyRecordFinished(Object key) {
        for (TaskLifecycleListener listener : taskLifecycleListeners) {
            listener.onRecordFinished(key);
        }
    }

    private ActionTask createActionTask(
            Object key,
            Action action,
            Event event,
            long sequenceNumber,
            ExecutionTraceContext sourceTraceContext) {
        ExecutionTraceContext actionTraceContext =
                ExecutionTraceContext.forAction(sourceTraceContext, action.getName());
        if (action.getExec() instanceof JavaFunction) {
            return new JavaActionTask(key, event, action, sequenceNumber, actionTraceContext);
        } else if (action.getExec() instanceof PythonFunction) {
            return new PythonActionTask(key, event, action, sequenceNumber, actionTraceContext);
        } else {
            throw new IllegalStateException(
                    "Unsupported action type: " + action.getExec().getClass());
        }
    }

    /** Returns one textual context key for Java and PyFlink keyed streams. */
    private String resolveContextKey(Object key) {
        PythonActionExecutor pythonActionExecutor =
                pythonBridge == null ? null : pythonBridge.getPythonActionExecutor();
        return resolveContextKey(key, inputIsJava, pythonKeyIsPickled, pythonActionExecutor);
    }

    @VisibleForTesting
    static String resolveContextKey(
            Object key,
            boolean inputIsJava,
            boolean pythonKeyIsPickled,
            @Nullable PythonActionExecutor pythonActionExecutor) {
        if (inputIsJava) {
            return String.valueOf(key);
        }
        checkState(
                pythonActionExecutor != null,
                "PythonActionExecutor must be initialized for a PyFlink keyed stream");
        return pythonActionExecutor.resolveKeyText(key, pythonKeyIsPickled);
    }

    private void enqueuePendingInputEvent(Event event) throws Exception {
        stateManager.addPendingInputEvent(event);
        builtInMetrics.markPendingInputEventEnqueued();
    }

    @Nullable
    private Event pollNextPendingInputEvent() throws Exception {
        Event event = stateManager.pollNextPendingInputEvent();
        if (event != null) {
            builtInMetrics.markPendingInputEventDequeued();
        }
        return event;
    }

    private void enqueueActionTask(ActionTask actionTask) throws Exception {
        stateManager.addActionTask(actionTask);
        builtInMetrics.markActionTaskEnqueued(
                actionTask.getTraceContext(), actionTask.hasExecutionStartedEventEmitted());
    }

    @Nullable
    private ActionTask pollNextActionTask() throws Exception {
        ActionTask actionTask = stateManager.pollNextActionTask();
        if (actionTask != null && !isInternalNoopInputAction(actionTask.action)) {
            builtInMetrics.markActionTaskDequeued(
                    actionTask.getTraceContext(), actionTask.hasExecutionStartedEventEmitted());
        }
        return actionTask;
    }

    private void tryResumeProcessActionTasks() throws Exception {
        Iterable<Object> keys = stateManager.getProcessingKeys();
        long activeInputRuns = 0L;
        if (keys != null) {
            int maxParallelism = getRuntimeContext().getTaskInfo().getMaxNumberOfParallelSubtasks();
            KeyGroupRange currentSubtaskKeyGroupRange =
                    stateManager.getCurrentSubtaskKeyGroupRange(
                            maxParallelism, getRuntimeContext());
            Set<Object> ownedKeys = new LinkedHashSet<>();
            for (Object key : keys) {
                if (!stateManager.isKeyOwnedByCurrentSubtask(
                        key, maxParallelism, currentSubtaskKeyGroupRange)) {
                    continue;
                }
                if (!ownedKeys.add(key)) {
                    continue;
                }
                eventRouter.getKeySegmentQueue().addKeyToLastSegment(key);
                if (parallelExecutionWithoutCoroutineEnabled) {
                    // Restored tasks were never enqueued through processEvent: add one node per
                    // queued task; the worker-completion path finishes the input (no kick mail).
                    setCurrentKey(key);
                    int queued = stateManager.countActionTasks();
                    for (int i = 0; i < queued; i++) {
                        executionCoordinator.addTask(key);
                    }
                } else {
                    // The normal engine has no workers; the kick mail drives
                    // processActionTaskForKey.
                    String contextKey = resolveContextKey(key);
                    // Align with the task-level replay: re-emit the record start for the resumed
                    // round so listeners observe a paired start/finished bracket as well.
                    notifyRecordStart(key);
                    mailboxExecutor.submit(
                            () -> tryProcessActionTaskForKey(key, contextKey, null),
                            "process action task");
                }
            }
            stateManager.replaceProcessingKeys(new ArrayList<>(ownedKeys));
            // Each recovered key has one active input record occupying in-flight budget; queued
            // pending records for these keys are added below.
            inFlightInputRecords = ownedKeys.size();
            activeInputRuns = ownedKeys.size();
        }
        builtInMetrics.restoreActiveInputRuns(activeInputRuns);

        stateManager.forEachActionTaskKey(
                getKeyedStateBackend(),
                (key, state) -> {
                    for (ActionTask actionTask : state.get()) {
                        builtInMetrics.restoreActionTask(
                                actionTask.getTraceContext(),
                                actionTask.hasExecutionStartedEventEmitted());
                    }
                });

        long[] pendingInputEvents = {0L};
        // Recovered pending input records will each be dequeued, processed, and decremented when
        // they finish, so they must occupy in-flight budget too — count one unit per pending event.
        stateManager.forEachPendingInputEventKey(
                getKeyedStateBackend(),
                (key, state) ->
                        state.get()
                                .forEach(
                                        event -> {
                                            inFlightInputRecords++;
                                            pendingInputEvents[0]++;
                                            eventRouter
                                                    .getKeySegmentQueue()
                                                    .addKeyToLastSegment(key);
                                        }));
        builtInMetrics.restorePendingInputEvents(pendingInputEvents[0]);
    }

    private void runWithParallelExecutionLock(ThrowingRunnable<? extends Exception> action)
            throws Exception {
        boolean acquired = false;
        try {
            parallelExecutionLock.acquireByMain();
            acquired = true;
            action.run();
        } finally {
            if (acquired) {
                parallelExecutionLock.release();
            }
        }
    }

    /**
     * A single action task's full worker lifecycle on the parallel engine: a pool thread {@code
     * setup}s and {@code execute}s it under the lock; the mailbox thread later restores and commits
     * it. All task-specific processing lives here; the operator keeps only mailbox flow.
     */
    private final class Work implements ParallelExecutionTask {
        private Object key;
        private long recordIndex;
        private long taskIndex;
        private String contextKey;

        private ActionTask actionTask;
        private long sequenceNumber;
        private boolean replayCompletedAction;

        @Nullable private ActionTask.ActionTaskResult result;
        @Nullable private Throwable failure;

        @Override
        public void setup(Object key, long recordIndex, long taskIndex) {
            this.key = key;
            this.recordIndex = recordIndex;
            this.taskIndex = taskIndex;
            this.contextKey = resolveContextKey(key);
        }

        @Override
        public void execute() {
            try {
                // Pull + prepare under the lock hold: pairing the FIFO poll with the under-lock
                // taskIndex keeps the (taskIndex, task) binding correct across concurrent workers.
                // createAndSetRunnerContext already binds the context on this thread.
                setCurrentKey(key);
                ActionTask task = pollNextActionTask();
                checkState(task != null, "Action task queue was empty.");

                contextManager.createAndSetRunnerContext(
                        task,
                        contextKey,
                        agentPlan,
                        resourceCache,
                        metricGroup,
                        jobIdentifier,
                        parallelExecutionLock::checkReentrant,
                        stateManager.getSensoryMemState(),
                        stateManager.getShortTermMemState(),
                        pythonBridge.getPythonRunnerContext(),
                        ltm,
                        ActionExecutionOperator.this::createComponentListeners);

                // The synthetic noop input action stays lifecycle-silent.
                boolean lifecycleVisible = !isInternalNoopInputAction(task.action);
                if (lifecycleVisible) {
                    notifyActionPrepared(task);
                }

                long seq = stateManager.getSequenceNumber();
                ActionState state =
                        durableExecManager.maybeGetActionState(key, seq, task.action, task.event);
                boolean replay = state != null && state.isCompleted();
                if (!replay) {
                    if (state == null) {
                        durableExecManager.maybeInitActionState(key, seq, task.action, task.event);
                        state =
                                durableExecManager.maybeGetActionState(
                                        key, seq, task.action, task.event);
                    }
                    durableExecManager.setupDurableExecutionContext(task, state, seq);
                }

                // Recording the key and action task on the continuation context uses its
                // JDK<21-only
                // internals, so it lives on the operator's JDK<21 parallel MVP path and is never
                // reached on JDK 21.
                ContinuationContext context =
                        checkNotNull(
                                contextManager.getContinuationContext(task),
                                "Missing continuation context for action task");
                context.setKey(key);
                context.setActionTask(task);
                context.setPriority(recordIndex, taskIndex);

                this.actionTask = task;
                this.sequenceNumber = seq;
                this.replayCompletedAction = replay;

                // Run: replay a durably-completed action from its persisted state, or invoke it.
                if (replay) {
                    MemoryUpdateReplayer.replay(
                            task.getRunnerContext().getShortTermMemory(),
                            state.getShortTermMemoryUpdates());
                    MemoryUpdateReplayer.replay(
                            task.getRunnerContext().getSensoryMemory(),
                            state.getSensoryMemoryUpdates());
                    if (lifecycleVisible) {
                        notifyActionReused(task);
                    }
                    result = task.new ActionTaskResult(true, state.getOutputEvents(), null);
                } else {
                    if (lifecycleVisible) {
                        notifyActionStarted(task);
                    }
                    try {
                        if (task instanceof JavaActionTask) {
                            ((JavaActionTask) task).setEventSerializer(eventSerializer);
                        }
                        result =
                                task.invoke(
                                        getRuntimeContext().getUserCodeClassLoader(),
                                        pythonBridge.getPythonActionExecutor());
                    } catch (Throwable actionFailure) {
                        try {
                            task.getRunnerContext().discardMemoryObservation();
                        } catch (Throwable discardFailure) {
                            if (discardFailure != actionFailure) {
                                actionFailure.addSuppressed(discardFailure);
                            }
                        }
                        if (lifecycleVisible) {
                            try {
                                notifyActionFailed(task, actionFailure);
                            } catch (Throwable notifyFailure) {
                                if (notifyFailure != actionFailure) {
                                    actionFailure.addSuppressed(notifyFailure);
                                }
                            }
                        }
                        throw actionFailure;
                    }
                }
            } catch (Throwable t) {
                failure = t;
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void restoreContext() {
            parallelExecutionLock.checkReentrant();
            if (failure != null) {
                // Preparation failed before a context existed; commit() will rethrow the failure.
                return;
            }
            contextManager.restore(key, actionTask);
        }

        @Override
        public boolean isDone() {
            return result != null || failure != null;
        }

        @Override
        public void commit() throws Exception {
            if (failure != null) {
                throw new ActionTaskExecutionException("Failed to execute action task", failure);
            }
            checkState(result != null, "Action task completion result must not be null.");
            // On this engine an action always finishes in one invoke (the base executor runs
            // durableExecuteAsync inline); an unfinished result would mean the Loom overlay or a
            // non-Java task leaked in — fail fast rather than run an untested branch.
            checkState(
                    result.isFinished(),
                    "Parallel-engine action did not finish in one invoke: %s",
                    actionTask.action.getName());

            // The synthetic noop input action stays lifecycle-silent.
            boolean lifecycleVisible = !isInternalNoopInputAction(actionTask.action);
            try {
                contextManager.removeContexts(actionTask);
                durableExecManager.removeDurableContext(actionTask);

                if (!replayCompletedAction) {
                    if (lifecycleVisible) {
                        notifyActionFinishing(actionTask);
                    }
                    durableExecManager.maybePersistTaskResult(
                            key,
                            sequenceNumber,
                            actionTask.action,
                            actionTask.event,
                            actionTask.getRunnerContext(),
                            result);
                    if (lifecycleVisible) {
                        notifyActionFinished(actionTask);
                    }
                }
            } catch (Throwable t) {
                if (lifecycleVisible) {
                    notifyActionFailed(actionTask, t);
                }
                throw new ActionTaskExecutionException("Failed to execute action task", t);
            }

            for (Event actionOutputEvent : result.getOutputEvents()) {
                try {
                    processEvent(key, contextKey, actionOutputEvent, actionTask.getTraceContext());
                } catch (Throwable t) {
                    // Mirror the serial kick-mail wrapper (tryProcessActionTaskForKey): surface
                    // output-event processing failures as ActionTaskExecutionException so callers
                    // observe a uniform cause. The action itself already finished, so it is not
                    // reported failed.
                    throw new ActionTaskExecutionException("Failed to execute action task", t);
                }
            }

            if (!isInternalNoopInputAction(actionTask.action)) {
                builtInMetrics.markActionExecuted(actionTask.action.getName());
            }
            actionTask.getRunnerContext().persistMemory();
        }

        @Override
        public void finishGroup(@Nullable ParallelExecutionTask lastCommitted) throws Exception {
            maybeFinishCurrentInput(key, (Work) lastCommitted);
        }
    }

    @VisibleForTesting
    DurableExecutionManager getDurableExecutionManager() {
        return durableExecManager;
    }

    @VisibleForTesting
    EventRouter<IN, OUT> getEventRouter() {
        return eventRouter;
    }

    @VisibleForTesting
    OperatorStateManager getOperatorStateManager() {
        return stateManager;
    }

    /** Failed to execute Action task. */
    public static class ActionTaskExecutionException extends Exception {
        public ActionTaskExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
