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

package org.apache.flink.agents.runtime;

import org.apache.flink.agents.plan.WorkflowPlan;
import org.apache.flink.agents.runtime.feedback.FeedbackKey;
import org.apache.flink.agents.runtime.message.EventMessage;
import org.apache.flink.agents.runtime.message.EventMessage.EventMessageKeySelector;
import org.apache.flink.agents.runtime.message.Message;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.agents.runtime.operator.FeedbackOperatorFactory;
import org.apache.flink.agents.runtime.operator.FeedbackSinkOperator;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamUtils;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.util.OutputTag;

/** A utility class that bridges Flink DataStream/SQL with the Flink Agents workflow. */
public class FlinkAgent {

    /**
     * Connects the given DataStream to the Flink Agents workflow.
     *
     * @param inputDataStream The input DataStream.
     * @param keyTypeInfo The type information of the key.
     * @param workflowPlan The workflow plan.
     * @param <K> The type of the key.
     * @return The output DataStream of the workflow.
     */
    public static <K> DataStream<EventMessage<K>> connectToWorkflow(
            DataStream<EventMessage<K>> inputDataStream,
            TypeInformation<K> keyTypeInfo,
            WorkflowPlan workflowPlan) {
        TypeInformation<EventMessage<K>> eventMessageTypeInfo =
                inputDataStream.getTransformation().getOutputType();
        FeedbackKey<Message> feedbackKey = new FeedbackKey<>("feedback-pipeline", 1L);

        // 1. Create feedback operator, the feedback operator is responsible for receive event from
        // {@code inputDataStream} and {@code feedBackSinkOperator} and forward to action execute
        // operator
        DataStream<EventMessage<K>> feedbackDatastream =
                inputDataStream
                        .keyBy(new EventMessageKeySelector(), keyTypeInfo)
                        .transform(
                                "feedback",
                                eventMessageTypeInfo,
                                new FeedbackOperatorFactory(
                                        feedbackKey, new EventMessageKeySelector()))
                        .returns(eventMessageTypeInfo);

        // 2. Create the action execution operator. This operator is responsible for executing
        // actions based on events received from the {@code feedbackDatastream}. It sends the final
        // output to a side output, while events that require further processing are forwarded to
        // the {@code feedbackSinkOperator}.
        OutputTag<EventMessage<K>> outputTag =
                new OutputTag<>("feedback-output", eventMessageTypeInfo);
        SingleOutputStreamOperator<EventMessage<K>> actionExecuteDatastream =
                DataStreamUtils.reinterpretAsKeyedStream(
                                feedbackDatastream, new EventMessageKeySelector(), keyTypeInfo)
                        .transform(
                                "action-execute",
                                eventMessageTypeInfo,
                                new ActionExecutionOperatorFactory(
                                        outputTag, workflowPlan, eventMessageTypeInfo))
                        .returns(eventMessageTypeInfo);

        // 3. Create the feedback sink operator. This operator is responsible for sending events to
        // the {@code feedbackDatastream} for further processing.
        DataStream<Void> feedbackSinkDatastream =
                actionExecuteDatastream
                        .keyBy(new EventMessageKeySelector(), keyTypeInfo)
                        .transform(
                                "feedback-sink",
                                TypeInformation.of(Void.class),
                                new FeedbackSinkOperator<>(feedbackKey))
                        .returns(eventMessageTypeInfo);

        // 4. Set the co-location group key and parallelism for the three operators.
        String colocationKey = feedbackKey.asColocationKey();
        feedbackDatastream.getTransformation().setCoLocationGroupKey(colocationKey);
        actionExecuteDatastream.getTransformation().setCoLocationGroupKey(colocationKey);
        feedbackSinkDatastream.getTransformation().setCoLocationGroupKey(colocationKey);

        feedbackDatastream.getTransformation().setParallelism(inputDataStream.getParallelism());
        actionExecuteDatastream
                .getTransformation()
                .setParallelism(inputDataStream.getParallelism());
        feedbackSinkDatastream
                .getTransformation()
                .setParallelism(actionExecuteDatastream.getParallelism());

        // Return the output DataStream of the workflow.
        return actionExecuteDatastream.getSideOutput(outputTag);
    }
}
