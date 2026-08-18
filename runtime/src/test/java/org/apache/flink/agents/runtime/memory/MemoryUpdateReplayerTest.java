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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link MemoryUpdateReplayer}: durable-execution replay of the {@link MemoryUpdate}s
 * recorded by a completed action must reproduce the action's memory effects, in particular for
 * updates recorded by {@code newObject}, which are not value writes.
 */
public class MemoryUpdateReplayerTest {

    private static MemoryObject freshMemory(List<MemoryUpdate> updates) throws Exception {
        return new MemoryObjectImpl(
                MemoryObject.MemoryType.SHORT_TERM,
                new CachedMemoryStore(new ForTestMemoryMapState<>()),
                MemoryObjectImpl.ROOT_KEY,
                updates);
    }

    /** Runs an "action" against a fresh memory and returns the updates it recorded. */
    private static List<MemoryUpdate> recordUpdates(ThrowingConsumer<MemoryObject> action)
            throws Exception {
        List<MemoryUpdate> updates = new LinkedList<>();
        MemoryObject memory = freshMemory(updates);
        action.accept(memory);
        return updates;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }

    @Test
    void testReplayNewObjectWithChildWritesIntoEmptyState() throws Exception {
        // An action creates a nested object and writes children into it.
        List<MemoryUpdate> updates =
                recordUpdates(
                        memory -> {
                            memory.newObject("user");
                            memory.set("user.name", "alice");
                            memory.set("user.age", 30);
                        });

        // Recovery from a checkpoint taken before the action ran: replay into empty state.
        MemoryObject restored = freshMemory(new LinkedList<>());
        MemoryUpdateReplayer.replay(restored, updates);

        assertThat(restored.get("user").isNestedObject()).isTrue();
        assertThat(restored.get("user").getFieldNames()).containsExactlyInAnyOrder("name", "age");
        assertThat(restored.get("user.name").getValue()).isEqualTo("alice");
        assertThat(restored.get("user.age").getValue()).isEqualTo(30);
    }

    @Test
    void testReplayNewObjectOverExistingObjectFromRestoredCheckpoint() throws Exception {
        List<MemoryUpdate> updates =
                recordUpdates(
                        memory -> {
                            memory.newObject("user");
                            memory.set("user.name", "alice");
                        });

        // Recovery from a checkpoint that already contains the object (e.g. the action ran and
        // its writes were checkpointed before the input was re-delivered). Before the
        // objectCreation discriminator existed, this replayed as set("user", null) and threw
        // "Cannot overwrite object with value", crash-looping recovery.
        MemoryObject restored = freshMemory(new LinkedList<>());
        restored.newObject("user");
        restored.set("user.name", "alice");

        assertThatCode(() -> MemoryUpdateReplayer.replay(restored, updates))
                .doesNotThrowAnyException();
        assertThat(restored.get("user").isNestedObject()).isTrue();
        assertThat(restored.get("user.name").getValue()).isEqualTo("alice");
    }

    @Test
    void testReplayNewObjectOverwritingValueLeaf() throws Exception {
        // newObject(path, overwrite=true) legally replaces a value leaf with an object; replay
        // must reproduce that, not fail or reintroduce the value.
        List<MemoryUpdate> updates =
                recordUpdates(
                        memory -> {
                            memory.set("slot", 1);
                            memory.newObject("slot", true);
                            memory.set("slot.child", 2);
                        });

        MemoryObject restored = freshMemory(new LinkedList<>());
        MemoryUpdateReplayer.replay(restored, updates);

        assertThat(restored.get("slot").isNestedObject()).isTrue();
        assertThat(restored.get("slot.child").getValue()).isEqualTo(2);
    }

    @Test
    void testReplayPreservesUserNullValueWrite() throws Exception {
        // A user's set(path, null) is a value write, not an object creation; replay must keep it
        // a value leaf.
        List<MemoryUpdate> updates = recordUpdates(memory -> memory.set("maybe", null));

        MemoryObject restored = freshMemory(new LinkedList<>());
        MemoryUpdateReplayer.replay(restored, updates);

        assertThat(restored.get("maybe").isNestedObject()).isFalse();
        assertThat(restored.get("maybe").getValue()).isNull();
    }
}
