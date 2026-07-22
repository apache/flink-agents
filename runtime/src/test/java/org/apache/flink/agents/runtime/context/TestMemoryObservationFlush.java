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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.event.LongTermUpdateEvent;
import org.apache.flink.agents.api.event.ShortTermWriteEvent;
import org.apache.flink.agents.api.memory.MemorySet;
import org.apache.flink.agents.api.memory.MemorySetItem;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.memory.CachedMemoryStore;
import org.apache.flink.agents.runtime.memory.ForTestMemoryMapState;
import org.apache.flink.agents.runtime.memory.InteranlBaseLongTermMemory;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Action-finish observation semantics at the real RunnerContext boundary. */
class TestMemoryObservationFlush {
    private RunnerContextImpl createContext(Map<String, Object> conf, boolean suppressed)
            throws Exception {
        return createContext(conf, suppressed, "user-42");
    }

    private RunnerContextImpl createContext(
            Map<String, Object> conf, boolean suppressed, @Nullable String eventKeyText)
            throws Exception {
        return createContext(conf, suppressed, eventKeyText, null);
    }

    private RunnerContextImpl createContext(
            Map<String, Object> conf,
            boolean suppressed,
            @Nullable String eventKeyText,
            @Nullable InteranlBaseLongTermMemory ltm)
            throws Exception {
        AgentPlan plan =
                new AgentPlan(
                        new HashMap<>(),
                        new HashMap<>(),
                        new HashMap<>(),
                        new org.apache.flink.agents.plan.AgentConfiguration(conf));
        RunnerContextImpl context =
                new RunnerContextImpl(
                        new FlinkAgentsMetricGroupImpl(
                                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup()),
                        () -> {},
                        plan,
                        null,
                        "job");
        if (ltm != null) {
            context.setLongTermMemory(ltm);
        }
        context.switchActionContext(
                "action",
                new RunnerContextImpl.MemoryContext(
                        new CachedMemoryStore(new ForTestMemoryMapState<>()),
                        new CachedMemoryStore(new ForTestMemoryMapState<>())),
                "legacy-partition-hash",
                eventKeyText,
                suppressed);
        return context;
    }

    @Test
    void finishReusesPersistenceWritesAndRecordsReadsFromGetFields() throws Exception {
        Map<String, Object> conf = new HashMap<>();
        conf.put("memory.generate-event.short-term-read", true);
        RunnerContextImpl context = createContext(conf, false);
        context.getShortTermMemory().newObject("empty", false);
        context.getShortTermMemory().set("user.tier", "gold");
        context.getShortTermMemory().get("user").getFields();

        List<Event> events = context.drainEventsAtActionFinish(null);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getType()).isEqualTo(ShortTermWriteEvent.EVENT_TYPE);
        assertThat(((ShortTermWriteEvent) events.get(0)).getValue())
                .containsEntry("empty", null)
                .containsEntry("user.tier", "gold");
        assertThat(events.get(1).getAttr("value").toString()).contains("user.tier=gold");
        assertReadObservationListsEmpty(context.getMemoryContext());
    }

    @Test
    void invalidKryoOnlyOrNonFiniteValuesNeverFailActionFinish() throws Exception {
        RunnerContextImpl context = createContext(new HashMap<>(), false);
        context.getShortTermMemory().set("valid", "kept");
        context.getShortTermMemory().set("nan", Double.NaN);
        context.getShortTermMemory().set("pojo", new KryoOnlyValue());

        assertThatCode(() -> context.drainEventsAtActionFinish(null)).doesNotThrowAnyException();
        assertThat(context.getShortTermMemoryUpdates()).hasSize(3);
    }

    @Test
    void unfinishedDrainDefersObservationsUntilFinish() throws Exception {
        RunnerContextImpl context = createContext(new HashMap<>(), false);
        context.getShortTermMemory().set("first", 1);

        assertThat(context.drainEvents(null)).isEmpty();
        context.getShortTermMemory().set("second", 2);

        List<Event> finished = context.drainEventsAtActionFinish(null);
        assertThat(finished).singleElement();
        assertThat(((ShortTermWriteEvent) finished.get(0)).getValue())
                .containsEntry("first", 1)
                .containsEntry("second", 2);
        assertThat(context.getShortTermMemoryUpdates()).hasSize(2);
    }

    @Test
    void userAndMemoryEventsCoexistAtFinish() throws Exception {
        RunnerContextImpl context = createContext(new HashMap<>(), false);
        context.sendEvent(new Event("user-event", Map.of("answer", 42)));
        context.getShortTermMemory().set("memory", "value");

        assertThat(context.drainEventsAtActionFinish(null))
                .extracting(Event::getType)
                .containsExactly("user-event", ShortTermWriteEvent.EVENT_TYPE);
    }

    @Test
    void disabledLtmOperationsDoNotCrossBridgeAtFinishOrDiscard() throws Exception {
        StubLtm ltm = new StubLtm();
        RunnerContextImpl context =
                createContext(Map.of("memory.generate-event", false), false, "user-42", ltm);

        assertThat(context.drainEventsAtActionFinish(null)).isEmpty();
        context.discardMemoryObservation();

        assertThat(ltm.drainCallCount).isZero();
        assertThat(ltm.updateEnabled).isFalse();
        assertThat(ltm.getEnabled).isFalse();
        assertThat(ltm.searchEnabled).isFalse();
    }

    @Test
    void individualLtmOperationFlagsAreForwardedExactly() throws Exception {
        Map<String, Object> conf = new HashMap<>();
        conf.put("memory.generate-event.long-term-update", false);
        conf.put("memory.generate-event.long-term-get", true);
        conf.put("memory.generate-event.long-term-search", false);
        StubLtm ltm = new StubLtm();
        RunnerContextImpl context = createContext(conf, false, "user-42", ltm);

        assertThat(context.drainEventsAtActionFinish(null)).isEmpty();

        assertThat(ltm.updateEnabled).isFalse();
        assertThat(ltm.getEnabled).isTrue();
        assertThat(ltm.searchEnabled).isFalse();
        assertThat(ltm.drainCallCount).isEqualTo(1);
    }

    @Test
    void suppressedOrNonStringKeyDoesNotCrossLtmBridge() throws Exception {
        StubLtm suppressedLtm = new StubLtm();
        RunnerContextImpl suppressed =
                createContext(new HashMap<>(), true, "user-42", suppressedLtm);
        suppressed.drainEventsAtActionFinish(null);
        suppressed.discardMemoryObservation();

        StubLtm nonStringLtm = new StubLtm();
        RunnerContextImpl nonStringKey = createContext(new HashMap<>(), false, null, nonStringLtm);
        nonStringKey.drainEventsAtActionFinish(null);
        nonStringKey.discardMemoryObservation();

        assertThat(suppressedLtm.drainCallCount).isZero();
        assertThat(nonStringLtm.drainCallCount).isZero();
    }

    @Test
    void nonStringKeyDoesNotRecordMemoryObservationsButStillPersistsUpdates() throws Exception {
        Map<String, Object> conf = new HashMap<>();
        conf.put("memory.generate-event.sensory-read", true);
        conf.put("memory.generate-event.short-term-read", true);
        RunnerContextImpl context = createContext(conf, false, null);

        context.getSensoryMemory().set("sensory", "value");
        context.getSensoryMemory().get("sensory").getValue();
        context.getShortTermMemory().set("short-term", "value");
        context.getShortTermMemory().get("short-term").getValue();

        assertReadObservationListsEmpty(context.getMemoryContext());
        assertThat(context.getSensoryMemoryUpdates()).hasSize(1);
        assertThat(context.getShortTermMemoryUpdates()).hasSize(1);
        assertThat(context.drainEventsAtActionFinish(null)).isEmpty();
    }

    @Test
    void validVersionedLtmRecordUsesStructuredSetMap() throws Exception {
        StubLtm ltm = new StubLtm();
        ltm.payload =
                "[{\"version\":1,\"op\":\"ADD\",\"set\":\"a.b\",\"id\":\"m.1\",\"value\":\"v\"}]";
        RunnerContextImpl context = createContext(new HashMap<>(), false, "user-42", ltm);

        LongTermUpdateEvent event =
                (LongTermUpdateEvent) context.drainEventsAtActionFinish(null).get(0);
        assertThat(event.getValue()).containsEntry("a.b", Map.of("m.1", "v"));
    }

    @Test
    void malformedPayloadAndLinkageErrorRemainBestEffort() throws Exception {
        StubLtm malformedLtm = new StubLtm();
        malformedLtm.payload = "not-json";
        RunnerContextImpl malformed =
                createContext(new HashMap<>(), false, "user-42", malformedLtm);
        assertThatCode(() -> malformed.drainEventsAtActionFinish(null)).doesNotThrowAnyException();

        StubLtm linkageLtm = new StubLtm();
        linkageLtm.drainLinkageError = new NoClassDefFoundError("missing-observation-codec");
        RunnerContextImpl linkage = createContext(new HashMap<>(), false, "user-42", linkageLtm);

        assertThatCode(() -> linkage.drainEventsAtActionFinish(null)).doesNotThrowAnyException();
        assertThat(linkageLtm.drainCallCount).isEqualTo(1);
    }

    private static void assertReadObservationListsEmpty(
            RunnerContextImpl.MemoryContext memoryContext) {
        assertThat(memoryContext.getSensoryMemoryReads()).isEmpty();
        assertThat(memoryContext.getShortTermMemoryReads()).isEmpty();
    }

    private static final class KryoOnlyValue {
        private final Object unsupported = new Object();
    }

    private static class StubLtm implements InteranlBaseLongTermMemory {
        private String payload = "[]";
        private boolean updateEnabled;
        private boolean getEnabled;
        private boolean searchEnabled;
        private int drainCallCount;
        private LinkageError drainLinkageError;

        @Override
        public void switchContext(
                String partitionKey,
                boolean updateObservationEnabled,
                boolean getObservationEnabled,
                boolean searchObservationEnabled) {
            this.updateEnabled = updateObservationEnabled;
            this.getEnabled = getObservationEnabled;
            this.searchEnabled = searchObservationEnabled;
        }

        @Override
        public String drainObservationRecordsJson(String partitionKey) {
            drainCallCount++;
            if (drainLinkageError != null) {
                throw drainLinkageError;
            }
            String result = payload;
            payload = "[]";
            return result;
        }

        @Override
        public MemorySet getMemorySet(String name) {
            return null;
        }

        @Override
        public boolean deleteMemorySet(String name) {
            return false;
        }

        @Override
        public List<String> add(
                MemorySet memorySet,
                List<String> memoryItems,
                @Nullable List<Map<String, Object>> metadatas) {
            return List.of();
        }

        @Override
        public List<MemorySetItem> get(
                MemorySet memorySet,
                @Nullable List<String> ids,
                @Nullable Map<String, Object> filters,
                @Nullable Integer limit) {
            return List.of();
        }

        @Override
        public void delete(MemorySet memorySet, @Nullable List<String> ids) {}

        @Override
        public List<MemorySetItem> search(
                MemorySet memorySet,
                String query,
                int limit,
                @Nullable Map<String, Object> filters,
                Map<String, Object> extraArgs) {
            return List.of();
        }

        @Override
        public void close() {}
    }
}
