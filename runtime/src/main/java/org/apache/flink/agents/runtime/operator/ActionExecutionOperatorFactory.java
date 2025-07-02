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

import org.apache.flink.agents.plan.WorkflowPlan;
import org.apache.flink.agents.runtime.message.EventMessage;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.util.OutputTag;

/**
 * Operator factory for {@link ActionExecutionOperator}.
 *
 * <p>NOTE: This code is adapted from the <a
 * href="https://github.com/apache/flink-statefun">flink-statefun</a>.
 */
public class ActionExecutionOperatorFactory<K>
        implements OneInputStreamOperatorFactory<EventMessage<K>, EventMessage<K>> {

    /** The interval in milliseconds to process pending input events. */
    private static final long DEFAULT_PROCESS_PENDING_INPUT_EVENTS_INTERVAL_MS = 3000;

    private final OutputTag<EventMessage<K>> outputTag;

    private final WorkflowPlan workflowPlan;

    private final TypeInformation<EventMessage<K>> eventMessageTypeInfo;

    public ActionExecutionOperatorFactory(
            OutputTag<EventMessage<K>> outputTag,
            WorkflowPlan workflowPlan,
            TypeInformation<EventMessage<K>> eventMessageTypeInfo) {
        this.outputTag = outputTag;
        this.workflowPlan = workflowPlan;
        this.eventMessageTypeInfo = eventMessageTypeInfo;
    }

    public <T extends StreamOperator<EventMessage<K>>> T createStreamOperator(
            StreamOperatorParameters<EventMessage<K>> streamOperatorParameters) {
        ActionExecutionOperator<K> op =
                new ActionExecutionOperator<>(
                        outputTag,
                        streamOperatorParameters.getProcessingTimeService(),
                        workflowPlan,
                        eventMessageTypeInfo,
                        DEFAULT_PROCESS_PENDING_INPUT_EVENTS_INTERVAL_MS);
        op.setup(
                streamOperatorParameters.getContainingTask(),
                streamOperatorParameters.getStreamConfig(),
                streamOperatorParameters.getOutput());

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
