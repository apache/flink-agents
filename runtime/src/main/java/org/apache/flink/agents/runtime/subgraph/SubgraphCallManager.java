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
package org.apache.flink.agents.runtime.subgraph;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Operator-owned invocation registry, confined to the mailbox. Both chat and internal subagents
 * register here; adapters may retain references for their public handles but own no scheduler.
 */
public final class SubgraphCallManager {
    private final Map<String, SubgraphInvocation<?>> calls = new HashMap<>();

    public void register(SubgraphInvocation<?> invocation) {
        if (calls.putIfAbsent(invocation.getId(), invocation) != null) {
            throw new IllegalStateException("Duplicate subgraph call: " + invocation.getId());
        }
    }

    public SubgraphInvocation<?> get(String id) {
        return Objects.requireNonNull(calls.get(id), "Missing subgraph call: " + id);
    }

    @Nullable
    public SubgraphInvocation<?> find(String id) {
        return calls.get(id);
    }

    public void remove(String id) {
        calls.remove(id);
    }

    /** A record cannot finish while one of its actions is still awaiting a child. */
    public void finishRecord(Object key) {
        calls.values()
                .removeIf(
                        call -> {
                            if (!Objects.equals(key, call.getKey())) {
                                return false;
                            }
                            if (!call.isDone()) {
                                call.cancel();
                            }
                            return true;
                        });
    }

    public void clear() {
        calls.values().forEach(SubgraphInvocation::cancel);
        calls.clear();
    }
}
