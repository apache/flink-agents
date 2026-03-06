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
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.metrics.BuiltInMetrics;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.python.operator.PythonActionTask;
import org.apache.flink.agents.runtime.utils.EventUtil;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxExecutorImpl;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxProcessor;
import org.apache.flink.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.JOB_IDENTIFIER;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * An operator that executes the actions defined in the agent. Upon receiving data from the
 * upstream, it first wraps the data into an {@link org.apache.flink.agents.api.InputEvent}. It then
 * invokes the corresponding action that is interested in the {@link
 * org.apache.flink.agents.api.InputEvent}, and collects the output event produced by the action.
 *
 * <p>For events of type {@link org.apache.flink.agents.api.OutputEvent}, the data contained in the
 * event is sent downstream. For all other event types, the process is repeated: the event triggers
 * the corresponding action, and the resulting output event is collected for further processing.
 *
 * <p>This operator delegates to the following package-private managers:
 *
 * <ul>
 *   <li>{@link PythonBridgeManager} — Python interpreter and resource adapters
 *   <li>{@link DurableExecutionManager} — action state persistence and recovery
 *   <li>{@link ActionTaskContextManager} — runner context creation and transfer
 *   <li>{@link EventRouter} — event wrapping, routing, notification, watermarks
 *   <li>{@link OperatorStateManager} — Flink state initialization and access
 * </ul>
 */
public class ActionExecutionOperator<IN, OUT> extends AbstractStreamOperator<OUT>
        implements OneInputStreamOperator<IN, OUT>, BoundedOneInput {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ActionExecutionOperator.class);

    private final AgentPlan agentPlan;

    private final Boolean inputIsJava;

    private transient StreamRecord<OUT> reusedStreamRecord;

    private final transient MailboxExecutor mailboxExecutor;

    private transient MailboxProcessor mailboxProcessor;

    private transient FlinkAgentsMetricGroupImpl metricGroup;

    private transient BuiltInMetrics builtInMetrics;

    // Each job can only have one identifier and this identifier must be consistent across restarts.
    private transient String jobIdentifier;

    // Managers
    private transient PythonBridgeManager pythonBridgeManager;
    private transient DurableExecutionManager durableExecutionManager;
    private transient ActionTaskContextManager contextManager;
    private transient EventRouter eventRouter;
    private transient OperatorStateManager stateManager;

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
        this.durableExecutionManager = new DurableExecutionManager(actionStateStore);
        this.eventRouter = new EventRouter(agentPlan, inputIsJava);
        this.stateManager = new OperatorStateManager();
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

        durableExecutionManager.maybeInitActionStateStore(agentPlan);

        stateManager.initializeStates(
                getRuntimeContext(),
                getOperatorStateBackend(),
                durableExecutionManager.getActionStateStore() != null);

        metricGroup = new FlinkAgentsMetricGroupImpl(getMetricGroup());
        builtInMetrics = new BuiltInMetrics(metricGroup, agentPlan);
        eventRouter.setBuiltInMetrics(builtInMetrics);

        // init PythonActionExecutor and PythonResourceAdapter
        pythonBridgeManager = new PythonBridgeManager();
        pythonBridgeManager.initPythonEnvironment(
                agentPlan,
                getExecutionConfig(),
                getRuntimeContext().getDistributedCache(),
                getContainingTask().getEnvironment().getTaskManagerInfo().getTmpDirectories(),
                getRuntimeContext().getJobInfo().getJobId(),
                metricGroup,
                this::checkMailboxThread,
                jobIdentifier);
        eventRouter.setPythonActionExecutor(pythonBridgeManager.getPythonActionExecutor());

        // init context manager for runner context lifecycle
        contextManager =
                new ActionTaskContextManager(
                        metricGroup,
                        this::checkMailboxThread,
                        agentPlan,
                        jobIdentifier,
                        pythonBridgeManager.getPythonRunnerContext());

        mailboxProcessor = getMailboxProcessor();

        // Initialize the event logger if it is set.
        eventRouter.initEventLogger(getRuntimeContext());

        // Since an operator restart may change the key range it manages due to changes in
        // parallelism,
        // and {@link tryProcessActionTaskForKey} mails might be lost,
        // it is necessary to reprocess all keys to ensure correctness.
        tryResumeProcessActionTasks();
    }

    @Override
    public void processWatermark(Watermark mark) throws Exception {
        eventRouter.getKeySegmentQueue().addWatermark(mark);
        eventRouter.processEligibleWatermarks(super::processWatermark);
    }

    @Override
    public void processElement(StreamRecord<IN> record) throws Exception {
        IN input = record.getValue();
        LOG.debug("Receive an element {}", input);

        Event inputEvent = eventRouter.wrapToInputEvent(input);
        if (record.hasTimestamp()) {
            inputEvent.setSourceTimestamp(record.getTimestamp());
        }

        eventRouter.getKeySegmentQueue().addKeyToLastSegment(getCurrentKey());

        if (stateManager.hasMoreActionTasks()) {
            stateManager.addPendingInputEvent(inputEvent);
        } else {
            processEvent(getCurrentKey(), inputEvent);
        }
    }

    /**
     * Processes an incoming event for the given key and may submit a new mail
     * `tryProcessActionTaskForKey` to continue processing.
     */
    private void processEvent(Object key, Event event) throws Exception {
        eventRouter.notifyEventProcessed(event);

        boolean isInputEvent = EventUtil.isInputEvent(event);
        if (EventUtil.isOutputEvent(event)) {
            OUT outputData = eventRouter.getOutputFromOutputEvent(event);
            if (event.hasSourceTimestamp()) {
                output.collect(reusedStreamRecord.replace(outputData, event.getSourceTimestamp()));
            } else {
                reusedStreamRecord.eraseTimestamp();
                output.collect(reusedStreamRecord.replace(outputData));
            }
        } else {
            if (isInputEvent) {
                stateManager.addProcessingKey(key);
                stateManager.initOrIncSequenceNumber();
            }
            List<Action> triggerActions = eventRouter.getActionsTriggeredBy(event);
            if (triggerActions != null && !triggerActions.isEmpty()) {
                for (Action triggerAction : triggerActions) {
                    stateManager.addActionTask(createActionTask(key, triggerAction, event));
                }
            }
        }

        if (isInputEvent) {
            mailboxExecutor.submit(() -> tryProcessActionTaskForKey(key), "process action task");
        }
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

        ActionTask actionTask = stateManager.pollNextActionTask();
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
            return;
        }

        // 2. Invoke the action task.
        contextManager.createAndSetRunnerContext(
                actionTask,
                key,
                stateManager.getSensoryMemState(),
                stateManager.getShortTermMemState());

        long sequenceNumber = stateManager.getSequenceNumber();
        boolean isFinished;
        List<Event> outputEvents;
        Optional<ActionTask> generatedActionTaskOpt = Optional.empty();
        ActionState actionState =
                durableExecutionManager.maybeGetActionState(
                        key, sequenceNumber, actionTask.action, actionTask.event);

        // Check if action is already completed
        if (actionState != null && actionState.isCompleted()) {
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
            if (actionState == null) {
                durableExecutionManager.maybeInitActionState(
                        key, sequenceNumber, actionTask.action, actionTask.event);
                actionState =
                        durableExecutionManager.maybeGetActionState(
                                key, sequenceNumber, actionTask.action, actionTask.event);
            }

            durableExecutionManager.setupDurableExecutionContext(
                    actionTask,
                    actionState,
                    sequenceNumber,
                    contextManager.getActionTaskDurableContexts());

            ActionTask.ActionTaskResult actionTaskResult =
                    actionTask.invoke(
                            getRuntimeContext().getUserCodeClassLoader(),
                            pythonBridgeManager.getPythonActionExecutor());

            contextManager.removeContexts(actionTask);
            durableExecutionManager.maybePersistTaskResult(
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
            currentInputEventFinished = !stateManager.hasMoreActionTasks();

            actionTask.getRunnerContext().persistMemory();
        } else {
            checkState(
                    generatedActionTaskOpt.isPresent(),
                    "ActionTask not finished, but the generated action task is null.");

            ActionTask generatedActionTask = generatedActionTaskOpt.get();
            contextManager.transferContexts(actionTask, generatedActionTask);
            stateManager.addActionTask(generatedActionTask);
        }

        // 3. Process the next InputEvent or next action task
        if (currentInputEventFinished) {
            actionTask.getRunnerContext().clearSensoryMemory();

            int removedCount = stateManager.removeProcessingKey(key);
            durableExecutionManager.maybePruneState(key, sequenceNumber);
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
            Event pendingInputEvent = stateManager.pollNextPendingInputEvent();
            if (pendingInputEvent != null) {
                processEvent(key, pendingInputEvent);
            }
        } else if (stateManager.hasMoreActionTasks()) {
            mailboxExecutor.submit(() -> tryProcessActionTaskForKey(key), "process action task");
        }
    }

    @Override
    public void endInput() throws Exception {
        waitInFlightEventsFinished();
    }

    @VisibleForTesting
    public void waitInFlightEventsFinished() throws Exception {
        while (stateManager.hasProcessingKeys()) {
            mailboxExecutor.yield();
        }
    }

    @Override
    public void close() throws Exception {
        if (contextManager != null) {
            contextManager.close();
        }
        if (pythonBridgeManager != null) {
            pythonBridgeManager.close();
        }
        if (eventRouter != null) {
            eventRouter.close();
        }
        if (durableExecutionManager != null) {
            durableExecutionManager.close();
        }

        super.close();
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);

        durableExecutionManager.maybeInitActionStateStore(agentPlan);

        List<Object> markers =
                stateManager.collectRecoveryMarkers(
                        getOperatorStateBackend(),
                        durableExecutionManager.getActionStateStore() != null);
        durableExecutionManager.rebuildStateFromMarkers(markers);

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
        if (durableExecutionManager.getActionStateStore() != null) {
            Object recoveryMarker = durableExecutionManager.getRecoveryMarker();
            if (recoveryMarker != null) {
                stateManager.updateRecoveryMarker(recoveryMarker);
            }

            Map<Object, Long> keyToSeqNum =
                    stateManager.snapshotSequenceNumbers(getKeyedStateBackend());
            durableExecutionManager.recordCheckpointSeqNums(context.getCheckpointId(), keyToSeqNum);
        }

        super.snapshotState(context);
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws Exception {
        durableExecutionManager.notifyCheckpointComplete(checkpointId);
        super.notifyCheckpointComplete(checkpointId);
    }

    // --- Test support ---

    @VisibleForTesting
    DurableExecutionManager getDurableExecutionManager() {
        return durableExecutionManager;
    }

    @VisibleForTesting
    EventRouter getEventRouter() {
        return eventRouter;
    }

    // --- Private helpers ---

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

    private void tryResumeProcessActionTasks() throws Exception {
        Iterable<Object> keys = stateManager.getProcessingKeys();
        if (keys != null) {
            for (Object key : keys) {
                eventRouter.getKeySegmentQueue().addKeyToLastSegment(key);
                mailboxExecutor.submit(
                        () -> tryProcessActionTaskForKey(key), "process action task");
            }
        }

        stateManager.forEachPendingInputEventKey(
                getKeyedStateBackend(),
                key -> eventRouter.getKeySegmentQueue().addKeyToLastSegment(key));
    }

    /** Failed to execute Action task. */
    public static class ActionTaskExecutionException extends Exception {
        public ActionTaskExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
