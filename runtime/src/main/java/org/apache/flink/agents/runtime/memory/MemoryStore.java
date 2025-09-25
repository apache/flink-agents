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

import org.apache.flink.agents.runtime.memory.MemoryObjectImpl.MemoryItem;

/** MemoryStore to put and get MemoryItems. */
public interface MemoryStore {

    /**
     * Get a MemoryItem by key.
     *
     * @param key the key of the MemoryItem
     * @return the MemoryItem
     */
    MemoryItem get(String key) throws Exception;

    /**
     * Put a MemoryItem by key.
     *
     * @param key the key of the MemoryItem
     * @param value the MemoryItem
     */
    void put(String key, MemoryItem value) throws Exception;

    /**
     * Check if a MemoryItem exists by key.
     *
     * @param key the key of the MemoryItem
     * @return true if the MemoryItem exists, false otherwise
     */
    boolean contains(String key) throws Exception;
}
