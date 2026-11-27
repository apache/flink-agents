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

import org.apache.flink.agents.api.configuration.ConfigOption;

import java.time.Duration;

/** Job-level defaults for per-chat loop budgets. */
public final class ReActLoopBudgetOptions {
    private ReActLoopBudgetOptions() {}

    public static final ConfigOption<Integer> LOOP_MAX_TURNS =
            new ConfigOption<>("react-loop.budget.max-turns", Integer.class, null);
    public static final ConfigOption<Long> LOOP_MAX_TOKENS =
            new ConfigOption<>("react-loop.budget.max-tokens", Long.class, null);
    public static final ConfigOption<Duration> LOOP_MAX_TIME =
            new ConfigOption<>("react-loop.budget.max-time", Duration.class, null);
    public static final ConfigOption<ReActLoopBudget.BudgetMode> LOOP_BUDGET_MODE =
            new ConfigOption<>(
                    "react-loop.budget.mode",
                    ReActLoopBudget.BudgetMode.class,
                    ReActLoopBudget.BudgetMode.SOFT);
}
