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

package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentFutures;
import org.apache.flink.agents.api.subagent.SubagentResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The {@link SubagentFutures} returned by {@code combine}: several handles held together. */
final class SubagentFutureGroup extends SubagentFutures {

    private final List<SubagentFuture> futures;

    SubagentFutureGroup(SubagentFuture first, SubagentFuture[] others) {
        this(withFirst(first, others));
    }

    private static List<SubagentFuture> withFirst(SubagentFuture first, SubagentFuture[] others) {
        List<SubagentFuture> all = new ArrayList<>(1 + others.length);
        all.add(first);
        all.addAll(Arrays.asList(others));
        return all;
    }

    private SubagentFutureGroup(List<SubagentFuture> futures) {
        this.futures = futures;
    }

    @Override
    public boolean isDone() {
        for (SubagentFuture future : futures) {
            if (!future.isDone()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<SubagentResult> awaitAll() throws Exception {
        RunnerContext context = null;
        Set<SubagentFuture> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<DeferredSubagentFuture> pending = new ArrayList<>();
        for (SubagentFuture future : futures) {
            Objects.requireNonNull(future, "Sub-agent handle must not be null");
            if (!seen.add(future)) {
                throw new IllegalArgumentException(
                        "Cannot combine the same sub-agent handle twice");
            }
            RunnerContext owner = null;
            if (future instanceof DeferredSubagentFuture) {
                DeferredSubagentFuture deferred = (DeferredSubagentFuture) future;
                deferred.checkNotCancelled();
                owner = deferred.getContext();
                if (!future.isDone()) {
                    pending.add(deferred);
                }
            } else if (future instanceof AsyncSubagentFuture) {
                AsyncSubagentFuture async = (AsyncSubagentFuture) future;
                async.checkNotCancelled();
                owner = async.getContext();
            }
            if (owner != null) {
                if (context != null && context != owner) {
                    throw new IllegalArgumentException(
                            "Sub-agent handles must belong to the same runner context");
                }
                context = owner;
            }
        }
        if (context != null) {
            int maximum = context.getConfig().get(AgentExecutionOptions.SUBAGENT_MAX_BATCH_SIZE);
            int parallelism = context.getConfig().get(AgentExecutionOptions.SUBAGENT_PARALLELISM);
            if (maximum <= 0 || parallelism <= 0) {
                throw new IllegalArgumentException(
                        "subagent.max-batch-size and subagent.parallelism must be positive");
            }
            if (futures.size() > maximum) {
                throw new IllegalArgumentException(
                        "Sub-agent batch size "
                                + futures.size()
                                + " exceeds subagent.max-batch-size "
                                + maximum);
            }
        }
        if (!pending.isEmpty()) {
            List<DurableFuture<SubagentResult>> calls = new ArrayList<>(pending.size());
            for (DeferredSubagentFuture future : pending) {
                calls.add(context.durableExecuteAsync(future.prepare()));
            }
            List<Outcome<SubagentResult>> results = context.gather(calls).await();
            Exception failure = null;
            for (int i = 0; i < results.size(); i++) {
                Outcome<SubagentResult> result = results.get(i);
                if (result.isSuccess()) {
                    pending.get(i).complete(result.getValue());
                } else if (failure == null) {
                    failure = result.getError();
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
        List<SubagentResult> outcomes = new ArrayList<>(futures.size());
        for (SubagentFuture future : futures) {
            outcomes.add(future.await());
        }
        return outcomes;
    }

    @Override
    public void cancel() {
        for (SubagentFuture future : futures) {
            future.cancel();
        }
    }

    @Override
    public SubagentFutures combine(SubagentFuture... others) {
        List<SubagentFuture> grown = new ArrayList<>(futures);
        grown.addAll(Arrays.asList(others));
        return new SubagentFutureGroup(grown);
    }
}
