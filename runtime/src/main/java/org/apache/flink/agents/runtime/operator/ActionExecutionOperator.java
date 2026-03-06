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
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.api.listener.EventListener;
import org.apache.flink.agents.api.logger.EventLogLevel;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.api.logger.EventLoggerConfig;
import org.apache.flink.agents.api.logger.EventLoggerFactory;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.actionstate.KafkaActionStateStore;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.context.ActionStatePersister;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.env.EmbeddedPythonEnvironment;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.agents.runtime.eventlog.FileEventLogger;
import org.apache.flink.agents.runtime.memory.CachedMemoryStore;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.metrics.BuiltInMetrics;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.operator.queue.SegmentedQueue;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.apache.flink.agents.runtime.python.operator.PythonActionTask;
import org.apache.flink.agents.runtime.python.utils.JavaResourceAdapter;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.apache.flink.agents.runtime.utils.EventUtil;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.python.env.PythonDependencyInfo;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.runtime.state.VoidNamespace;
import org.apache.flink.runtime.state.VoidNamespaceSerializer;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxExecutorImpl;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxProcessor;
import org.apache.flink.types.Row;
import org.apache.flink.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pemja.core.PythonInterpreter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.ACTION_STATE_STORE_BACKEND;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.BASE_LOG_DIR;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.EVENT_LOG_LEVEL;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.EVENT_LOG_LEVELS;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.EVENT_LOG_MAX_FIELD_LENGTH;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.JOB_IDENTIFIER;
import static org.apache.flink.agents.runtime.actionstate.ActionStateStore.BackendType.KAFKA;
import static org.apache.flink.agents.runtime.utils.StateUtil.*;
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
        implements OneInputStreamOperator<IN, OUT>, BoundedOneInput, ActionStatePersister {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ActionExecutionOperator.class);

    private static final String RECOVERY_MARKER_STATE_NAME = "recoveryMarker";
    private static final String MESSAGE_SEQUENCE_NUMBER_STATE_NAME = "messageSequenceNumber";
    private static final String PENDING_INPUT_EVENT_STATE_NAME = "pendingInputEvents";

    private final AgentPlan agentPlan;

    private final Boolean inputIsJava;

    private transient StreamRecord<OUT> reusedStreamRecord;

    private transient MapState<String, MemoryObjectImpl.MemoryItem> sensoryMemState;

    private transient MapState<String, MemoryObjectImpl.MemoryItem> shortTermMemState;

    private transient PythonEnvironmentManager pythonEnvironmentManager;

    private transient PythonInterpreter pythonInterpreter;

    // PythonActionExecutor for Python actions
    private transient PythonActionExecutor pythonActionExecutor;

    // RunnerContext for Python actions
    private transient PythonRunnerContextImpl pythonRunnerContext;

    // PythonResourceAdapter for Python resources in Java actions
    private transient PythonResourceAdapterImpl pythonResourceAdapter;

    // PythonResourceAdapter for Java resources in Python actions or Python resources
    private transient JavaResourceAdapter javaResourceAdapter;

    private transient FlinkAgentsMetricGroupImpl metricGroup;

    private transient BuiltInMetrics builtInMetrics;

    private transient SegmentedQueue keySegmentQueue;

    private final transient MailboxExecutor mailboxExecutor;

    // RunnerContext for Java Actions
    private transient RunnerContextImpl runnerContext;

    // We need to check whether the current thread is the mailbox thread using the mailbox
    // processor.
    // TODO: This is a temporary workaround. In the future, we should add an interface in
    // MailboxExecutor to check whether a thread is a mailbox thread, rather than using reflection
    // to obtain the MailboxProcessor instance and make the determination.
    private transient MailboxProcessor mailboxProcessor;

    // An action will be split into one or more ActionTask objects. We use a state to store the
    // pending ActionTasks that are waiting to be executed.
    private transient ListState<ActionTask> actionTasksKState;

    // To avoid processing different InputEvents with the same key, we use a state to store pending
    // InputEvents that are waiting to be processed.
    private transient ListState<Event> pendingInputEventsKState;

    // An operator state is used to track the currently processing keys. This is useful when
    // receiving an EndOfInput signal, as we need to wait until all related events are fully
    // processed.
    private transient ListState<Object> currentProcessingKeysOpState;

    private final transient EventLogger eventLogger;
    private final transient List<EventListener> eventListeners;

    private transient ActionStateStore actionStateStore;
    private transient ValueState<Long> sequenceNumberKState;
    private transient ListState<Object> recoveryMarkerOpState;
    private transient Map<Long, Map<Object, Long>> checkpointIdToSeqNums;

    // This in memory map keep track of the runner context for the async action task that having
    // been finished
    private final transient Map<ActionTask, RunnerContextImpl.MemoryContext>
            actionTaskMemoryContexts;

    // This in memory map keeps track of the durable execution context for async action tasks
    // that have not been finished, allowing recovery of currentCallIndex across invocations
    private final transient Map<ActionTask, RunnerContextImpl.DurableExecutionContext>
            actionTaskDurableContexts;

    private final transient Map<ActionTask, ContinuationContext> continuationContexts;

    private final transient Map<ActionTask, String> pythonAwaitableRefs;

    // Each job can only have one identifier and this identifier must be consistent across restarts.
    // We cannot use job id as the identifier here because user may change job id by
    // creating a savepoint, stop the job and then resume from savepoint.
    // We use this identifier to control the visibility for long-term memory.
    // Inspired by Apache Paimon.
    private transient String jobIdentifier;

    private transient ContinuationActionExecutor continuationActionExecutor;

    public ActionExecutionOperator(
            AgentPlan agentPlan,
            Boolean inputIsJava,
            ProcessingTimeService processingTimeService,
            MailboxExecutor mailboxExecutor,
            ActionStateStore actionStateStore) {
        this.agentPlan = agentPlan;
        this.inputIsJava = inputIsJava;
        this.processingTimeService = processingTimeService;
        this.mailboxExecutor = mailboxExecutor;
        this.eventLogger = createEventLogger(agentPlan);
        this.eventListeners = new ArrayList<>();
        this.actionStateStore = actionStateStore;
        this.checkpointIdToSeqNums = new HashMap<>();
        this.actionTaskMemoryContexts = new HashMap<>();
        this.actionTaskDurableContexts = new HashMap<>();
        this.continuationContexts = new HashMap<>();
        this.pythonAwaitableRefs = new HashMap<>();
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
        reusedStreamRecord = new StreamRecord<>(null);
        // init sensoryMemState
        MapStateDescriptor<String, MemoryObjectImpl.MemoryItem> sensoryMemStateDescriptor =
                new MapStateDescriptor<>(
                        "sensoryMemory",
                        TypeInformation.of(String.class),
                        TypeInformation.of(MemoryObjectImpl.MemoryItem.class));
        sensoryMemState = getRuntimeContext().getMapState(sensoryMemStateDescriptor);

        // init shortTermMemState
        MapStateDescriptor<String, MemoryObjectImpl.MemoryItem> shortTermMemStateDescriptor =
                new MapStateDescriptor<>(
                        "shortTermMemory",
                        TypeInformation.of(String.class),
                        TypeInformation.of(MemoryObjectImpl.MemoryItem.class));
        shortTermMemState = getRuntimeContext().getMapState(shortTermMemStateDescriptor);

        metricGroup = new FlinkAgentsMetricGroupImpl(getMetricGroup());
        builtInMetrics = new BuiltInMetrics(metricGroup, agentPlan);

        keySegmentQueue = new SegmentedQueue();

        maybeInitActionStateStore();

        if (actionStateStore != null) {
            // init recovery marker state for recovery marker persistence
            recoveryMarkerOpState =
                    getOperatorStateBackend()
                            .getUnionListState(
                                    new ListStateDescriptor<>(
                                            RECOVERY_MARKER_STATE_NAME,
                                            TypeInformation.of(Object.class)));
        }
        // init sequence number state for per key message ordering
        sequenceNumberKState =
                getRuntimeContext()
                        .getState(
                                new ValueStateDescriptor<>(
                                        MESSAGE_SEQUENCE_NUMBER_STATE_NAME, Long.class));

        // init agent processing related state
        actionTasksKState =
                getRuntimeContext()
                        .getListState(
                                new ListStateDescriptor<>(
                                        "actionTasks", TypeInformation.of(ActionTask.class)));
        pendingInputEventsKState =
                getRuntimeContext()
                        .getListState(
                                new ListStateDescriptor<>(
                                        PENDING_INPUT_EVENT_STATE_NAME,
                                        TypeInformation.of(Event.class)));
        // We use UnionList here to ensure that the task can access all keys after parallelism
        // modifications.
        // Subsequent steps {@link #tryResumeProcessActionTasks} will then filter out keys that do
        // not belong to the key range of current task.
        currentProcessingKeysOpState =
                getOperatorStateBackend()
                        .getUnionListState(
                                new ListStateDescriptor<>(
                                        "currentProcessingKeys", TypeInformation.of(Object.class)));

        // init PythonActionExecutor and PythonResourceAdapter
        initPythonEnvironment();

        // init executor for Java async execution
        continuationActionExecutor =
                new ContinuationActionExecutor(
                        agentPlan.getConfig().get(AgentExecutionOptions.NUM_ASYNC_THREADS));

        mailboxProcessor = getMailboxProcessor();

        // Initialize the event logger if it is set.
        initEventLogger(getRuntimeContext());

        // Since an operator restart may change the key range it manages due to changes in
        // parallelism,
        // and {@link tryProcessActionTaskForKey} mails might be lost,
        // it is necessary to reprocess all keys to ensure correctness.
        tryResumeProcessActionTasks();
    }

    private void initEventLogger(StreamingRuntimeContext runtimeContext) throws Exception {
        if (eventLogger == null) {
            return;
        }
        eventLogger.open(new EventLoggerOpenParams(runtimeContext));
    }

    @Override
    public void processWatermark(Watermark mark) throws Exception {
        keySegmentQueue.addWatermark(mark);
        processEligibleWatermarks();
    }

    @Override
    public void processElement(StreamRecord<IN> record) throws Exception {
        IN input = record.getValue();
        LOG.debug("Receive an element {}", input);

        // wrap to InputEvent first
        Event inputEvent = wrapToInputEvent(input);
        if (record.hasTimestamp()) {
            inputEvent.setSourceTimestamp(record.getTimestamp());
        }

        keySegmentQueue.addKeyToLastSegment(getCurrentKey());

        if (currentKeyHasMoreActionTask()) {
            // If there are already actions being processed for the current key, the newly incoming
            // event should be queued and processed later. Therefore, we add it to
            // pendingInputEventsState.
            pendingInputEventsKState.add(inputEvent);
        } else {
            // Otherwise, the new event is processed immediately.
            processEvent(getCurrentKey(), inputEvent);
        }
    }

    /**
     * Processes an incoming event for the given key and may submit a new mail
     * `tryProcessActionTaskForKey` to continue processing.
     */
    private void processEvent(Object key, Event event) throws Exception {
        notifyEventProcessed(event);

        boolean isInputEvent = EventUtil.isInputEvent(event);
        if (EventUtil.isOutputEvent(event)) {
            // If the event is an OutputEvent, we send it downstream.
            OUT outputData = getOutputFromOutputEvent(event);
            if (event.hasSourceTimestamp()) {
                output.collect(reusedStreamRecord.replace(outputData, event.getSourceTimestamp()));
            } else {
                reusedStreamRecord.eraseTimestamp();
                output.collect(reusedStreamRecord.replace(outputData));
            }
        } else {
            if (isInputEvent) {
                // If the event is an InputEvent, we mark that the key is currently being processed.
                currentProcessingKeysOpState.add(key);
                initOrIncSequenceNumber();
            }
            // We then obtain the triggered action and add ActionTasks to the waiting processing
            // queue.
            List<Action> triggerActions = getActionsTriggeredBy(event);
            if (triggerActions != null && !triggerActions.isEmpty()) {
                for (Action triggerAction : triggerActions) {
                    actionTasksKState.add(createActionTask(key, triggerAction, event));
                }
            }
        }

        if (isInputEvent) {
            // If the event is an InputEvent, we submit a new mail to try processing the actions.
            mailboxExecutor.submit(() -> tryProcessActionTaskForKey(key), "process action task");
        }
    }

    private void notifyEventProcessed(Event event) throws Exception {
        EventContext eventContext = new EventContext(event);
        if (eventLogger != null) {
            // If event logging is enabled, we log the event along with its context.
            eventLogger.append(eventContext, event);
            // For now, we flush the event logger after each event to ensure immediate logging.
            // This is a temporary solution to ensure that events are logged immediately.
            // TODO: In the future, we may want to implement a more efficient batching mechanism.
            eventLogger.flush();
        }
        if (eventListeners != null) {
            // Notify all registered event listeners about the event.
            for (EventListener listener : eventListeners) {
                listener.onEventProcessed(eventContext, event);
            }
        }
        builtInMetrics.markEventProcessed();
    }

    private void tryProcessActionTaskForKey(Object key) {
        try {
            processActionTaskForKey(key);
        } catch (Exception e) {
            mailboxExecutor.execute(
                    () ->
                            ExceptionUtils.rethrow(
                                    new ActionTaskExecutionException(
                                            "Failed to execute action task", e)),
                    "throw exception in mailbox");
        }
    }

    private void processActionTaskForKey(Object key) throws Exception {
        // 1. Get an action task for the key.
        setCurrentKey(key);

        ActionTask actionTask = pollFromListState(actionTasksKState);
        if (actionTask == null) {
            int removedCount = removeFromListState(currentProcessingKeysOpState, key);
            checkState(
                    removedCount == 1,
                    "Current processing key count for key "
                            + key
                            + " should be 1, but got "
                            + removedCount);
            checkState(
                    keySegmentQueue.removeKey(key),
                    "Current key" + key + " is missing from the segmentedQueue.");
            processEligibleWatermarks();
            return;
        }

        // 2. Invoke the action task.
        createAndSetRunnerContext(actionTask, key);

        long sequenceNumber = sequenceNumberKState.value();
        boolean isFinished;
        List<Event> outputEvents;
        Optional<ActionTask> generatedActionTaskOpt = Optional.empty();
        ActionState actionState =
                maybeGetActionState(key, sequenceNumber, actionTask.action, actionTask.event);

        // Check if action is already completed
        if (actionState != null && actionState.isCompleted()) {
            // Action has completed, skip execution and replay memory/events
            LOG.debug(
                    "Skipping already completed action: {} for key: {}",
                    actionTask.action.getName(),
                    key);
            isFinished = true;
            outputEvents = actionState.getOutputEvents();
            for (MemoryUpdate memoryUpdate : actionState.getShortTermMemoryUpdates()) {
                actionTask
                        .getRunnerContext()
                        .getShortTermMemory()
                        .set(memoryUpdate.getPath(), memoryUpdate.getValue());
            }

            for (MemoryUpdate memoryUpdate : actionState.getSensoryMemoryUpdates()) {
                actionTask
                        .getRunnerContext()
                        .getSensoryMemory()
                        .set(memoryUpdate.getPath(), memoryUpdate.getValue());
            }
        } else {
            // Initialize ActionState if not exists, or use existing one for recovery
            if (actionState == null) {
                maybeInitActionState(key, sequenceNumber, actionTask.action, actionTask.event);
                actionState =
                        maybeGetActionState(
                                key, sequenceNumber, actionTask.action, actionTask.event);
            }

            // Set up durable execution context for fine-grained recovery
            setupDurableExecutionContext(actionTask, actionState);

            ActionTask.ActionTaskResult actionTaskResult =
                    actionTask.invoke(
                            getRuntimeContext().getUserCodeClassLoader(),
                            this.pythonActionExecutor);

            // We remove the contexts from the map after the task is processed. They will be added
            // back later if the action task has a generated action task, meaning it is not
            // finished.
            actionTaskMemoryContexts.remove(actionTask);
            actionTaskDurableContexts.remove(actionTask);
            continuationContexts.remove(actionTask);
            pythonAwaitableRefs.remove(actionTask);
            maybePersistTaskResult(
                    key,
                    sequenceNumber,
                    actionTask.action,
                    actionTask.event,
                    actionTask.getRunnerContext(),
                    actionTaskResult);
            isFinished = actionTaskResult.isFinished();
            outputEvents = actionTaskResult.getOutputEvents();
            generatedActionTaskOpt = actionTaskResult.getGeneratedActionTask();
        }

        for (Event actionOutputEvent : outputEvents) {
            processEvent(key, actionOutputEvent);
        }

        boolean currentInputEventFinished = false;
        if (isFinished) {
            builtInMetrics.markActionExecuted(actionTask.action.getName());
            currentInputEventFinished = !currentKeyHasMoreActionTask();

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
            actionTaskMemoryContexts.put(
                    generatedActionTask, actionTask.getRunnerContext().getMemoryContext());
            RunnerContextImpl.DurableExecutionContext durableContext =
                    actionTask.getRunnerContext().getDurableExecutionContext();
            if (durableContext != null) {
                actionTaskDurableContexts.put(generatedActionTask, durableContext);
            }
            if (actionTask.getRunnerContext() instanceof JavaRunnerContextImpl) {
                continuationContexts.put(
                        generatedActionTask,
                        ((JavaRunnerContextImpl) actionTask.getRunnerContext())
                                .getContinuationContext());
            }
            if (actionTask.getRunnerContext() instanceof PythonRunnerContextImpl) {
                String awaitableRef =
                        ((PythonRunnerContextImpl) actionTask.getRunnerContext())
                                .getPythonAwaitableRef();
                if (awaitableRef != null) {
                    pythonAwaitableRefs.put(generatedActionTask, awaitableRef);
                }
            }

            actionTasksKState.add(generatedActionTask);
        }

        // 3. Process the next InputEvent or next action task
        if (currentInputEventFinished) {
            // Clean up sensory memory when a single run finished.
            actionTask.getRunnerContext().clearSensoryMemory();

            // Once all sub-events and actions related to the current InputEvent are completed,
            // we can proceed to process the next InputEvent.
            int removedCount = removeFromListState(currentProcessingKeysOpState, key);
            maybePruneState(key, sequenceNumber);
            checkState(
                    removedCount == 1,
                    "Current processing key count for key "
                            + key
                            + " should be 1, but got "
                            + removedCount);
            checkState(
                    keySegmentQueue.removeKey(key),
                    "Current key" + key + " is missing from the segmentedQueue.");
            processEligibleWatermarks();
            Event pendingInputEvent = pollFromListState(pendingInputEventsKState);
            if (pendingInputEvent != null) {
                processEvent(key, pendingInputEvent);
            }
        } else if (currentKeyHasMoreActionTask()) {
            // If the current key has additional action tasks remaining, we should submit a new mail
            // to continue processing them.
            mailboxExecutor.submit(() -> tryProcessActionTaskForKey(key), "process action task");
        }
    }

    private Resource getResource(String name, ResourceType type) {
        try {
            return agentPlan.getResource(name, type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void initPythonEnvironment() throws Exception {
        boolean containPythonAction =
                agentPlan.getActions().values().stream()
                        .anyMatch(action -> action.getExec() instanceof PythonFunction);

        boolean containPythonResource =
                agentPlan.getResourceProviders().values().stream()
                        .anyMatch(
                                resourceProviderMap ->
                                        resourceProviderMap.values().stream()
                                                .anyMatch(
                                                        resourceProvider ->
                                                                resourceProvider
                                                                        instanceof
                                                                        PythonResourceProvider));

        if (containPythonAction || containPythonResource) {
            LOG.debug("Begin initialize PythonEnvironmentManager.");
            PythonDependencyInfo dependencyInfo =
                    PythonDependencyInfo.create(
                            getExecutionConfig().toConfiguration(),
                            getRuntimeContext().getDistributedCache());
            pythonEnvironmentManager =
                    new PythonEnvironmentManager(
                            dependencyInfo,
                            getContainingTask()
                                    .getEnvironment()
                                    .getTaskManagerInfo()
                                    .getTmpDirectories(),
                            new HashMap<>(System.getenv()),
                            getRuntimeContext().getJobInfo().getJobId());
            pythonEnvironmentManager.open();
            EmbeddedPythonEnvironment env = pythonEnvironmentManager.createEnvironment();
            pythonInterpreter = env.getInterpreter();
            pythonRunnerContext =
                    new PythonRunnerContextImpl(
                            this.metricGroup,
                            this::checkMailboxThread,
                            this.agentPlan,
                            this.jobIdentifier);

            javaResourceAdapter = new JavaResourceAdapter(this::getResource, pythonInterpreter);
            if (containPythonResource) {
                initPythonResourceAdapter();
            }
            if (containPythonAction) {
                initPythonActionExecutor();
            }
        }
    }

    private void initPythonActionExecutor() throws Exception {
        pythonActionExecutor =
                new PythonActionExecutor(
                        pythonInterpreter,
                        agentPlan,
                        javaResourceAdapter,
                        pythonRunnerContext,
                        jobIdentifier);
        pythonActionExecutor.open();
    }

    private void initPythonResourceAdapter() throws Exception {
        pythonResourceAdapter =
                new PythonResourceAdapterImpl(
                        (String anotherName, ResourceType anotherType) -> {
                            try {
                                return agentPlan.getResource(anotherName, anotherType);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        },
                        pythonInterpreter,
                        javaResourceAdapter);
        pythonResourceAdapter.open();
        agentPlan.setPythonResourceAdapter(pythonResourceAdapter);
    }

    @Override
    public void endInput() throws Exception {
        waitInFlightEventsFinished();
    }

    @VisibleForTesting
    public void waitInFlightEventsFinished() throws Exception {
        while (listStateNotEmpty(currentProcessingKeysOpState)) {
            mailboxExecutor.yield();
        }
    }

    @Override
    public void close() throws Exception {
        if (runnerContext != null) {
            try {
                runnerContext.close();
            } finally {
                runnerContext = null;
            }
        }
        if (pythonActionExecutor != null) {
            pythonActionExecutor.close();
        }
        if (pythonInterpreter != null) {
            pythonInterpreter.close();
        }
        if (pythonEnvironmentManager != null) {
            pythonEnvironmentManager.close();
        }
        if (eventLogger != null) {
            eventLogger.close();
        }
        if (actionStateStore != null) {
            actionStateStore.close();
        }
        if (runnerContext != null) {
            runnerContext.close();
        }
        if (continuationActionExecutor != null) {
            continuationActionExecutor.close();
        }

        super.close();
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);

        maybeInitActionStateStore();

        if (actionStateStore != null) {
            List<Object> markers = new ArrayList<>();

            // We use UnionList here to ensure that the task can access all the recovery marker
            // after
            // parallelism modifications.
            // The ActionStateStore will decide how to use the recovery markers.
            ListState<Object> recoveryMarkerOpState =
                    getOperatorStateBackend()
                            .getUnionListState(
                                    new ListStateDescriptor<>(
                                            RECOVERY_MARKER_STATE_NAME,
                                            TypeInformation.of(Object.class)));

            Iterable<Object> recoveryMarkers = recoveryMarkerOpState.get();
            if (recoveryMarkers != null) {
                recoveryMarkers.forEach(markers::add);
            }
            LOG.info("Rebuilding action state from {} recovery markers", markers.size());
            actionStateStore.rebuildState(markers);
        }

        // Get job identifier from user configuration.
        // If not configured, get from state.
        jobIdentifier = agentPlan.getConfig().get(JOB_IDENTIFIER);
        if (jobIdentifier == null) {
            String initialJobIdentifier = getRuntimeContext().getJobInfo().getJobId().toString();
            jobIdentifier =
                    StateUtils.getSingleValueFromState(
                            context, "identifier_state", String.class, initialJobIdentifier);
        }
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        if (actionStateStore != null) {
            Object recoveryMarker = actionStateStore.getRecoveryMarker();
            if (recoveryMarker != null) {
                recoveryMarkerOpState.update(List.of(recoveryMarker));
            }
        }

        HashMap<Object, Long> keyToSeqNum = new HashMap<>();
        getKeyedStateBackend()
                .applyToAllKeys(
                        VoidNamespace.INSTANCE,
                        VoidNamespaceSerializer.INSTANCE,
                        new ValueStateDescriptor<>(MESSAGE_SEQUENCE_NUMBER_STATE_NAME, Long.class),
                        (key, state) -> keyToSeqNum.put(key, state.value()));
        checkpointIdToSeqNums.put(context.getCheckpointId(), keyToSeqNum);

        super.snapshotState(context);
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws Exception {
        if (actionStateStore != null) {
            Map<Object, Long> keyToSeqNum =
                    checkpointIdToSeqNums.getOrDefault(checkpointId, new HashMap<>());
            for (Map.Entry<Object, Long> entry : keyToSeqNum.entrySet()) {
                actionStateStore.pruneState(entry.getKey(), entry.getValue());
            }
            checkpointIdToSeqNums.remove(checkpointId);
        }
        super.notifyCheckpointComplete(checkpointId);
    }

    private Event wrapToInputEvent(IN input) {
        if (inputIsJava) {
            return new InputEvent(input);
        } else {
            // the input data must originate from Python and be of type Row with two fields — the
            // first representing the key, and the second representing the actual data payload.
            checkState(input instanceof Row && ((Row) input).getArity() == 2);
            return pythonActionExecutor.wrapToInputEvent(((Row) input).getField(1));
        }
    }

    private OUT getOutputFromOutputEvent(Event event) {
        checkState(EventUtil.isOutputEvent(event));
        if (event instanceof OutputEvent) {
            return (OUT) ((OutputEvent) event).getOutput();
        } else if (event instanceof PythonEvent) {
            Object outputFromOutputEvent =
                    pythonActionExecutor.getOutputFromOutputEvent(((PythonEvent) event).getEvent());
            return (OUT) outputFromOutputEvent;
        } else {
            throw new IllegalStateException(
                    "Unsupported event type: " + event.getClass().getName());
        }
    }

    private List<Action> getActionsTriggeredBy(Event event) {
        if (event instanceof PythonEvent) {
            return agentPlan.getActionsTriggeredBy(((PythonEvent) event).getEventType());
        } else {
            return agentPlan.getActionsTriggeredBy(event.getClass().getName());
        }
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

    private ActionTask createActionTask(Object key, Action action, Event event) {
        if (action.getExec() instanceof JavaFunction) {
            return new JavaActionTask(key, event, action);
        } else if (action.getExec() instanceof PythonFunction) {
            return new PythonActionTask(key, event, action);
        } else {
            throw new IllegalStateException(
                    "Unsupported action type: " + action.getExec().getClass());
        }
    }

    private void createAndSetRunnerContext(ActionTask actionTask, Object key) {
        RunnerContextImpl runnerContext;
        if (actionTask.action.getExec() instanceof JavaFunction) {
            runnerContext = createOrGetRunnerContext(true);
        } else if (actionTask.action.getExec() instanceof PythonFunction) {
            runnerContext = createOrGetRunnerContext(false);
        } else {
            throw new IllegalStateException(
                    "Unsupported action type: " + actionTask.action.getExec().getClass());
        }

        RunnerContextImpl.MemoryContext memoryContext;
        if (actionTaskMemoryContexts.containsKey(actionTask)) {
            // action task for async execution action, should retrieve intermediate results from
            // map.
            memoryContext = actionTaskMemoryContexts.get(actionTask);
        } else {
            memoryContext =
                    new RunnerContextImpl.MemoryContext(
                            new CachedMemoryStore(sensoryMemState),
                            new CachedMemoryStore(shortTermMemState));
        }

        runnerContext.switchActionContext(
                actionTask.action.getName(), memoryContext, String.valueOf(key.hashCode()));

        if (runnerContext instanceof JavaRunnerContextImpl) {
            ContinuationContext continuationContext;
            if (continuationContexts.containsKey(actionTask)) {
                // action task for async execution action, should retrieve intermediate results from
                // map.
                continuationContext = continuationContexts.get(actionTask);
            } else {
                continuationContext = new ContinuationContext();
            }
            ((JavaRunnerContextImpl) runnerContext).setContinuationContext(continuationContext);
        }
        if (runnerContext instanceof PythonRunnerContextImpl) {
            // Get the awaitable ref from the transient map. After checkpoint restore, this will be
            // null, signaling that the awaitable was lost and needs re-execution.
            String awaitableRef = pythonAwaitableRefs.get(actionTask);
            ((PythonRunnerContextImpl) runnerContext).setPythonAwaitableRef(awaitableRef);
        }
        actionTask.setRunnerContext(runnerContext);
    }

    private boolean currentKeyHasMoreActionTask() throws Exception {
        return listStateNotEmpty(actionTasksKState);
    }

    private void tryResumeProcessActionTasks() throws Exception {
        Iterable<Object> keys = currentProcessingKeysOpState.get();
        if (keys != null) {
            for (Object key : keys) {
                keySegmentQueue.addKeyToLastSegment(key);
                mailboxExecutor.submit(
                        () -> tryProcessActionTaskForKey(key), "process action task");
            }
        }

        getKeyedStateBackend()
                .applyToAllKeys(
                        VoidNamespace.INSTANCE,
                        VoidNamespaceSerializer.INSTANCE,
                        new ListStateDescriptor<>(
                                PENDING_INPUT_EVENT_STATE_NAME, TypeInformation.of(Event.class)),
                        (key, state) ->
                                state.get()
                                        .forEach(
                                                event -> keySegmentQueue.addKeyToLastSegment(key)));
    }

    private void initOrIncSequenceNumber() throws Exception {
        // Initialize the sequence number state if it does not exist.
        Long sequenceNumber = sequenceNumberKState.value();
        if (sequenceNumber == null) {
            sequenceNumberKState.update(0L);
        } else {
            sequenceNumberKState.update(sequenceNumber + 1);
        }
    }

    private ActionState maybeGetActionState(
            Object key, long sequenceNum, Action action, Event event) throws Exception {
        return actionStateStore == null
                ? null
                : actionStateStore.get(key.toString(), sequenceNum, action, event);
    }

    private void maybeInitActionState(Object key, long sequenceNum, Action action, Event event)
            throws Exception {
        if (actionStateStore != null) {
            // Initialize the action state if it does not exist. It will exist when the action is an
            // async action and
            // has been persisted before the action task is finished.
            if (actionStateStore.get(key, sequenceNum, action, event) == null) {
                actionStateStore.put(key, sequenceNum, action, event, new ActionState(event));
            }
        }
    }

    private void maybePersistTaskResult(
            Object key,
            long sequenceNum,
            Action action,
            Event event,
            RunnerContextImpl context,
            ActionTask.ActionTaskResult actionTaskResult)
            throws Exception {
        if (actionStateStore == null) {
            return;
        }

        // if the task is not finished, we skip the persistence for now and wait until it is
        // finished.
        if (!actionTaskResult.isFinished()) {
            return;
        }

        ActionState actionState = actionStateStore.get(key, sequenceNum, action, event);

        for (MemoryUpdate memoryUpdate : context.getSensoryMemoryUpdates()) {
            actionState.addSensoryMemoryUpdate(memoryUpdate);
        }

        for (MemoryUpdate memoryUpdate : context.getShortTermMemoryUpdates()) {
            actionState.addShortTermMemoryUpdate(memoryUpdate);
        }

        for (Event outputEvent : actionTaskResult.getOutputEvents()) {
            actionState.addEvent(outputEvent);
        }

        // Mark the action as completed and clear call records
        // This indicates that recovery should skip the entire action
        actionState.markCompleted();

        actionStateStore.put(key, sequenceNum, action, event, actionState);

        // Clear durable execution context
        context.clearDurableExecutionContext();
    }

    /**
     * Sets up the durable execution context for fine-grained recovery.
     *
     * <p>This method initializes the runner context with a {@link
     * RunnerContextImpl.DurableExecutionContext}, which enables execute/execute_async calls to:
     *
     * <ul>
     *   <li>Skip re-execution for already completed calls during recovery
     *   <li>Persist CallRecords after each code block completion
     * </ul>
     */
    private void setupDurableExecutionContext(ActionTask actionTask, ActionState actionState) {
        if (actionStateStore == null) {
            return;
        }

        RunnerContextImpl.DurableExecutionContext durableContext;
        if (actionTaskDurableContexts.containsKey(actionTask)) {
            // Reuse existing context for async action continuation
            durableContext = actionTaskDurableContexts.get(actionTask);
        } else {
            // Create new context for first invocation
            final long sequenceNumber;
            try {
                sequenceNumber = sequenceNumberKState.value();
            } catch (Exception e) {
                throw new RuntimeException("Failed to get sequence number from state", e);
            }

            durableContext =
                    new RunnerContextImpl.DurableExecutionContext(
                            actionTask.getKey(),
                            sequenceNumber,
                            actionTask.action,
                            actionTask.event,
                            actionState,
                            this);
        }

        actionTask.getRunnerContext().setDurableExecutionContext(durableContext);
    }

    @Override
    public void persist(
            Object key, long sequenceNumber, Action action, Event event, ActionState actionState) {
        try {
            actionStateStore.put(key, sequenceNumber, action, event, actionState);
        } catch (Exception e) {
            LOG.error("Failed to persist ActionState", e);
            throw new RuntimeException("Failed to persist ActionState", e);
        }
    }

    private void maybePruneState(Object key, long sequenceNum) throws Exception {
        if (actionStateStore != null) {
            actionStateStore.pruneState(key, sequenceNum);
        }
    }

    private void processEligibleWatermarks() throws Exception {
        Watermark mark = keySegmentQueue.popOldestWatermark();
        while (mark != null) {
            super.processWatermark(mark);
            mark = keySegmentQueue.popOldestWatermark();
        }
    }

    private RunnerContextImpl createOrGetRunnerContext(Boolean isJava) {
        if (isJava) {
            if (runnerContext == null) {
                if (continuationActionExecutor == null) {
                    continuationActionExecutor =
                            new ContinuationActionExecutor(
                                    agentPlan
                                            .getConfig()
                                            .get(AgentExecutionOptions.NUM_ASYNC_THREADS));
                }
                runnerContext =
                        new JavaRunnerContextImpl(
                                this.metricGroup,
                                this::checkMailboxThread,
                                this.agentPlan,
                                this.jobIdentifier,
                                continuationActionExecutor);
            }
            return runnerContext;
        } else {
            if (pythonRunnerContext == null) {
                pythonRunnerContext =
                        new PythonRunnerContextImpl(
                                this.metricGroup,
                                this::checkMailboxThread,
                                this.agentPlan,
                                jobIdentifier);
            }
            return pythonRunnerContext;
        }
    }

    private EventLogger createEventLogger(AgentPlan agentPlan) {
        EventLoggerConfig.Builder loggerConfigBuilder = EventLoggerConfig.builder();
        String baseLogDir = agentPlan.getConfig().get(BASE_LOG_DIR);
        if (baseLogDir != null && !baseLogDir.trim().isEmpty()) {
            loggerConfigBuilder.property(FileEventLogger.BASE_LOG_DIR_PROPERTY_KEY, baseLogDir);
        }

        // Parse default log level (default is "STANDARD" via ConfigOption, null check kept
        // defensively in case a custom Configuration returns null)
        String defaultLevelStr = agentPlan.getConfig().get(EVENT_LOG_LEVEL);
        if (defaultLevelStr != null && !defaultLevelStr.trim().isEmpty()) {
            try {
                loggerConfigBuilder.defaultLogLevel(
                        EventLogLevel.valueOf(defaultLevelStr.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid eventLogLevel: '"
                                + defaultLevelStr
                                + "'. Valid values: OFF, STANDARD, VERBOSE",
                        e);
            }
        }

        // Parse per-event-type log level overrides
        String perTypeLevels = agentPlan.getConfig().get(EVENT_LOG_LEVELS);
        if (perTypeLevels != null && !perTypeLevels.trim().isEmpty()) {
            try {
                loggerConfigBuilder.eventLogLevels(EventLogLevel.parseLogLevels(perTypeLevels));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid eventLogLevels: '"
                                + perTypeLevels
                                + "'. Expected format: EventTypeName=LEVEL,EventTypeName=LEVEL"
                                + " where LEVEL is OFF, STANDARD, or VERBOSE",
                        e);
            }
        }

        // Parse max field length for STANDARD level truncation
        Integer maxFieldLength = agentPlan.getConfig().get(EVENT_LOG_MAX_FIELD_LENGTH);
        if (maxFieldLength != null) {
            loggerConfigBuilder.maxFieldLength(maxFieldLength);
        }

        return EventLoggerFactory.createLogger(loggerConfigBuilder.build());
    }

    private void maybeInitActionStateStore() {
        if (actionStateStore == null
                && KAFKA.getType()
                        .equalsIgnoreCase(agentPlan.getConfig().get(ACTION_STATE_STORE_BACKEND))) {
            LOG.info("Using Kafka as backend of action state store.");
            actionStateStore = new KafkaActionStateStore(agentPlan.getConfig());
        }
    }

    /** Failed to execute Action task. */
    public static class ActionTaskExecutionException extends Exception {
        public ActionTaskExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
