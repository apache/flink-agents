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

package org.apache.flink.agents.runtime.utils;

/**
 * The executing action's source of {@link DeterministicIdAllocator}s, one per call target.
 *
 * <p>Installed on the runner context by the operator on every task switch, restore and transfer, so
 * a caller reads the executing action's counters off the context it was handed. The mapping from an
 * execution to its allocators is owned by the installer and outlives a single task, so a
 * suspend/resume transfer hands the successor the same counters instead of restarting them.
 */
public interface IdAllocatorSource {

    /** The counter assigning ids for one target of the executing action, created on first use. */
    DeterministicIdAllocator allocator(String targetName);
}
