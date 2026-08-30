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
package org.apache.flink.agents.runtime.memory;

import org.apache.flink.agents.api.memory.MemorySet;
import org.apache.flink.agents.api.memory.MemorySetItem;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import pemja.core.object.PyObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class Mem0LongTermMemoryTest {
    @Mock private PythonResourceAdapter mockAdapter;
    @Mock private PyObject mockPyMem0;
    @Mock private PyObject mockPyMemorySet;

    private Mem0LongTermMemory ltm;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        ltm = new Mem0LongTermMemory(mockAdapter, mockPyMem0, () -> {});
        // Operations refuse an absent or empty key, so every test that does not manage its
        // own context runs under the key an action would have switched to.
        ltm.switchContext("a-key", "an-action", false);
        when(mockAdapter.invoke(
                        eq("python_java_utils.to_python_memory_set"), any(), any(), any(), any()))
                .thenReturn(mockPyMemorySet);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> captureKwargs(String methodName) {
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockAdapter).callMethod(eq(mockPyMem0), eq(methodName), captor.capture());
        return captor.getValue();
    }

    @Test
    void testGetMemorySetIsPureFactoryAndBindsLtm() throws Exception {
        MemorySet ms = ltm.getMemorySet("notes");

        assertThat(ms.getName()).isEqualTo("notes");
        // Adding through the proxy should reach our ltm instance.
        when(mockAdapter.callMethod(eq(mockPyMem0), eq("add"), any())).thenReturn(List.of("id1"));
        ms.add(List.of("hello"), null);
        verify(mockAdapter).callMethod(eq(mockPyMem0), eq("add"), any());
        // get_memory_set itself is a pure factory; it should NOT round-trip to Python.
        verify(mockAdapter, org.mockito.Mockito.never())
                .callMethod(eq(mockPyMem0), eq("get_memory_set"), any());
    }

    @Test
    void testDeleteMemorySetForwardsAndReturnsBoolean() throws Exception {
        when(mockAdapter.callMethod(eq(mockPyMem0), eq("delete_memory_set"), any()))
                .thenReturn(Boolean.TRUE);

        boolean deleted = ltm.deleteMemorySet("notes");

        assertThat(deleted).isTrue();
        verify(mockAdapter)
                .callMethod(eq(mockPyMem0), eq("delete_memory_set"), eq(Map.of("name", "notes")));
    }

    @Test
    void testAddForwardsKwargsAndReturnsIds() throws Exception {
        MemorySet ms = ltm.getMemorySet("notes");
        when(mockAdapter.callMethod(eq(mockPyMem0), eq("add"), any()))
                .thenReturn(List.of("a", "b"));

        List<String> ids =
                ltm.add(ms, List.of("hello", "world"), List.of(Map.of("k", "v"), Map.of()));

        assertThat(ids).containsExactly("a", "b");
        assertThat(captureKwargs("add"))
                .containsKeys("memory_set", "memory_items", "metadatas")
                .containsEntry("memory_set", mockPyMemorySet);
    }

    @Test
    void testGetOmitsNullOptionalKwargs() throws Exception {
        MemorySet ms = ltm.getMemorySet("notes");
        when(mockAdapter.callMethod(eq(mockPyMem0), eq("get"), any())).thenReturn(null);
        when(mockAdapter.invoke(eq("python_java_utils.mem0_items_to_java"), any()))
                .thenReturn(null);

        ltm.get(ms, null, null, null);

        assertThat(captureKwargs("get")).containsOnlyKeys("memory_set");
    }

    @Test
    void testGetWithIdsAndFiltersConvertsItems() throws Exception {
        MemorySet ms = ltm.getMemorySet("notes");
        when(mockAdapter.callMethod(eq(mockPyMem0), eq("get"), any())).thenReturn("py_items");
        when(mockAdapter.invoke(eq("python_java_utils.mem0_items_to_java"), eq("py_items")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "memory_set_name", "notes",
                                        "id", "id1",
                                        "value", "hello",
                                        "additional_metadata", Map.of("k", "v"))));

        List<MemorySetItem> items = ltm.get(ms, List.of("id1"), Map.of("user_id", "u1"), 50);

        assertThat(items).hasSize(1);
        MemorySetItem item = items.get(0);
        assertThat(item.getMemorySetName()).isEqualTo("notes");
        assertThat(item.getId()).isEqualTo("id1");
        assertThat(item.getValue()).isEqualTo("hello");
        assertThat(item.getAdditionalMetadata()).containsEntry("k", "v");
        assertThat(item.getCreatedAt()).isNull();

        assertThat(captureKwargs("get"))
                .containsKeys("ids", "filters", "limit")
                .containsEntry("limit", 50);
    }

    @Test
    void testDeleteForwardsIds() throws Exception {
        MemorySet ms = ltm.getMemorySet("notes");

        ltm.delete(ms, List.of("id1", "id2"));

        assertThat(captureKwargs("delete")).containsKeys("memory_set", "ids");
    }

    @Test
    void testDeleteWithoutIdsOmitsKwarg() throws Exception {
        MemorySet ms = ltm.getMemorySet("notes");

        ltm.delete(ms, null);

        assertThat(captureKwargs("delete")).containsOnlyKeys("memory_set");
    }

    @Test
    void testSearchForwardsKwargs() throws Exception {
        MemorySet ms = ltm.getMemorySet("notes");
        when(mockAdapter.callMethod(eq(mockPyMem0), eq("search"), any())).thenReturn(null);
        when(mockAdapter.invoke(eq("python_java_utils.mem0_items_to_java"), any()))
                .thenReturn(null);

        ltm.search(ms, "hi", 10, Map.of("user_id", "u1"), Map.of("threshold", 0.7));

        assertThat(captureKwargs("search"))
                .containsKeys("memory_set", "query", "limit", "filters", "threshold")
                .containsEntry("query", "hi")
                .containsEntry("limit", 10)
                .containsEntry("threshold", 0.7);
    }

    @Test
    void testSwitchContextAndCloseForward() throws Exception {
        ltm.configureObservation(true, false, true);
        ltm.switchContext("k1", "observation-1", true);
        ltm.drainObservationRecordsJson("k1", "observation-1");
        ltm.close();
        ltm.close();

        verify(mockAdapter)
                .callMethod(
                        eq(mockPyMem0),
                        eq("configure_observation"),
                        eq(
                                Map.of(
                                        "update_observation_enabled",
                                        true,
                                        "get_observation_enabled",
                                        false,
                                        "search_observation_enabled",
                                        true)));
        verify(mockAdapter)
                .callMethod(
                        eq(mockPyMem0),
                        eq("switch_context"),
                        eq(
                                Map.of(
                                        "key",
                                        "k1",
                                        "observation_id",
                                        "observation-1",
                                        "observation_suppressed",
                                        true)));
        verify(mockAdapter)
                .callMethod(
                        eq(mockPyMem0),
                        eq("drain_ltm_observation_records"),
                        eq(Map.of("key", "k1", "observation_id", "observation-1")));
        verify(mockAdapter).callMethod(eq(mockPyMem0), eq("close"), eq(Map.of()));
        verify(mockPyMem0).close();
    }

    @Test
    void testCloseReleasesPythonObjectWhenLogicalCleanupFails() throws Exception {
        RuntimeException failure = new RuntimeException("logical close failed");
        doThrow(failure).when(mockAdapter).callMethod(mockPyMem0, "close", Map.of());

        assertThatThrownBy(ltm::close).isSameAs(failure);
        ltm.close();

        verify(mockAdapter).callMethod(mockPyMem0, "close", Map.of());
        verify(mockPyMem0).close();
    }

    @Test
    void testUnboundSetIsRefusedRatherThanWidened() {
        MemorySet unbound = new MemorySet("notes");
        unbound.setLtm(ltm);

        assertThatThrownBy(() -> ltm.add(unbound, List.of("hello"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not bound to a partition key");
        verify(mockAdapter, never())
                .invoke(eq("python_java_utils.to_python_memory_set"), any(), any(), any(), any());
    }

    @Test
    void testEmptyKeyedSetIsRefusedRatherThanWidened() {
        MemorySet emptyKeyed = new MemorySet("notes");
        emptyKeyed.setLtm(ltm);
        emptyKeyed.setActionContext("", "an-action", false);

        assertThatThrownBy(() -> ltm.add(emptyKeyed, List.of("hello"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty partition key");
        verify(mockAdapter, never())
                .invoke(eq("python_java_utils.to_python_memory_set"), any(), any(), any(), any());
    }

    @Test
    void testMemorySetManagementIsRefusedForAnEmptyKey() {
        ltm.switchContext("", "an-action", false);

        assertThatThrownBy(() -> ltm.getMemorySet("notes"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty partition key");
        assertThatThrownBy(() -> ltm.deleteMemorySet("notes"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty partition key");
        verify(mockAdapter, never()).callMethod(eq(mockPyMem0), eq("delete_memory_set"), any());
    }

    @Test
    void testMemorySetIsRefusedBeforeAnyContextSwitch() {
        Mem0LongTermMemory fresh = new Mem0LongTermMemory(mockAdapter, mockPyMem0, () -> {});

        assertThatThrownBy(() -> fresh.getMemorySet("notes"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no partition key in scope");
    }

    @Test
    void testForwardedSetCarriesTheContextItWasObtainedIn() throws Exception {
        ltm.switchContext("owner", "owner-action", false);
        MemorySet ms = ltm.getMemorySet("notes");

        ltm.switchContext("other", "other-action", true);
        ltm.add(ms, List.of("hello"), null);

        verify(mockAdapter)
                .invoke(
                        eq("python_java_utils.to_python_memory_set"),
                        eq("notes"),
                        eq("owner"),
                        eq("owner-action"),
                        eq(false));
    }

    @Test
    void testMemorySetManagementIsRefusedOffTheMailboxThread() {
        Mem0LongTermMemory guarded =
                new Mem0LongTermMemory(
                        mockAdapter,
                        mockPyMem0,
                        () -> {
                            throw new IllegalStateException(
                                    "Expected to be running on the task mailbox thread, but was"
                                            + " not.");
                        });
        // No context switch: with no key in scope, only a checker that runs before the key
        // is read can produce the mailbox-thread message. That pins the intended order,
        // because off the mailbox thread the key read is itself unreliable.

        assertThatThrownBy(() -> guarded.getMemorySet("notes"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task mailbox thread");
        assertThatThrownBy(() -> guarded.deleteMemorySet("notes"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task mailbox thread");
        verify(mockAdapter, never()).callMethod(eq(mockPyMem0), eq("delete_memory_set"), any());
    }

    @Test
    void testSetScopedOperationsRunWithoutTheMailboxThread() {
        // A set carries the context it was obtained under, so operations on it are safe to
        // forward to a worker thread. Gating them would break durable async execution.
        AtomicInteger checkerCalls = new AtomicInteger();
        Mem0LongTermMemory counting =
                new Mem0LongTermMemory(mockAdapter, mockPyMem0, checkerCalls::incrementAndGet);
        counting.switchContext("a-key", "an-action", false);
        MemorySet ms = counting.getMemorySet("notes");
        // Guards the assertion below from passing vacuously on a checker that never runs.
        assertThat(checkerCalls.get()).isOne();
        checkerCalls.set(0);

        counting.add(ms, List.of("hello"), null);
        counting.get(ms, null, null, null);
        counting.delete(ms, null);
        counting.search(ms, "query", 5, null, Map.of());

        assertThat(checkerCalls.get()).isZero();
    }
}
