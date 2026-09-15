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
package org.apache.flink.agents.plan.routing;

import org.apache.flink.agents.api.chat.model.routing.CustomRoutingExecutor;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

class CustomRoutingExecutorAdapterTest {

    /** A constructor that normalizes its arguments in place — a common, legitimate shape. */
    public static class NormalizingExecutor implements CustomRoutingExecutor {
        public NormalizingExecutor(Map<String, Object> args) {
            args.putIfAbsent("threshold", 0.5);
            args.remove("unused");
        }

        @Override
        public RoutingDecision route(RoutingStrategy strategy, RoutingContext context) {
            return RoutingDecision.abstain();
        }
    }

    @Test
    void constructorMayMutateItsArgumentMap() {
        RoutingStrategy strategy =
                Strategies.custom(NormalizingExecutor.class, new HashMap<>(Map.of("unused", 1)));
        CustomRoutingExecutorAdapter adapter = new CustomRoutingExecutorAdapter();
        assertThatCode(() -> adapter.prepare(strategy, null)).doesNotThrowAnyException();
        // The declaration itself stays untouched.
        org.junit.jupiter.api.Assertions.assertEquals(Map.of("unused", 1), strategy.getArguments());
    }
}
