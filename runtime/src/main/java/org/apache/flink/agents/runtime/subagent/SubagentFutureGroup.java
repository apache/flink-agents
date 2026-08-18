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
