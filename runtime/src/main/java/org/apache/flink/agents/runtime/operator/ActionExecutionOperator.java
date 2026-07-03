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
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.memory.Mem0LongTermMemory;
import org.apache.flink.agents.runtime.metrics.BuiltInMetrics;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.python.operator.PythonActionTask;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.utils.EventUtil;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.runtime.state.KeyGroupRange;
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

import javax.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.JOB_IDENTIFIER;
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

    private transient ResourceCache resourceCache;

    private transient PythonBridgeManager pythonBridge;

    private transient FlinkAgentsMetricGroupImpl metricGroup;

    private transient BuiltInMetrics builtInMetrics;

    private final transient MailboxExecutor mailboxExecutor;

    private transient ActionTaskContextManager contextManager;

    // Long-term memory backed by Mem0; non-null only when LongTermMemoryOptions.Mem0 is configured.
    private transient Mem0LongTermMemory ltm;

    // We need to check whether the current thread is the mailbox thread using the mailbox
    // processor.
    // TODO: This is a temporary workaround. In the future, we should add an interface in
    // MailboxExecutor to check whether a thread is a mailbox thread, rather than using reflection
    // to obtain the MailboxProcessor instance and make the determination.
    private transient MailboxProcessor mailboxProcessor;

    private final transient EventRouter<IN, OUT> eventRouter;

    private final transient DurableExecutionManager durableExecManager;

    private transient OperatorStateManager stateManager;

    // Each job can only have one identifier and this identifier must be consistent across restarts.
    // We cannot use job id as the identifier here because user may change job id by
    // creating a savepoint, stop the job and then resume from savepoint.
    // We use this identifier to control the visibility for long-term memory.
    // Inspired by Apache Paimon.
    private transient String jobIdentifier;

    private final boolean inputIsJava;

    public ActionExecutionOperator(
            AgentPlan agentPlan,
            Boolean inputIsJava,
            ProcessingTimeService processingTimeService,
            MailboxExecutor mailboxExecutor,
            ActionStateStore actionStateStore) {
        this.agentPlan = agentPlan;
        this.processingTimeService = processingTimeService;
        this.mailboxExecutor = mailboxExecutor;
        this.inputIsJava = inputIsJava;
        this.eventRouter = new EventRouter<>(agentPlan, inputIsJava);
        this.durableExecManager = new DurableExecutionManager(actionStateStore);
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
        builtInMetrics = new BuiltInMetrics(metricGroup, agentPlan);

        eventRouter.open(builtInMetrics);

        durableExecManager.maybeInitActionStateStore(agentPlan.getConfig());
        durableExecManager.initRecoveryMarkerState(getOperatorStateBackend());
        durableExecManager.initializeKeyedStates(getRuntimeContext());

        // init PythonActionExecutor and PythonResourceAdapter
        pythonBridge = new PythonBridgeManager();
        pythonBridge.open(
                agentPlan,
                resourceCache,
                getExecutionConfig(),
                getRuntimeContext().getDistributedCache(),
                getContainingTask().getEnvironment().getTaskManagerInfo().getTmpDirectories(),
                getRuntimeContext().getJobInfo().getJobId(),
                metricGroup,
                this::checkMailboxThread,
                jobIdentifier,
                getRuntimeContext().getUserCodeClassLoader());

        // Capture the wired Mem0 long-term memory, if any, so it can be plumbed into the Java
        // runner context created by ActionTaskContextManager.
        ltm = pythonBridge.getLongTermMemory();

        // init context manager for runner context creation and memory contexts
        contextManager =
                new ActionTaskContextManager(
                        agentPlan.getConfig().get(AgentExecutionOptions.NUM_ASYNC_THREADS));

        mailboxProcessor = getMailboxProcessor();

        // Initialize the event logger if it is set.
        eventRouter.initEventLogger(getRuntimeContext());

        // Initialize user event listeners from configuration
        eventRouter.initEventListeners(getRuntimeContext());

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

        // wrap to InputEvent first
        Event inputEvent =
                eventRouter.wrapToInputEvent(input, pythonBridge.getPythonActionExecutor());
        if (record.hasTimestamp()) {
            inputEvent.setSourceTimestamp(record.getTimestamp());
        }

        eventRouter.getKeySegmentQueue().addKeyToLastSegment(getCurrentKey());

        if (stateManager.hasMoreActionTasks()) {
            // If there are already actions being processed for the current key, the newly incoming
            // event should be queued and processed later. Therefore, we add it to
            // pendingInputEventsState.
            stateManager.addPendingInputEvent(inputEvent);
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
        eventRouter.notifyEventProcessed(event);

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
            if (isInputEvent) {
                // If the event is an InputEvent, we mark that the key is currently being processed.
                stateManager.addProcessingKey(key);
                stateManager.initOrIncSequenceNumber();
            }
            // We then obtain the triggered action and add ActionTasks to the waiting processing
            // queue.
            List<Action> triggerActions = eventRouter.getActionsTriggeredBy(event, agentPlan);
            if (triggerActions != null && !triggerActions.isEmpty()) {
                for (Action triggerAction : triggerActions) {
                    stateManager.addActionTask(createActionTask(key, triggerAction, event));
                }
            }
        }

        if (isInputEvent) {
            // If the event is an InputEvent, we submit a new mail to try processing the actions.
            mailboxExecutor.submit(() -> tryProcessActionTaskForKey(key), "process action task");
        }
    }

    private void tryProcessActionTaskForKey(Object key) {
        try {
            processActionTaskForKey(key);
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
                resolveEventKeyText(key),
                agentPlan,
                resourceCache,
                metricGroup,
                jobIdentifier,
                this::checkMailboxThread,
                stateManager.getSensoryMemState(),
                stateManager.getShortTermMemState(),
                pythonBridge.getPythonRunnerContext(),
                ltm);

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
                durableExecManager.maybeInitActionState(
                        key, sequenceNumber, actionTask.action, actionTask.event);
                actionState =
                        durableExecManager.maybeGetActionState(
                                key, sequenceNumber, actionTask.action, actionTask.event);
            }

            // Set up durable execution context for fine-grained recovery
            durableExecManager.setupDurableExecutionContext(
                    actionTask, actionState, sequenceNumber);

            ActionTask.ActionTaskResult actionTaskResult =
                    actionTask.invoke(
                            getRuntimeContext().getUserCodeClassLoader(),
                            this.pythonBridge.getPythonActionExecutor());

            // We remove the contexts from the map after the task is processed. They will be added
            // back later if the action task has a generated action task, meaning it is not
            // finished.
            contextManager.removeMemoryContext(actionTask);
            durableExecManager.removeDurableContext(actionTask);
            contextManager.removeContinuationContext(actionTask);
            contextManager.removePythonAwaitableRef(actionTask);
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
        }

        for (Event actionOutputEvent : outputEvents) {
            processEvent(key, actionOutputEvent);
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

            stateManager.addActionTask(generatedActionTask);
        }

        // 3. Process the next InputEvent or next action task
        if (currentInputEventFinished) {
            // Clean up sensory memory when a single run finished.
            actionTask.getRunnerContext().clearSensoryMemory();
            durableExecManager.updateLastCompletedSequenceNumber(sequenceNumber);

            // Once all sub-events and actions related to the current InputEvent are completed,
            // we can proceed to process the next InputEvent.
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
            Event pendingInputEvent = stateManager.pollNextPendingInputEvent();
            if (pendingInputEvent != null) {
                processEvent(key, pendingInputEvent);
            }
        } else if (stateManager.hasMoreActionTasks()) {
            // If the current key has additional action tasks remaining, we should submit a new mail
            // to continue processing them.
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
        // Must close before pythonInterpreter since cached resources may hold Python references.
        if (resourceCache != null) {
            resourceCache.close();
        }
        if (contextManager != null) {
            contextManager.close();
        }
        if (pythonBridge != null) {
            pythonBridge.close();
        }
        if (eventRouter != null) {
            eventRouter.close();
        }
        if (durableExecManager != null) {
            durableExecManager.close();
        }

        super.close();
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);

        durableExecManager.maybeInitActionStateStore(agentPlan.getConfig());
        durableExecManager.handleRecovery(getOperatorStateBackend());

        stateManager = new OperatorStateManager();

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

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        durableExecManager.snapshotRecoveryMarker();
        durableExecManager.snapshotLastCompletedSequenceNumbers(
                getKeyedStateBackend(), context.getCheckpointId());

        super.snapshotState(context);
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws Exception {
        durableExecManager.notifyCheckpointComplete(checkpointId);
        super.notifyCheckpointComplete(checkpointId);
    }

    @Override
    public void notifyCheckpointAborted(long checkpointId) throws Exception {
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

    /** Returns the logical String key for Java and PyFlink keyed streams, if supported. */
    @Nullable
    private String resolveEventKeyText(Object key) {
        PythonActionExecutor pythonActionExecutor =
                pythonBridge == null ? null : pythonBridge.getPythonActionExecutor();
        return resolveEventKeyText(key, inputIsJava, pythonActionExecutor);
    }

    @VisibleForTesting
    @Nullable
    static String resolveEventKeyText(
            Object key, boolean inputIsJava, @Nullable PythonActionExecutor pythonActionExecutor) {
        if (key instanceof String) {
            return (String) key;
        }
        if (inputIsJava || pythonActionExecutor == null) {
            return null;
        }
        return pythonActionExecutor.resolveStringKey(key);
    }

    private void tryResumeProcessActionTasks() throws Exception {
        Iterable<Object> keys = stateManager.getProcessingKeys();
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
                mailboxExecutor.submit(
                        () -> tryProcessActionTaskForKey(key), "process action task");
            }
            stateManager.replaceProcessingKeys(new ArrayList<>(ownedKeys));
        }

        stateManager.forEachPendingInputEventKey(
                getKeyedStateBackend(),
                (key, state) ->
                        state.get()
                                .forEach(
                                        event ->
                                                eventRouter
                                                        .getKeySegmentQueue()
                                                        .addKeyToLastSegment(key)));
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
