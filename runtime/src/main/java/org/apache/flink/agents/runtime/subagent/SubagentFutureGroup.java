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

import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentFutures;
import org.apache.flink.agents.api.subagent.SubagentResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        checkNotMixedInternalAndDeferred();
        for (SubagentFuture future : futures) {
            if (future instanceof PhasedSubagentFuture && !future.isDone()) {
                ((PhasedSubagentFuture) future).prepare();
            }
        }
        // TODO(#926): execute the prepared calls as one batch once durable execution supports
        // batched submission; until then the prepared calls are executed one by one.
        for (SubagentFuture future : futures) {
            if (future instanceof PhasedSubagentFuture && !future.isDone()) {
                ((PhasedSubagentFuture) future).execute();
            }
        }
        List<SubagentResult> outcomes = new ArrayList<>(futures.size());
        for (SubagentFuture future : futures) {
            outcomes.add(future.await());
        }
        return outcomes;
    }

    /**
     * Refuses a group mixing internal and deferred handles: the two consult this action's durable
     * journal in different phases, so mixing them currently breaks durable recovery.
     */
    private void checkNotMixedInternalAndDeferred() {
        SubagentFuture internal = null;
        SubagentFuture deferred = null;
        for (SubagentFuture future : futures) {
            // A resolved member is skipped by both loops above, so it consults no journal.
            if (future instanceof InternalSubagentFuture) {
                if (internal == null && !future.isDone()) {
                    internal = future;
                }
            } else if (future instanceof DeferredSubagentFuture) {
                if (deferred == null && !future.isDone()) {
                    deferred = future;
                }
            }
        }
        if (internal != null && deferred != null) {
            throw new IllegalStateException(
                    String.format(
                            "A combined sub-agent group cannot mix the internal call %s with the"
                                    + " deferred call %s: the two consult this action's durable"
                                    + " journal in different phases, so recovery cannot replay the"
                                    + " reads in the written order. Resolve the two calls"
                                    + " separately.",
                            describe(internal), describe(deferred)));
        }
    }

    private static String describe(SubagentFuture future) {
        return future.getClass().getSimpleName()
                + " "
                + future.getSessionId()
                + "#"
                + future.getCallId();
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
