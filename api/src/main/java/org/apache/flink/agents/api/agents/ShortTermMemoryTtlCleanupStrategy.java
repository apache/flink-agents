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

package org.apache.flink.agents.api.agents;

/** Cleanup strategy for expired short-term memory TTL state, consulted only when TTL is enabled. */
public enum ShortTermMemoryTtlCleanupStrategy {
    /** Reclaim during full checkpoint snapshots (default; preserves prior behavior). */
    FULL_SNAPSHOT,
    /** Reclaim incrementally on state access, bounding per-access overhead. */
    INCREMENTAL,
    /**
     * Reclaim during RocksDB background compaction. Only effective with the RocksDB state backend;
     * a silent no-op on other backends.
     */
    ROCKSDB_COMPACTION_FILTER,
    /** Reclaim only on read access; disables background cleanup entirely. */
    LAZY
}
