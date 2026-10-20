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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;

import javax.annotation.Nullable;

/**
 * A {@link SubgraphDefinition} for tests that drive an invocation's routing, action accounting and
 * completion directly, and so never reach the issue path.
 *
 * <p>The three issue-path members fail loudly rather than returning a plausible default: a test
 * that starts reaching them is exercising something other than what it set out to, and a silent
 * {@code null} would hide that.
 */
public abstract class RoutingOnlyDefinition<R> implements SubgraphDefinition<R> {

    private static final String UNUSED =
            "This test drives routing directly and must not reach the issue path.";

    /**
     * A real value rather than a throw: {@link SubgraphInvocation} reads this while it is being
     * constructed, to name its durable slot, so even a routing-only test needs one.
     */
    @Override
    public final String kind() {
        return "routing-only";
    }

    @Override
    public final Class<R> resultClass() {
        throw new UnsupportedOperationException(UNUSED);
    }

    @Override
    public final Event inputEvent(Object input) {
        throw new UnsupportedOperationException(UNUSED);
    }

    @Override
    public final SubgraphInvocation<R> newInvocation(
            RunnerContextImpl context, String id, @Nullable Object key) {
        throw new UnsupportedOperationException(UNUSED);
    }
}
