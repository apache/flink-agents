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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import pemja.core.object.PyObject;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
        ltm = new Mem0LongTermMemory(mockAdapter, mockPyMem0);
        when(mockAdapter.invoke(eq("python_java_utils.to_python_memory_set"), any()))
                .thenReturn(mockPyMemorySet);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
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
        verify(mockAdapter)
                .callMethod(
                        eq(mockPyMem0),
                        eq("add"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs)
                                            .containsKeys(
                                                    "memory_set", "memory_items", "metadatas");
                                    assertThat(kwargs.get("memory_set")).isEqualTo(mockPyMemorySet);
                                    return true;
                                }));
    }

    @Test
    void testGetOmitsNullOptionalKwargs() throws Exception {
        MemorySet ms = ltm.getMemorySet("notes");
        when(mockAdapter.callMethod(eq(mockPyMem0), eq("get"), any())).thenReturn(null);
        when(mockAdapter.invoke(eq("python_java_utils.mem0_items_to_java"), any()))
                .thenReturn(null);

        ltm.get(ms, null, null, null);

        verify(mockAdapter)
                .callMethod(
                        eq(mockPyMem0),
                        eq("get"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsOnlyKeys("memory_set");
                                    return true;
                                }));
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

        verify(mockAdapter)
                .callMethod(
                        eq(mockPyMem0),
                        eq("get"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKeys("ids", "filters", "limit");
                                    assertThat(kwargs.get("limit")).isEqualTo(50);
                                    return true;
                                }));
    }

    @Test
    void testDeleteForwardsIds() throws Exception {
        MemorySet ms = ltm.getMemorySet("notes");

        ltm.delete(ms, List.of("id1", "id2"));

        verify(mockAdapter)
                .callMethod(
                        eq(mockPyMem0),
                        eq("delete"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKeys("memory_set", "ids");
                                    return true;
                                }));
    }

    @Test
    void testDeleteWithoutIdsOmitsKwarg() throws Exception {
        MemorySet ms = ltm.getMemorySet("notes");

        ltm.delete(ms, null);

        verify(mockAdapter)
                .callMethod(
                        eq(mockPyMem0),
                        eq("delete"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsOnlyKeys("memory_set");
                                    return true;
                                }));
    }

    @Test
    void testSearchForwardsKwargs() throws Exception {
        MemorySet ms = ltm.getMemorySet("notes");
        when(mockAdapter.callMethod(eq(mockPyMem0), eq("search"), any())).thenReturn(null);
        when(mockAdapter.invoke(eq("python_java_utils.mem0_items_to_java"), any()))
                .thenReturn(null);

        ltm.search(ms, "hi", 10, Map.of("user_id", "u1"), Map.of("threshold", 0.7));

        verify(mockAdapter)
                .callMethod(
                        eq(mockPyMem0),
                        eq("search"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs)
                                            .containsKeys(
                                                    "memory_set",
                                                    "query",
                                                    "limit",
                                                    "filters",
                                                    "threshold");
                                    assertThat(kwargs.get("query")).isEqualTo("hi");
                                    assertThat(kwargs.get("limit")).isEqualTo(10);
                                    assertThat(kwargs.get("threshold")).isEqualTo(0.7);
                                    return true;
                                }));
    }

    @Test
    void testSwitchContextAndCloseForward() {
        ltm.switchContext("k1");
        ltm.close();

        verify(mockAdapter)
                .callMethod(eq(mockPyMem0), eq("switch_context"), eq(Map.of("key", "k1")));
        verify(mockAdapter).callMethod(eq(mockPyMem0), eq("close"), eq(Map.of()));
    }
}
