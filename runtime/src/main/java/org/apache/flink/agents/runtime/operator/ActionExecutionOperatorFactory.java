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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.streaming.api.operators.AbstractStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;

/** Operator factory for {@link ActionExecutionOperator}. */
public class ActionExecutionOperatorFactory<IN, OUT> extends AbstractStreamOperatorFactory<OUT>
        implements OneInputStreamOperatorFactory<IN, OUT> {

    private final AgentPlan agentPlan;

    private final Boolean inputIsJava;

    private final ActionStateStore actionStateStore;

    public ActionExecutionOperatorFactory(AgentPlan agentPlan, Boolean inputIsJava) {
        this(agentPlan, inputIsJava, null);
    }

    @VisibleForTesting
    protected ActionExecutionOperatorFactory(
            AgentPlan agentPlan, Boolean inputIsJava, ActionStateStore actionStateStore) {
        this.agentPlan = agentPlan;
        this.inputIsJava = inputIsJava;
        this.actionStateStore = actionStateStore;
        this.chainingStrategy = ChainingStrategy.ALWAYS;
    }

    @Override
    public <T extends StreamOperator<OUT>> T createStreamOperator(
            StreamOperatorParameters<OUT> parameters) {
        ActionExecutionOperator<IN, OUT> op =
                new ActionExecutionOperator<>(
                        agentPlan,
                        inputIsJava,
                        parameters.getProcessingTimeService(),
                        parameters.getMailboxExecutor(),
                        actionStateStore);
        op.setup(
                parameters.getContainingTask(),
                parameters.getStreamConfig(),
                parameters.getOutput());
        return (T) op;
    }

    @Override
    public void setChainingStrategy(ChainingStrategy chainingStrategy) {}

    @Override
    public ChainingStrategy getChainingStrategy() {
        return ChainingStrategy.ALWAYS;
    }

    @Override
    public Class<? extends StreamOperator> getStreamOperatorClass(ClassLoader classLoader) {
        return ActionExecutionOperator.class;
    }
}
