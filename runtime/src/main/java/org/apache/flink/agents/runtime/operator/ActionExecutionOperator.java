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
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.apache.flink.agents.runtime.python.operator.PythonActionTask;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.utils.EventUtil;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.api.common.state.*;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.python.env.PythonDependencyInfo;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
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
import java.util.Iterator;
import java.util.List;

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

    private transient MailboxExecutor mailboxExecutor;

    // We need to check whether the current thread is the mailbox main thread using the mailbox
    // processor.
    private transient MailboxProcessor mailboxProcessor;

    // An action will be split into one or more ActionTask objects. We use a state to store the
    // pending ActionTasks that are waiting to be executed.
    private transient ListState<ActionTask> actionTasksState;

    // To avoid processing different InputEvents with the same key, we use a state to store pending
    // InputEvents that are waiting to be processed.
    private transient ListState<Event> pendingInputEventsState;

    // An operator state is used to track the currently processing keys. This is useful when
    // receiving an EndOfInput signal, as we need to wait until all related events are fully
    // processed.
    private transient ListState<Object> currentProcessingKeysOperatorState;

    private transient Throwable errorCause;

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

        // init agent processing related state
        actionTasksState =
                getRuntimeContext()
                        .getListState(
                                new ListStateDescriptor<>(
                                        "actionTasks", TypeInformation.of(ActionTask.class)));
        pendingInputEventsState =
                getRuntimeContext()
                        .getListState(
                                new ListStateDescriptor<>(
                                        "pendingInputEvents", TypeInformation.of(Event.class)));
        currentProcessingKeysOperatorState =
                getOperatorStateBackend()
                        .getListState(
                                new ListStateDescriptor<>(
                                        "currentProcessingKeys", TypeInformation.of(Object.class)));

        // init PythonActionExecutor
        initPythonActionExecutor();

        mailboxProcessor = getMailboxProcessor();
    }

    @Override
    public void processElement(StreamRecord<IN> record) throws Exception {
        checkError();

        IN input = record.getValue();
        LOG.debug("Receive an element {}", input);

        // wrap to InputEvent first
        Event inputEvent = wrapToInputEvent(input);

        if (currentKeyHasMoreActionTask()) {
            // If there are already actions being processed for the current key, the newly incoming
            // event should be queued and processed later. Therefore, we add it to
            // pendingInputEventsState.
            pendingInputEventsState.add(inputEvent);
        } else {
            // Otherwise, the new event is processed immediately.
            processEvent(getCurrentKey(), inputEvent);
        }
    }

    private void processEvent(Object key, Event event) throws Exception {
        if (EventUtil.isOutputEvent(event)) {
            // If the event is an OutputEvent, we send it downstream.
            OUT outputData = getOutputFromOutputEvent(event);
            output.collect(reusedStreamRecord.replace(outputData));
        } else {
            if (EventUtil.isInputEvent(event)) {
                // If the event is an InputEvent, we mark that the key is currently being processed.
                currentProcessingKeysOperatorState.add(key);
            }
            // We then obtain the triggered action and add ActionTasks to the waiting processing
            // queue.
            List<Action> triggerActions = getActionsTriggeredBy(event);
            for (Action triggerAction : triggerActions) {
                addActionTask(createActionTask(key, triggerAction, event));
            }
        }
    }

    private void processActionTask(Object key) throws Exception {
        setCurrentKey(key);
        ActionTask actionTask = pollFromListState(actionTasksState);
        if (actionTask == null) {
            throw new RuntimeException(
                    "A null value was encountered while trying to process the ActionTask. This likely indicates a bug in the internal logic.");
        }
        boolean finished = actionTask.invoke();
        List<Event> outputEvents = actionTask.getOutputEvents();
        for (Event actionOutputEvent : outputEvents) {
            processEvent(actionTask.getKey(), actionOutputEvent);
        }

        if (finished) {
            if (!currentKeyHasMoreActionTask()) {
                // Once all sub-events and actions related to the current InputEvent are completed,
                // we can proceed to process the next InputEvent.
                int removedCount =
                        removeFromListState(
                                currentProcessingKeysOperatorState, actionTask.getKey());
                checkState(
                        removedCount == 1,
                        "Current processing key count should be 1, but got " + removedCount);
                Event pendingInputEvent = pollFromListState(pendingInputEventsState);
                if (pendingInputEvent != null) {
                    processEvent(actionTask.getKey(), pendingInputEvent);
                }
            }
        } else {
            // If the action task not finished, we should get a new action task to execute continue.
            ActionTask generatedActionTask = actionTask.getGeneratedActionTask();
            checkNotNull(
                    generatedActionTask,
                    "ActionTask not finished, but the generated action task is null.");
            addActionTask(generatedActionTask);
        }
    }

    private void processActionTaskWrapper(Object key) {
        if (errorCause != null) {
            return;
        }
        try {
            processActionTask(key);
        } catch (Exception e) {
            errorCause = new ActionTaskExecutionException("Failed to execute action task", e);
            ExceptionUtils.rethrow(e);
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
        } else {
            Object outputFromOutputEvent =
                    pythonActionExecutor.getOutputFromOutputEvent(((PythonEvent) event).getEvent());
            return (OUT) outputFromOutputEvent;
        }
    }

    private List<Action> getActionsTriggeredBy(Event event) {
        if (event instanceof PythonEvent) {
            return agentPlan.getActionsTriggeredBy(((PythonEvent) event).getEventType());
        } else {
            return agentPlan.getActionsTriggeredBy(event.getClass().getName());
        }
    }

    private <T> T pollFromListState(ListState<T> listState) throws Exception {
        Iterator<T> listStateIterator = listState.get().iterator();
        if (!listStateIterator.hasNext()) {
            return null;
        }

        T polled = listStateIterator.next();
        List<T> remaining = new ArrayList<>();
        while (listStateIterator.hasNext()) {
            remaining.add(listStateIterator.next());
        }
        listState.clear();
        listState.update(remaining);
        return polled;
    }

    private ActionTask createActionTask(Object key, Action action, Event event) {
        if (action.getExec() instanceof JavaFunction) {
            return new JavaActionTask(
                    key,
                    event,
                    action,
                    new RunnerContextImpl(shortTermMemState, this::checkMailboxThread));
        } else if (action.getExec() instanceof PythonFunction) {
            return new PythonActionTask(
                    key,
                    event,
                    action,
                    pythonActionExecutor,
                    new PythonRunnerContextImpl(shortTermMemState, this::checkMailboxThread));
        } else {
            throw new IllegalStateException(
                    "Unsupported action type: " + action.getExec().getClass());
        }
    }

    private void addActionTask(ActionTask actionTask) throws Exception {
        actionTasksState.add(actionTask);
        mailboxExecutor.submit(
                () -> processActionTaskWrapper(actionTask.getKey()), "process action task");
    }

    private boolean listStateNotEmpty(ListState<?> listState) throws Exception {
        return listState.get() != null && listState.get().iterator().hasNext();
    }

    private boolean currentKeyHasMoreActionTask() throws Exception {
        return listStateNotEmpty(actionTasksState);
    }

    private <T> int removeFromListState(ListState<T> listState, T element) throws Exception {
        Iterator<T> listStateIterator = listState.get().iterator();
        if (!listStateIterator.hasNext()) {
            return 0;
        }

        int removedElementCount = 0;
        List<T> remaining = new ArrayList<>();
        while (listStateIterator.hasNext()) {
            T next = listStateIterator.next();
            if (next.equals(element)) {
                removedElementCount++;
                continue;
            }
            remaining.add(next);
        }
        listState.clear();
        listState.update(remaining);
        return removedElementCount;
    }

    @VisibleForTesting
    public void waitInFlightEventsFinished() throws Exception {
        while (listStateNotEmpty(currentProcessingKeysOperatorState)) {
            checkError();
            mailboxExecutor.yield();
        }
    }

    @Override
    public void endInput() throws Exception {
        waitInFlightEventsFinished();
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

    private void checkError() {
        if (errorCause != null) {
            ExceptionUtils.rethrow(errorCause);
        }
    }

    /** Failed to execute Action task. */
    public static class ActionTaskExecutionException extends Exception {
        public ActionTaskExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
