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

import java.util.List;

/**
 * Re-applies the {@link MemoryUpdate}s recorded by a completed action to a memory object during
 * durable-execution replay.
 *
 * <p>An update recorded by {@code newObject} must be replayed via {@link
 * MemoryObject#newObject(String, boolean)}: replaying it via {@code set(path, null)} would either
 * throw (the path already holds an object restored from the checkpoint) or materialize the object
 * as a null value leaf, breaking every subsequent child write.
 */
public final class MemoryUpdateReplayer {

    private MemoryUpdateReplayer() {}

    /**
     * Applies the given updates to the memory object in recorded order.
     *
     * @param memory the root memory object to apply the updates to.
     * @param memoryUpdates the updates recorded by the completed action.
     */
    public static void replay(MemoryObject memory, List<MemoryUpdate> memoryUpdates)
            throws Exception {
        for (MemoryUpdate memoryUpdate : memoryUpdates) {
            if (memoryUpdate.isObjectCreation()) {
                // Overwrite unconditionally: the recorded update reflects the action's final,
                // successfully applied effect, so replay must converge to it even if the restored
                // checkpoint holds a value at this path.
                memory.newObject(memoryUpdate.getPath(), true);
            } else {
                memory.set(memoryUpdate.getPath(), memoryUpdate.getValue());
            }
        }
    }
}
