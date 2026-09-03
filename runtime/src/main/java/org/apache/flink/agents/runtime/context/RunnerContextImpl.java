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
package org.apache.flink.agents.runtime.context;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.memory.BaseLongTermMemory;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.utils.JsonUtils;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.CallResult;
import org.apache.flink.agents.runtime.memory.CachedMemoryStore;
import org.apache.flink.agents.runtime.memory.InteranlBaseLongTermMemory;
import org.apache.flink.agents.runtime.memory.MemoryEventBuilder;
import org.apache.flink.agents.runtime.memory.MemoryEventSettings;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.memory.MemoryValueObservation;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.trace.ExecutionEventSink;
import org.apache.flink.agents.runtime.trace.ReportedExecutionKey;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The implementation class of {@link RunnerContext}, which serves as the execution context for
 * actions.
 */
public class RunnerContextImpl implements RunnerContext, ExecutionReporter {

    protected static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    public static class MemoryContext {
        private final CachedMemoryStore sensoryMemStore;
        private final CachedMemoryStore shortTermMemStore;
        private final List<MemoryUpdate> sensoryMemoryUpdates;
        private final List<MemoryUpdate> shortTermMemoryUpdates;
        private final List<MemoryValueObservation> sensoryMemoryReads;
        private final List<MemoryValueObservation> shortTermMemoryReads;

        public MemoryContext(
                CachedMemoryStore sensoryMemStore, CachedMemoryStore shortTermMemStore) {
            this.sensoryMemStore = sensoryMemStore;
            this.shortTermMemStore = shortTermMemStore;
            this.sensoryMemoryUpdates = new LinkedList<>();
            this.shortTermMemoryUpdates = new LinkedList<>();
            this.sensoryMemoryReads = new LinkedList<>();
            this.shortTermMemoryReads = new LinkedList<>();
        }

        public List<MemoryUpdate> getShortTermMemoryUpdates() {
            return shortTermMemoryUpdates;
        }

        public List<MemoryUpdate> getSensoryMemoryUpdates() {
            return sensoryMemoryUpdates;
        }

        public List<MemoryValueObservation> getSensoryMemoryReads() {
            return sensoryMemoryReads;
        }

        public List<MemoryValueObservation> getShortTermMemoryReads() {
            return shortTermMemoryReads;
        }

        private void clearReadObservations() {
            sensoryMemoryReads.clear();
            shortTermMemoryReads.clear();
        }

        public CachedMemoryStore getShortTermMemStore() {
            return shortTermMemStore;
        }

        public CachedMemoryStore getSensoryMemStore() {
            return sensoryMemStore;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RunnerContextImpl.class);

    protected final List<Event> pendingEvents = new ArrayList<>();
    protected final FlinkAgentsMetricGroupImpl agentMetricGroup;
    protected final Runnable mailboxThreadChecker;
    protected final AgentPlan agentPlan;
    protected final ResourceCache resourceCache;

    protected MemoryContext memoryContext;
    protected String actionName;
    protected InteranlBaseLongTermMemory ltm;

    /** Textual key shared by long-term-memory isolation and framework observation events. */
    protected String contextKey;

    /** Stable identifier that isolates observations for one logical action execution. */
    protected String observationId;

    /** True when the current action was triggered by a memory event: suppress observation. */
    protected boolean observationSuppressed;

    /** True when at least one LTM operation records observations for the current action. */
    protected boolean ltmObservationEnabled;

    /** Resolved per-operation memory-event switches; config is fixed per agent plan. */
    private final MemoryEventSettings memoryEventSettings;

    /** Whether the fixed job-level configuration enables any LTM observation. */
    private final boolean ltmObservationConfigured;

    @Nullable protected ExecutionTraceContext actionTraceContext;
    @Nullable protected ExecutionEventSink executionEventSink;
    @Nullable private Map<ReportedExecutionKey, ExecutionTraceContext> activeReportedExecutions;

    /** Context for fine-grained durable execution, may be null if not enabled. */
    @Nullable protected DurableExecutionContext durableExecutionContext;

    public RunnerContextImpl(
            FlinkAgentsMetricGroupImpl agentMetricGroup,
            Runnable mailboxThreadChecker,
            AgentPlan agentPlan,
            ResourceCache resourceCache,
            String jobIdentifier) {
        this.agentMetricGroup = agentMetricGroup;
        this.mailboxThreadChecker = mailboxThreadChecker;
        this.agentPlan = agentPlan;
        this.resourceCache = resourceCache;
        this.memoryEventSettings = MemoryEventSettings.from(agentPlan.getConfigData());
        this.ltmObservationConfigured =
                memoryEventSettings.generate(MemoryEventSettings.MemoryOp.LONG_TERM_UPDATE)
                        || memoryEventSettings.generate(MemoryEventSettings.MemoryOp.LONG_TERM_GET)
                        || memoryEventSettings.generate(
                                MemoryEventSettings.MemoryOp.LONG_TERM_SEARCH);
    }

    public void setLongTermMemory(InteranlBaseLongTermMemory ltm) {
        this.ltm = ltm;
    }

    public void switchActionContext(
            String actionName,
            MemoryContext memoryContext,
            String contextKey,
            String observationId,
            boolean observationSuppressed) {
        switchActionContext(
                actionName,
                memoryContext,
                contextKey,
                observationId,
                observationSuppressed,
                null,
                null);
    }

    public void switchActionContext(
            String actionName,
            MemoryContext memoryContext,
            String contextKey,
            @Nullable ExecutionTraceContext actionTraceContext,
            @Nullable Map<ReportedExecutionKey, ExecutionTraceContext> activeReportedExecutions) {
        switchActionContext(
                actionName,
                memoryContext,
                contextKey,
                null,
                false,
                actionTraceContext,
                activeReportedExecutions);
    }

    public void switchActionContext(
            String actionName,
            MemoryContext memoryContext,
            String contextKey,
            @Nullable String observationId,
            boolean observationSuppressed,
            @Nullable ExecutionTraceContext actionTraceContext,
            @Nullable Map<ReportedExecutionKey, ExecutionTraceContext> activeReportedExecutions) {
        this.actionName = actionName;
        this.memoryContext = memoryContext;
        this.contextKey = contextKey;
        this.observationId = observationId;
        this.observationSuppressed = observationSuppressed;
        this.ltmObservationEnabled = !observationSuppressed && ltmObservationConfigured;
        this.actionTraceContext = actionTraceContext;
        this.activeReportedExecutions = activeReportedExecutions;
        if (ltm != null) {
            ltm.switchContext(contextKey, observationId, observationSuppressed);
        }
    }

    public void setExecutionEventSink(@Nullable ExecutionEventSink executionEventSink) {
        this.executionEventSink = executionEventSink;
    }

    public MemoryContext getMemoryContext() {
        return memoryContext;
    }

    @Override
    public FlinkAgentsMetricGroupImpl getAgentMetricGroup() {
        return agentMetricGroup;
    }

    @Override
    public FlinkAgentsMetricGroupImpl getActionMetricGroup() {
        return agentMetricGroup.getSubGroup("action", actionName);
    }

    @Override
    public void sendEvent(Event event) {
        mailboxThreadChecker.run();
        try {
            JsonUtils.checkSerializable(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Event is not JSON serializable. All events sent to context must be JSON serializable.",
                    e);
        }
        pendingEvents.add(event);
    }

    public List<Event> drainEvents(Long timestamp) {
        mailboxThreadChecker.run();
        return drainPendingEvents(timestamp);
    }

    /** Converts this action's memory records into events and drains all action output events. */
    public List<Event> drainEventsAtActionFinish(Long timestamp) {
        mailboxThreadChecker.run();
        flushMemoryObservation();
        return drainPendingEvents(timestamp);
    }

    /**
     * Discards pending LTM observation records for the current key without rolling back written
     * data.
     */
    public void discardMemoryObservation() {
        mailboxThreadChecker.run();
        if (memoryContext != null) {
            memoryContext.clearReadObservations();
        }
        if (ltm == null || !ltmObservationEnabled) {
            return;
        }
        ltm.drainObservationRecordsJson(contextKey, observationId);
    }

    private List<Event> drainPendingEvents(Long timestamp) {
        List<Event> list = new ArrayList<>(this.pendingEvents);
        if (timestamp != null) {
            list.forEach(event -> event.setSourceTimestamp(timestamp));
        }
        this.pendingEvents.clear();
        return list;
    }

    private void flushMemoryObservation() {
        if (memoryContext == null) {
            return;
        }
        List<MemoryValueObservation> sensoryReads =
                new ArrayList<>(memoryContext.getSensoryMemoryReads());
        List<MemoryValueObservation> shortTermReads =
                new ArrayList<>(memoryContext.getShortTermMemoryReads());
        memoryContext.clearReadObservations();
        if (observationSuppressed || !memoryEventSettings.anyEnabled()) {
            return;
        }
        List<Map<String, Object>> ltmRecords = Collections.emptyList();
        if (ltm != null && ltmObservationEnabled) {
            try {
                ltmRecords =
                        MemoryEventBuilder.parseLtmObservationRecords(
                                ltm.drainObservationRecordsJson(contextKey, observationId));
            } catch (Exception | LinkageError e) {
                LOG.warn(
                        "LTM observation drain failed for action '{}' and partition key '{}' ({}); skipping records",
                        actionName,
                        contextKey,
                        e.getClass().getSimpleName());
            }
        }
        try {
            pendingEvents.addAll(
                    MemoryEventBuilder.buildWriteEvents(
                            contextKey,
                            memoryContext.getSensoryMemoryUpdates(),
                            memoryContext.getShortTermMemoryUpdates(),
                            memoryEventSettings));
            pendingEvents.addAll(
                    MemoryEventBuilder.buildReadEvents(
                            contextKey, sensoryReads, shortTermReads, memoryEventSettings));
            pendingEvents.addAll(
                    MemoryEventBuilder.buildLtmEvents(contextKey, ltmRecords, memoryEventSettings));
        } catch (RuntimeException | LinkageError e) {
            LOG.warn(
                    "Skipping framework memory observation for action '{}' ({})",
                    actionName,
                    e.getClass().getSimpleName());
        }
    }

    public void checkNoPendingEvents() {
        Preconditions.checkState(
                this.pendingEvents.isEmpty(), "There are pending events remaining in the context.");
    }

    public List<MemoryUpdate> getSensoryMemoryUpdates() {
        mailboxThreadChecker.run();
        return List.copyOf(memoryContext.getSensoryMemoryUpdates());
    }

    /**
     * Gets all the updates made to this MemoryObject since it was created or the last time this
     * method was called. This method lives here because it is internally used by the ActionTask to
     * persist memory updates after an action is executed.
     *
     * @return list of memory updates
     */
    public List<MemoryUpdate> getShortTermMemoryUpdates() {
        mailboxThreadChecker.run();
        return List.copyOf(memoryContext.getShortTermMemoryUpdates());
    }

    @Override
    public void reportExecutionStarted(
            String entityType, String entityName, Map<String, Object> entityMetadata)
            throws Exception {
        reportChildExecution(
                entityType,
                entityName,
                entityMetadata,
                ExecutionLifecycleEvents.executionStarted());
    }

    @Override
    public void reportExecutionStartedAt(
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            String timestamp)
            throws Exception {
        Event event = ExecutionLifecycleEvents.executionStarted();
        reportChildExecution(
                entityType,
                entityName,
                entityMetadata,
                new EventContext(event.getType(), timestamp),
                event);
    }

    @Override
    public void reportExecutionSucceeded(
            String entityType, String entityName, Map<String, Object> entityMetadata)
            throws Exception {
        reportChildExecution(
                entityType,
                entityName,
                entityMetadata,
                ExecutionLifecycleEvents.executionFinished());
    }

    @Override
    public void reportExecutionSucceededAt(
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            String timestamp)
            throws Exception {
        Event event = ExecutionLifecycleEvents.executionFinished();
        reportChildExecution(
                entityType,
                entityName,
                entityMetadata,
                new EventContext(event.getType(), timestamp),
                event);
    }

    @Override
    public void reportExecutionFailed(
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            Throwable error,
            @Nullable String problemCategory)
            throws Exception {
        reportChildExecution(
                entityType,
                entityName,
                entityMetadata,
                ExecutionLifecycleEvents.executionFailed(error, problemCategory));
    }

    @Override
    public void reportExecutionFailedAt(
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            Throwable error,
            @Nullable String problemCategory,
            String timestamp)
            throws Exception {
        Event event = ExecutionLifecycleEvents.executionFailed(error, problemCategory);
        reportChildExecution(
                entityType,
                entityName,
                entityMetadata,
                new EventContext(event.getType(), timestamp),
                event);
    }

    protected void reportChildExecution(
            String entityType, String entityName, Map<String, Object> entityMetadata, Event event) {
        reportChildExecution(
                entityType, entityName, entityMetadata, new EventContext(event), event);
    }

    protected void reportChildExecution(
            String entityType,
            String entityName,
            Map<String, Object> entityMetadata,
            EventContext eventContext,
            Event event) {
        mailboxThreadChecker.run();
        if (actionTraceContext == null
                || executionEventSink == null
                || activeReportedExecutions == null) {
            return;
        }

        ReportedExecutionKey key = new ReportedExecutionKey(entityType, entityName, entityMetadata);
        ExecutionTraceContext reportTraceContext;
        if (ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE.equals(event.getType())) {
            reportTraceContext =
                    actionTraceContext.childExecution(
                            entityType, entityName, key.getEntityMetadata());
            ExecutionTraceContext previous = activeReportedExecutions.put(key, reportTraceContext);
            if (previous != null) {
                LOG.debug(
                        "Execution start report for {}:{} replaced an active report with the same metadata.",
                        entityType,
                        entityName);
            }
        } else {
            reportTraceContext = activeReportedExecutions.remove(key);
            if (reportTraceContext == null) {
                LOG.debug(
                        "Execution terminal report for {}:{} has no matching start report; emitting it with a new execution id.",
                        entityType,
                        entityName);
                reportTraceContext =
                        actionTraceContext.childExecution(
                                entityType, entityName, key.getEntityMetadata());
            }
        }

        executionEventSink.emit(eventContext, event, reportTraceContext);
    }

    @Override
    public MemoryObject getSensoryMemory() throws Exception {
        mailboxThreadChecker.run();
        List<MemoryValueObservation> memoryReads = null;
        if (!observationSuppressed
                && memoryEventSettings.generate(MemoryEventSettings.MemoryOp.SENSORY_READ)) {
            memoryReads = memoryContext.getSensoryMemoryReads();
        }
        return new MemoryObjectImpl(
                MemoryObject.MemoryType.SENSORY,
                memoryContext.getSensoryMemStore(),
                MemoryObjectImpl.ROOT_KEY,
                mailboxThreadChecker,
                memoryContext.getSensoryMemoryUpdates(),
                memoryReads);
    }

    @Override
    public MemoryObject getShortTermMemory() throws Exception {
        mailboxThreadChecker.run();
        List<MemoryValueObservation> memoryReads = null;
        if (!observationSuppressed
                && memoryEventSettings.generate(MemoryEventSettings.MemoryOp.SHORT_TERM_READ)) {
            memoryReads = memoryContext.getShortTermMemoryReads();
        }
        return new MemoryObjectImpl(
                MemoryObject.MemoryType.SHORT_TERM,
                memoryContext.getShortTermMemStore(),
                MemoryObjectImpl.ROOT_KEY,
                mailboxThreadChecker,
                memoryContext.getShortTermMemoryUpdates(),
                memoryReads);
    }

    @Override
    public BaseLongTermMemory getLongTermMemory() throws Exception {
        Preconditions.checkNotNull(this.ltm);
        return this.ltm;
    }

    @Override
    public Resource getResource(String name, ResourceType type) throws Exception {
        mailboxThreadChecker.run();
        if (resourceCache == null) {
            throw new IllegalStateException("ResourceCache is not available in this context");
        }
        Resource resource = resourceCache.getResource(name, type);
        // Set current action's metric group to the resource
        resource.setMetricGroup(getActionMetricGroup());
        return resource;
    }

    @Override
    public boolean hasResource(String name, ResourceType type) {
        return resourceCache != null && resourceCache.hasResource(name, type);
    }

    @Override
    public ReadableConfiguration getConfig() {
        return agentPlan.getConfig();
    }

    @Override
    public Map<String, Object> getActionConfig() {
        return agentPlan.getActionConfig(actionName);
    }

    @Override
    public Object getActionConfigValue(String key) {
        return agentPlan.getActionConfigValue(actionName, key);
    }

    @Override
    public <T> T durableExecute(DurableCallable<T> callable) throws Exception {
        if (durableExecutionContext != null) {
            Callable<T> reconcileCallable = callable.reconciler();
            if (reconcileCallable != null) {
                return durableExecuteSyncWithReconcile(callable, reconcileCallable);
            }
        }
        return durableExecuteCompletionOnly(callable, callable::call);
    }

    @Override
    public <T> T durableExecuteAsync(DurableCallable<T> callable) throws Exception {
        LOG.debug(
                "Async durable execution is not supported in RunnerContextImpl; falling back to durableExecute for {}",
                callable.getId());
        return durableExecute(callable);
    }

    @Override
    public <T> List<Outcome<T>> durableExecuteAllAsync(List<DurableCallable<T>> callables)
            throws Exception {
        List<Outcome<T>> outcomes = new ArrayList<>(callables.size());
        for (DurableCallable<T> callable : callables) {
            try {
                outcomes.add(Outcome.success(durableExecute(callable)));
            } catch (Exception e) {
                outcomes.add(Outcome.failure(e));
            }
        }
        return outcomes;
    }

    /**
     * Executes a durable call using the completion-only state machine.
     *
     * @param durableCallable durable call that provides the durable execution identity and result
     *     metadata
     * @param executionCallable concrete execution boundary for the current path, such as direct
     *     sync execution or Java-specific async execution
     */
    protected <T> T durableExecuteCompletionOnly(
            DurableCallable<T> durableCallable, Callable<T> executionCallable) throws Exception {
        String functionId = durableCallable.getId();
        // argsDigest is empty because DurableCallable encapsulates all arguments internally
        String argsDigest = "";

        CallResult current = getCurrentCallResult();
        if (current != null && current.matches(functionId, argsDigest) && current.isPending()) {
            return executeAndFinalizeCurrentCall(functionId, argsDigest, executionCallable);
        }

        Optional<T> cachedResult =
                tryGetCachedResult(functionId, argsDigest, durableCallable.getResultClass());
        if (cachedResult.isPresent()) {
            return cachedResult.get();
        }

        T result = null;
        Exception exception = null;
        try {
            result = executionCallable.call();
        } catch (Exception e) {
            exception = e;
        }

        recordDurableCompletion(functionId, argsDigest, result, exception);

        if (exception != null) {
            throw exception;
        }
        return result;
    }

    private <T> T durableExecuteSyncWithReconcile(
            DurableCallable<T> callable, Callable<T> reconcileCallable) throws Exception {
        return durableExecuteWithReconcile(callable, reconcileCallable, callable::call);
    }

    /** Serializable exception info for durable execution persistence. */
    public static class DurableExecutionException {
        private static final String FIELD_MESSAGE = "message";
        private static final String FIELD_EXCEPTION_CLASS = "exceptionClass";

        @JsonProperty(FIELD_EXCEPTION_CLASS)
        private final String exceptionClass;

        @JsonProperty(FIELD_MESSAGE)
        private final String message;

        public DurableExecutionException() {
            this.exceptionClass = null;
            this.message = null;
        }

        public DurableExecutionException(String exceptionClass, String message) {
            this.exceptionClass = exceptionClass;
            this.message = message;
        }

        public static DurableExecutionException fromException(Exception e) {
            return new DurableExecutionException(e.getClass().getName(), e.getMessage());
        }

        public Exception toException() {
            if (exceptionClass == null) {
                return new RuntimeException(message);
            }
            try {
                ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                if (classLoader == null) {
                    classLoader = RunnerContextImpl.class.getClassLoader();
                }
                Class<?> clazz = Class.forName(exceptionClass, true, classLoader);
                if (Exception.class.isAssignableFrom(clazz)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Exception> exceptionClazz = (Class<? extends Exception>) clazz;
                    try {
                        return exceptionClazz.getConstructor(String.class).newInstance(message);
                    } catch (NoSuchMethodException ignored) {
                        return new RuntimeException(exceptionClass + ": " + message);
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall back to a generic wrapper below.
            }
            return new RuntimeException(exceptionClass + ": " + message);
        }
    }

    @Override
    public void close() throws Exception {
        if (this.ltm != null) {
            this.ltm.close();
            this.ltm = null;
        }
    }

    public String getActionName() {
        return actionName;
    }

    public void persistMemory() throws Exception {
        memoryContext.getSensoryMemStore().persistCache();
        memoryContext.getShortTermMemStore().persistCache();
    }

    public void clearSensoryMemory() throws Exception {
        memoryContext.getSensoryMemStore().clear();
    }

    public void setDurableExecutionContext(
            @Nullable DurableExecutionContext durableExecutionContext) {
        this.durableExecutionContext = durableExecutionContext;
    }

    @Nullable
    public DurableExecutionContext getDurableExecutionContext() {
        return durableExecutionContext;
    }

    public void clearDurableExecutionContext() {
        this.durableExecutionContext = null;
    }

    /**
     * Matches the next call result for recovery, or clears subsequent results if mismatch detected.
     *
     * <p>This method delegates to the {@link DurableExecutionContext} if present.
     *
     * @param functionId the function identifier
     * @param argsDigest the digest of serialized arguments
     * @return array containing [isHit (boolean), resultPayload (byte[]), exceptionPayload
     *     (byte[])], or null if miss or durable execution is not enabled
     */
    public Object[] matchNextOrClearSubsequentCallResult(String functionId, String argsDigest) {
        mailboxThreadChecker.run();
        if (durableExecutionContext != null) {
            return durableExecutionContext.matchNextOrClearSubsequentCallResult(
                    functionId, argsDigest);
        }
        return null;
    }

    /**
     * Records a completed call and persists the ActionState.
     *
     * <p>This method delegates to the {@link DurableExecutionContext} if present.
     *
     * @param functionId the function identifier
     * @param argsDigest the digest of serialized arguments
     * @param resultPayload the serialized result (null if exception)
     * @param exceptionPayload the serialized exception (null if success)
     */
    public void recordCallCompletion(
            String functionId, String argsDigest, byte[] resultPayload, byte[] exceptionPayload) {
        mailboxThreadChecker.run();
        if (durableExecutionContext != null) {
            durableExecutionContext.recordCallCompletion(
                    functionId, argsDigest, resultPayload, exceptionPayload);
        }
    }

    /** Appends a pending durable call slot at the current call index. */
    public void appendPendingCall(String functionId, String argsDigest) {
        mailboxThreadChecker.run();
        if (durableExecutionContext != null) {
            durableExecutionContext.appendPendingCall(functionId, argsDigest);
        }
    }

    public void reservePendingBatch(List<String> functionIds, List<String> argsDigests) {
        mailboxThreadChecker.run();
        if (durableExecutionContext != null && !functionIds.isEmpty()) {
            durableExecutionContext.reservePendingBatch(functionIds, argsDigests);
        }
    }

    /** Finalizes the pending durable call slot at the current call index. */
    public void finalizeCurrentCall(
            String functionId, String argsDigest, byte[] resultPayload, byte[] exceptionPayload) {
        mailboxThreadChecker.run();
        if (durableExecutionContext != null) {
            durableExecutionContext.finalizeCurrentCall(
                    functionId, argsDigest, resultPayload, exceptionPayload);
        }
    }

    public void finalizeCallAt(
            int index,
            String functionId,
            String argsDigest,
            byte[] resultPayload,
            byte[] exceptionPayload) {
        mailboxThreadChecker.run();
        if (durableExecutionContext != null) {
            durableExecutionContext.finalizeCallAt(
                    index, functionId, argsDigest, resultPayload, exceptionPayload);
        }
    }

    public void advanceCallIndexBy(int count) {
        mailboxThreadChecker.run();
        if (durableExecutionContext != null) {
            durableExecutionContext.advanceCallIndexBy(count);
        }
    }

    /**
     * Clears persisted call results from the current call index onward and persists immediately.
     */
    public void clearCallResultsFromCurrentIndexAndPersist() {
        mailboxThreadChecker.run();
        if (durableExecutionContext != null) {
            durableExecutionContext.clearCallResultsFromCurrentIndexAndPersist();
        }
    }

    public void clearCallResultsFromAndPersist(int index) {
        mailboxThreadChecker.run();
        if (durableExecutionContext != null) {
            durableExecutionContext.clearCallResultsFromAndPersist(index);
        }
    }

    public int getCurrentCallIndex() {
        mailboxThreadChecker.run();
        if (durableExecutionContext == null) {
            return 0;
        }
        return durableExecutionContext.getCurrentCallIndex();
    }

    public Object[] getCallResultFieldsAt(int index) {
        CallResult current = getCallResultAt(index);
        if (current == null) {
            return null;
        }
        return new Object[] {
            current.getFunctionId(),
            current.getArgsDigest(),
            current.isPending() ? "PENDING" : current.isFailure() ? "FAILED" : "SUCCEEDED",
            current.getResultPayload(),
            current.getExceptionPayload()
        };
    }

    /**
     * Returns the current durable call result as an array of fields for bridge consumers, or null
     * if no persisted slot exists at the current call index.
     */
    public Object[] getCurrentCallResultFields() {
        if (durableExecutionContext == null) {
            return null;
        }
        return getCallResultFieldsAt(durableExecutionContext.getCurrentCallIndex());
    }

    protected <T> Outcome<T> readTerminalOutcomeAt(
            int index, String functionId, String argsDigest, Class<T> resultClass)
            throws Exception {
        CallResult callResult = getCallResultAt(index);
        if (callResult == null || callResult.isPending()) {
            throw new IllegalStateException(
                    String.format(
                            "Expected a terminal durable call result at index %s for "
                                    + "functionId=%s, argsDigest=%s",
                            index, functionId, argsDigest));
        }
        try {
            if (callResult.getExceptionPayload() != null) {
                DurableExecutionException exception =
                        OBJECT_MAPPER.readValue(
                                callResult.getExceptionPayload(), DurableExecutionException.class);
                return Outcome.failure(exception.toException());
            }
            if (callResult.getResultPayload() == null) {
                return Outcome.success(null);
            }
            return Outcome.success(
                    OBJECT_MAPPER.readValue(callResult.getResultPayload(), resultClass));
        } catch (JsonProcessingException e) {
            return Outcome.failure(e);
        }
    }

    protected CallResult getCurrentCallResult() {
        mailboxThreadChecker.run();
        if (durableExecutionContext != null) {
            return durableExecutionContext.getCurrentCallResult();
        }
        return null;
    }

    protected CallResult getCallResultAt(int index) {
        mailboxThreadChecker.run();
        if (durableExecutionContext != null) {
            return durableExecutionContext.getCallResultAt(index);
        }
        return null;
    }

    protected <T> Optional<T> tryGetCachedResult(
            String functionId, String argsDigest, Class<T> resultClass) throws Exception {
        Object[] cached = matchNextOrClearSubsequentCallResult(functionId, argsDigest);
        if (cached != null && (Boolean) cached[0]) {
            byte[] resultPayload = (byte[]) cached[1];
            byte[] exceptionPayload = (byte[]) cached[2];

            if (exceptionPayload != null) {
                DurableExecutionException cachedException =
                        OBJECT_MAPPER.readValue(exceptionPayload, DurableExecutionException.class);
                throw cachedException.toException();
            } else if (resultPayload != null) {
                return Optional.of(OBJECT_MAPPER.readValue(resultPayload, resultClass));
            } else {
                return Optional.of(null);
            }
        }
        return Optional.empty();
    }

    protected void recordDurableCompletion(
            String functionId, String argsDigest, Object result, Exception exception)
            throws Exception {
        byte[] resultPayload = serializeDurableResult(result);
        byte[] exceptionPayload = serializeDurableException(exception);
        recordCallCompletion(functionId, argsDigest, resultPayload, exceptionPayload);
    }

    /**
     * Executes a durable call using the reconcile-enabled state machine.
     *
     * @param durableCallable durable call that provides the durable execution identity and result
     *     metadata
     * @param reconcileCallable reconcile boundary used to recover a terminal outcome from a pending
     *     durable call
     * @param executionCallable concrete execution boundary for the current path when recovery
     *     starts or restarts the original durable call
     */
    protected <T> T durableExecuteWithReconcile(
            DurableCallable<T> durableCallable,
            Callable<T> reconcileCallable,
            Callable<T> executionCallable)
            throws Exception {
        String functionId = durableCallable.getId();
        String argsDigest = "";
        Preconditions.checkState(
                durableExecutionContext != null, "durableExecutionContext must not be null");

        CallResult current = getCurrentCallResult();

        if (current == null) {
            appendPendingCall(functionId, argsDigest);
            return executeAndFinalizeCurrentCall(functionId, argsDigest, executionCallable);
        }

        if (!current.matches(functionId, argsDigest)) {
            clearCallResultsFromCurrentIndexAndPersist();
            appendPendingCall(functionId, argsDigest);
            return executeAndFinalizeCurrentCall(functionId, argsDigest, executionCallable);
        }

        if (!current.isPending()) {
            Optional<T> cachedResult =
                    tryGetCachedResult(functionId, argsDigest, durableCallable.getResultClass());
            if (cachedResult.isPresent()) {
                return cachedResult.get();
            }
            throw new IllegalStateException(
                    String.format(
                            "Expected a terminal durable call result at index %s for "
                                    + "functionId=%s, argsDigest=%s",
                            durableExecutionContext.getCurrentCallIndex(), functionId, argsDigest));
        }

        return executeAndFinalizeCurrentCall(functionId, argsDigest, reconcileCallable);
    }

    protected <T> T executeAndFinalizeCurrentCall(
            String functionId, String argsDigest, Callable<T> callSupplier) throws Exception {
        T result = null;
        Exception exception = null;
        try {
            result = callSupplier.call();
        } catch (Exception e) {
            exception = e;
        }

        finalizeCurrentCall(
                functionId,
                argsDigest,
                serializeDurableResult(result),
                serializeDurableException(exception));

        if (exception != null) {
            throw exception;
        }
        return result;
    }

    protected byte[] serializeDurableResult(Object result) throws JsonProcessingException {
        if (result == null) {
            return null;
        }
        return OBJECT_MAPPER.writeValueAsBytes(result);
    }

    protected byte[] serializeDurableException(Exception exception) throws JsonProcessingException {
        if (exception == null) {
            return null;
        }
        return OBJECT_MAPPER.writeValueAsBytes(DurableExecutionException.fromException(exception));
    }

    protected static class DurableExecutionRuntimeException extends RuntimeException {
        DurableExecutionRuntimeException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Context for fine-grained durable execution within an action.
     *
     * <p>This class encapsulates all state needed for {@code durable_execute}/{@code
     * durable_execute_async} recovery. During normal execution, each call is recorded as a {@link
     * CallResult}. During recovery, these results are used to skip re-execution of already
     * completed calls.
     */
    public static class DurableExecutionContext {
        private final Object key;
        private final long sequenceNumber;
        private final Action action;
        private final Event event;
        private final ActionState actionState;
        private final ActionStatePersister persister;

        /** Current call index within the action, used for matching CallResults during recovery. */
        private int currentCallIndex;

        /** Snapshot of CallResults loaded during recovery. */
        private List<CallResult> recoveryCallResults;

        public DurableExecutionContext(
                Object key,
                long sequenceNumber,
                Action action,
                Event event,
                ActionState actionState,
                ActionStatePersister persister) {
            this.key = key;
            this.sequenceNumber = sequenceNumber;
            this.action = action;
            this.event = event;
            this.actionState = actionState;
            this.persister = persister;
            this.currentCallIndex = 0;
            this.recoveryCallResults =
                    actionState.getCallResults() != null
                            ? new ArrayList<>(actionState.getCallResults())
                            : new ArrayList<>();
        }

        public int getCurrentCallIndex() {
            return currentCallIndex;
        }

        public ActionState getActionState() {
            return actionState;
        }

        /**
         * Returns the call result at the current call index, or null if the current index does not
         * yet have a persisted slot.
         */
        public CallResult getCurrentCallResult() {
            return getCallResultAt(currentCallIndex);
        }

        public CallResult getCallResultAt(int index) {
            if (index < recoveryCallResults.size()) {
                return recoveryCallResults.get(index);
            }
            return null;
        }

        /**
         * Matches the next call result for recovery, or clears subsequent results if mismatch
         * detected.
         *
         * @param functionId the function identifier
         * @param argsDigest the digest of serialized arguments
         * @return array containing [isHit, resultPayload, exceptionPayload], or null if miss
         */
        public Object[] matchNextOrClearSubsequentCallResult(String functionId, String argsDigest) {
            if (currentCallIndex < recoveryCallResults.size()) {
                CallResult result = recoveryCallResults.get(currentCallIndex);

                if (result.matches(functionId, argsDigest)) {
                    if (result.isPending()) {
                        LOG.debug(
                                "Pending CallResult at index {} treated as cache miss: "
                                        + "functionId={}, argsDigest={}",
                                currentCallIndex,
                                functionId,
                                argsDigest);
                        return null;
                    }
                    LOG.debug(
                            "CallResult hit at index {}: functionId={}, argsDigest={}",
                            currentCallIndex,
                            functionId,
                            argsDigest);
                    currentCallIndex++;
                    return new Object[] {
                        true, result.getResultPayload(), result.getExceptionPayload()
                    };
                } else {
                    LOG.warn(
                            "Non-deterministic call detected at index {}: expected functionId={}, "
                                    + "argsDigest={}, but got functionId={}, argsDigest={}. "
                                    + "Clearing subsequent results.",
                            currentCallIndex,
                            result.getFunctionId(),
                            result.getArgsDigest(),
                            functionId,
                            argsDigest);
                    clearCallResultsFromCurrentIndex();
                }
            }
            return null;
        }

        /**
         * Records a completed call and persists the ActionState.
         *
         * @param functionId the function identifier
         * @param argsDigest the digest of serialized arguments
         * @param resultPayload the serialized result (null if exception)
         * @param exceptionPayload the serialized exception (null if success)
         */
        public void recordCallCompletion(
                String functionId,
                String argsDigest,
                byte[] resultPayload,
                byte[] exceptionPayload) {
            CallResult callResult =
                    new CallResult(functionId, argsDigest, resultPayload, exceptionPayload);

            actionState.addCallResult(callResult);
            recoveryCallResults.add(callResult);
            persistActionState();

            LOG.debug(
                    "Recorded and persisted CallResult at index {}: functionId={}, argsDigest={}",
                    currentCallIndex,
                    functionId,
                    argsDigest);

            currentCallIndex++;
        }

        /**
         * Appends and persists a pending slot for the current call index.
         *
         * <p>This reserves the current slot for a reconcilable durable call but does not advance
         * {@code currentCallIndex}.
         */
        public void appendPendingCall(String functionId, String argsDigest) {
            if (currentCallIndex != recoveryCallResults.size()) {
                throw new IllegalStateException(
                        String.format(
                                "Cannot append pending call at index %s when a persisted slot "
                                        + "already exists",
                                currentCallIndex));
            }

            CallResult pending = CallResult.pending(functionId, argsDigest);
            actionState.addCallResult(pending);
            recoveryCallResults.add(pending);
            persistActionState();

            LOG.debug(
                    "Recorded and persisted pending CallResult at index {}: functionId={}, "
                            + "argsDigest={}",
                    currentCallIndex,
                    functionId,
                    argsDigest);
        }

        public void reservePendingBatch(List<String> functionIds, List<String> argsDigests) {
            if (functionIds.size() != argsDigests.size()) {
                throw new IllegalArgumentException(
                        String.format(
                                "functionIds size (%s) must match argsDigests size (%s)",
                                functionIds.size(), argsDigests.size()));
            }
            for (int i = 0; i < functionIds.size(); i++) {
                CallResult pending = CallResult.pending(functionIds.get(i), argsDigests.get(i));
                actionState.addCallResult(pending);
                recoveryCallResults.add(pending);
            }
            persistActionState();
        }

        /**
         * Replaces the current persisted slot with a terminal call result and advances the current
         * call index.
         */
        public void finalizeCurrentCall(
                String functionId,
                String argsDigest,
                byte[] resultPayload,
                byte[] exceptionPayload) {
            finalizeCallAt(
                    currentCallIndex, functionId, argsDigest, resultPayload, exceptionPayload);
            currentCallIndex++;
        }

        public void finalizeCallAt(
                int index,
                String functionId,
                String argsDigest,
                byte[] resultPayload,
                byte[] exceptionPayload) {
            CallResult current = getCallResultAt(index);
            if (current == null) {
                throw new IllegalStateException(
                        String.format(
                                "Cannot finalize call at index %s because no persisted slot exists",
                                index));
            }
            if (!current.matches(functionId, argsDigest)) {
                throw new IllegalStateException(
                        String.format(
                                "Cannot finalize call at index %s because the persisted slot does not match functionId=%s, argsDigest=%s",
                                index, functionId, argsDigest));
            }
            if (!current.isPending()) {
                throw new IllegalStateException(
                        String.format(
                                "Cannot finalize call at index %s because the persisted slot is not pending",
                                index));
            }

            CallResult terminal =
                    new CallResult(functionId, argsDigest, resultPayload, exceptionPayload);
            actionState.replaceCallResult(index, terminal);
            recoveryCallResults.set(index, terminal);
            persistActionState();

            LOG.debug(
                    "Finalized and persisted CallResult at index {}: functionId={}, argsDigest={}",
                    index,
                    functionId,
                    argsDigest);
        }

        public void advanceCallIndexBy(int count) {
            currentCallIndex += count;
        }

        /**
         * Clears persisted call results from the current index onward and persists the truncated
         * state immediately.
         */
        public void clearCallResultsFromCurrentIndexAndPersist() {
            clearCallResultsFromCurrentIndex();
            persistActionState();
        }

        public void clearCallResultsFromAndPersist(int index) {
            clearCallResultsFrom(index);
            persistActionState();
        }

        public void clearCallResultsFrom(int index) {
            actionState.clearCallResultsFrom(index);
            recoveryCallResults =
                    new ArrayList<>(
                            recoveryCallResults.subList(
                                    0, Math.min(index, recoveryCallResults.size())));
        }

        private void clearCallResultsFromCurrentIndex() {
            clearCallResultsFrom(currentCallIndex);
        }

        private void persistActionState() {
            persister.persist(key, sequenceNumber, action, event, actionState);
        }
    }
}
