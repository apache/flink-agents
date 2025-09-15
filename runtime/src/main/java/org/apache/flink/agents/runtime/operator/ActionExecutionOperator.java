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
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.api.listener.EventListener;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.api.logger.EventLoggerConfig;
import org.apache.flink.agents.api.logger.EventLoggerFactory;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.actionstate.KafkaActionStateStore;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.metrics.BuiltInMetrics;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.apache.flink.agents.runtime.python.operator.PythonActionTask;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.utils.EventUtil;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.python.env.PythonDependencyInfo;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.operators.*;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxExecutorImpl;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxProcessor;
import org.apache.flink.types.Row;
import org.apache.flink.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.ACTION_STATE_STORE_BACKEND;
import static org.apache.flink.agents.runtime.actionstate.ActionStateStore.BackendType.INMEMORY;
import static org.apache.flink.agents.runtime.actionstate.ActionStateStore.BackendType.KAFKA;
import static org.apache.flink.agents.runtime.utils.StateUtil.*;
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

    private static final Logger LOG = LoggerFactory.getLogger(ActionExecutionOperator.class);

    private final AgentPlan agentPlan;

    private final Boolean inputIsJava;

    private transient StreamRecord<OUT> reusedStreamRecord;

    private transient MapState<String, MemoryObjectImpl.MemoryItem> shortTermMemState;

    // PythonActionExecutor for Python actions
    private transient PythonActionExecutor pythonActionExecutor;

    private transient FlinkAgentsMetricGroupImpl metricGroup;

    private transient BuiltInMetrics builtInMetrics;

    private final transient MailboxExecutor mailboxExecutor;

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

    public ActionExecutionOperator(
            AgentPlan agentPlan,
            Boolean inputIsJava,
            ProcessingTimeService processingTimeService,
            MailboxExecutor mailboxExecutor) {
        this.agentPlan = agentPlan;
        this.inputIsJava = inputIsJava;
        this.processingTimeService = processingTimeService;
        this.chainingStrategy = ChainingStrategy.ALWAYS;
        this.mailboxExecutor = mailboxExecutor;
        this.eventLogger = EventLoggerFactory.createLogger(EventLoggerConfig.builder().build());
        this.eventListeners = new ArrayList<>();
    }

    @Override
    public void open() throws Exception {
        super.open();

        reusedStreamRecord = new StreamRecord<>(null);

        // init shortTermMemState
        MapStateDescriptor<String, MemoryObjectImpl.MemoryItem> shortTermMemStateDescriptor =
                new MapStateDescriptor<>(
                        "shortTermMemory",
                        TypeInformation.of(String.class),
                        TypeInformation.of(MemoryObjectImpl.MemoryItem.class));
        shortTermMemState = getRuntimeContext().getMapState(shortTermMemStateDescriptor);

        metricGroup = new FlinkAgentsMetricGroupImpl(getMetricGroup());
        builtInMetrics = new BuiltInMetrics(metricGroup, agentPlan);

        if (KAFKA.getType()
                .equalsIgnoreCase(agentPlan.getConfig().get(ACTION_STATE_STORE_BACKEND))) {
            LOG.info("Using KafkaActionStateStore for action state store.");
            actionStateStore = new KafkaActionStateStore();
        } else if (INMEMORY.getType()
                .equalsIgnoreCase(agentPlan.getConfig().get(ACTION_STATE_STORE_BACKEND))) {
            actionStateStore = new InMemoryActionStateStore();
        }

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
                                        "pendingInputEvents", TypeInformation.of(Event.class)));
        // We use UnionList here to ensure that the task can access all keys after parallelism
        // modifications.
        // Subsequent steps {@link #tryResumeProcessActionTasks} will then filter out keys that do
        // not belong to the key range of current task.
        currentProcessingKeysOpState =
                getOperatorStateBackend()
                        .getUnionListState(
                                new ListStateDescriptor<>(
                                        "currentProcessingKeys", TypeInformation.of(Object.class)));

        // init PythonActionExecutor
        initPythonActionExecutor();

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
    public void processElement(StreamRecord<IN> record) throws Exception {
        IN input = record.getValue();
        LOG.debug("Receive an element {}", input);

        // wrap to InputEvent first
        Event inputEvent = wrapToInputEvent(input);

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
            output.collect(reusedStreamRecord.replace(outputData));
        } else {
            if (isInputEvent) {
                // If the event is an InputEvent, we mark that the key is currently being processed.
                currentProcessingKeysOpState.add(key);
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
            return;
        }

        // 2. Invoke the action task.
        createAndSetRunnerContext(actionTask);

        boolean isFinished;
        List<Event> outputEvents;
        Optional<ActionTask> generatedActionTaskOpt;
        ActionState actionState = maybeGetActionState(key, actionTask.action, actionTask.event);
        if (actionState != null && actionState.getGeneratedActionTask().isEmpty()) {
            isFinished = true;
            outputEvents = actionState.getOutputEvents();
            generatedActionTaskOpt = actionState.getGeneratedActionTask();
            for (MemoryUpdate memoryUpdate : actionState.getMemoryUpdates()) {
                actionTask
                        .getRunnerContext()
                        .getShortTermMemory()
                        .set(memoryUpdate.getPath(), memoryUpdate.getValue());
            }
        } else {
            maybeInitActionState(key, actionTask.action, actionTask.event);
            ActionTask.ActionTaskResult actionTaskResult = actionTask.invoke();
            maybePersistTaskResult(
                    key,
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
        } else {
            // If the action task is not finished, we should get a new action task to continue the
            // execution.
            checkNotNull(
                    generatedActionTaskOpt.get(),
                    "ActionTask not finished, but the generated action task is null.");
            actionTasksKState.add(generatedActionTaskOpt.get());
        }

        // 3. Process the next InputEvent or next action task
        if (currentInputEventFinished) {
            // Once all sub-events and actions related to the current InputEvent are completed,
            // we can proceed to process the next InputEvent.
            int removedCount = removeFromListState(currentProcessingKeysOpState, key);
            checkState(
                    removedCount == 1,
                    "Current processing key count for key "
                            + key
                            + " should be 1, but got "
                            + removedCount);
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

    private void initPythonActionExecutor() throws Exception {
        boolean containPythonAction =
                agentPlan.getActions().values().stream()
                        .anyMatch(action -> action.getExec() instanceof PythonFunction);
        if (containPythonAction) {
            LOG.debug("Begin initialize PythonActionExecutor.");
            PythonDependencyInfo dependencyInfo =
                    PythonDependencyInfo.create(
                            getExecutionConfig().toConfiguration(),
                            getRuntimeContext().getDistributedCache());
            PythonEnvironmentManager pythonEnvironmentManager =
                    new PythonEnvironmentManager(
                            dependencyInfo,
                            getContainingTask()
                                    .getEnvironment()
                                    .getTaskManagerInfo()
                                    .getTmpDirectories(),
                            new HashMap<>(System.getenv()),
                            getRuntimeContext().getJobInfo().getJobId());
            pythonActionExecutor =
                    new PythonActionExecutor(
                            pythonEnvironmentManager,
                            new ObjectMapper().writeValueAsString(agentPlan));
            pythonActionExecutor.open();
        }
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
        if (pythonActionExecutor != null) {
            pythonActionExecutor.close();
        }
        if (eventLogger != null) {
            eventLogger.close();
        }

        super.close();
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);

        if (actionStateStore != null) {
            Object recoveryMarker = null;
            ListState<Object> recoveryMarkerOpState =
                    getOperatorStateBackend()
                            .getListState(
                                    new ListStateDescriptor<>(
                                            "recoveryMarker", TypeInformation.of(Object.class)));
            Iterable<Object> recoveryMarkers = recoveryMarkerOpState.get();
            if (recoveryMarkers != null) {
                for (Object marker : recoveryMarkers) {
                    // there should be only one recovery marker
                    recoveryMarker = marker;
                    break;
                }
            }
            actionStateStore.rebuildState(recoveryMarker);
        }
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        super.snapshotState(context);
        if (actionStateStore != null) {
            Object recoveryMarker = actionStateStore.getRecoveryMarker();
            if (recoveryMarker != null) {
                ListState<Object> recoveryMarkerOpState =
                        getOperatorStateBackend()
                                .getListState(
                                        new ListStateDescriptor<>(
                                                "recoveryMarker",
                                                TypeInformation.of(Object.class)));
                recoveryMarkerOpState.update(List.of(recoveryMarker));
            }
        }
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws Exception {
        super.notifyCheckpointComplete(checkpointId);
        actionStateStore.cleanUpState();
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
            return new PythonActionTask(key, event, action, pythonActionExecutor);
        } else {
            throw new IllegalStateException(
                    "Unsupported action type: " + action.getExec().getClass());
        }
    }

    private void createAndSetRunnerContext(ActionTask actionTask) {
        if (actionTask.getRunnerContext() != null) {
            return;
        }

        RunnerContextImpl runnerContext;
        if (actionTask.action.getExec() instanceof JavaFunction) {
            runnerContext =
                    new RunnerContextImpl(
                            shortTermMemState, metricGroup, this::checkMailboxThread, agentPlan);
        } else if (actionTask.action.getExec() instanceof PythonFunction) {
            runnerContext =
                    new PythonRunnerContextImpl(
                            shortTermMemState, metricGroup, this::checkMailboxThread, agentPlan);
        } else {
            throw new IllegalStateException(
                    "Unsupported action type: " + actionTask.action.getExec().getClass());
        }

        runnerContext.setActionName(actionTask.action.getName());
        actionTask.setRunnerContext(runnerContext);
    }

    private boolean currentKeyHasMoreActionTask() throws Exception {
        return listStateNotEmpty(actionTasksKState);
    }

    private void tryResumeProcessActionTasks() throws Exception {
        Iterable<Object> keys = currentProcessingKeysOpState.get();
        if (keys != null) {
            for (Object key : keys) {
                mailboxExecutor.submit(
                        () -> tryProcessActionTaskForKey(key), "process action task");
            }
        }
    }

    private ActionState maybeGetActionState(Object key, Action action, Event event) {
        return actionStateStore == null ? null : actionStateStore.get(key, action, event);
    }

    private void maybeInitActionState(Object key, Action action, Event event) {
        if (actionStateStore != null) {
            // Initialize the action state if it does not exist. It will exist when the action is an
            // async action and
            // has been persisted before the action task is finished.
            if (actionStateStore.get(key, action, event) == null) {
                actionStateStore.put(key, action, event, new ActionState(event));
            }
        }
    }

    private void maybePersistTaskResult(
            Object key,
            Action action,
            Event event,
            RunnerContextImpl context,
            ActionTask.ActionTaskResult actionTaskResult) {
        if (actionStateStore == null) {
            return;
        }

        ActionState actionState = actionStateStore.get(key, action, event);
        actionState.setGeneratedActionTask(actionTaskResult.getGeneratedActionTask().orElse(null));

        for (MemoryUpdate memoryUpdate : context.getAllMemoryUpdates()) {
            actionState.addMemoryUpdate(memoryUpdate);
        }

        for (Event outputEvent : actionTaskResult.getOutputEvents()) {
            actionState.addEvent(outputEvent);
        }
        actionStateStore.put(key, action, event, actionState);
    }

    /** Failed to execute Action task. */
    public static class ActionTaskExecutionException extends Exception {
        public ActionTaskExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
