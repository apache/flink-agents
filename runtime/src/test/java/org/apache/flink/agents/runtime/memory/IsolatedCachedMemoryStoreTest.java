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

import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link IsolatedCachedMemoryStore}: a child scope reads through to the parent but its
 * writes must not mutate the parent's view.
 */
public class IsolatedCachedMemoryStoreTest {

    private static final MemoryObject.MemoryType TYPE = MemoryObject.MemoryType.SHORT_TERM;

    private static MemoryObjectImpl newMemoryObject(MemoryStore store, List<MemoryUpdate> updates)
            throws Exception {
        return new MemoryObjectImpl(TYPE, store, MemoryObjectImpl.ROOT_KEY, updates);
    }

    /**
     * A child write adds a field to the child scope only. {@link MemoryObjectImpl#set} rebuilds the
     * resolved item through {@code MemoryItem.withSubKey} and publishes it to the child store's own
     * cache, so the parent's immutable item keeps its original field list and {@link
     * MemoryObjectImpl#getFields()} resolves every name the parent lists.
     */
    @Test
    void childFieldWriteDoesNotLeakIntoParentFieldList() throws Exception {
        ForTestMemoryMapState<MemoryObjectImpl.MemoryItem> mapState = new ForTestMemoryMapState<>();
        CachedMemoryStore parentStore = new CachedMemoryStore(mapState);
        MemoryObjectImpl parent = newMemoryObject(parentStore, new LinkedList<>());
        parent.set("p", 1);

        IsolatedCachedMemoryStore childStore = new IsolatedCachedMemoryStore(parentStore);
        MemoryObjectImpl child = newMemoryObject(childStore, new LinkedList<>());
        child.set("c", 2);

        // The child still reads its own write, and reads through to the parent's value.
        assertThat(child.get("c").getValue()).isEqualTo(2);
        assertThat(child.get("p").getValue()).isEqualTo(1);

        // The parent must neither see the child's field nor fail listing its own fields.
        Map<String, Object> parentFields = parent.getFields();
        assertThat(parentFields).containsEntry("p", 1).doesNotContainKey("c");
    }

    /**
     * A child write to a key the parent already holds as a nested object rebuilds that item into
     * the child's own cache, so the parent's immutable nested object keeps only its own field.
     */
    @Test
    void childNestedWriteDoesNotLeakIntoParentObject() throws Exception {
        ForTestMemoryMapState<MemoryObjectImpl.MemoryItem> mapState = new ForTestMemoryMapState<>();
        CachedMemoryStore parentStore = new CachedMemoryStore(mapState);
        MemoryObjectImpl parent = newMemoryObject(parentStore, new LinkedList<>());
        parent.newObject("obj", false).set("kept", 1);

        IsolatedCachedMemoryStore childStore = new IsolatedCachedMemoryStore(parentStore);
        MemoryObjectImpl child = newMemoryObject(childStore, new LinkedList<>());
        child.set("obj.added", 2);

        assertThat(child.get("obj.added").getValue()).isEqualTo(2);
        assertThat(child.get("obj.kept").getValue()).isEqualTo(1);

        // The parent's nested object keeps only its own field.
        assertThat(parent.get("obj").getFieldNames()).containsExactly("kept");
        assertThat(parent.get("obj").getFields())
                .containsEntry("kept", 1)
                .doesNotContainKey("added");
    }
}
