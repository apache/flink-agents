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

import org.apache.flink.agents.runtime.feedback.FeedbackChannel;
import org.apache.flink.agents.runtime.feedback.FeedbackChannelBroker;
import org.apache.flink.agents.runtime.feedback.FeedbackKey;
import org.apache.flink.agents.runtime.feedback.SubtaskFeedbackKey;
import org.apache.flink.agents.runtime.message.CheckpointMessage;
import org.apache.flink.agents.runtime.message.EventMessage;
import org.apache.flink.agents.runtime.message.Message;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * A special operator designed to receive the input data stream from the {@link
 * ActionExecutionOperator} and forward it to the {@link FeedbackOperator}. It will serve as the
 * tail node of the workflow execution loop.
 *
 * <p>NOTE: This code is adapted from the <a
 * href="https://github.com/apache/flink-statefun">flink-statefun</a>.
 *
 * @param <K> The type of the key of input event.
 */
public class FeedbackSinkOperator<K> extends AbstractStreamOperator<Void>
        implements OneInputStreamOperator<EventMessage<K>, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(FeedbackSinkOperator.class);

    private static final long serialVersionUID = 1;
    private final FeedbackKey<Message> key;
    private transient FeedbackChannel<Message> channel;

    public FeedbackSinkOperator(FeedbackKey<Message> key) {
        this.key = Objects.requireNonNull(key);
    }

    @Override
    public void open() throws Exception {
        super.open();
        final int indexOfThisSubtask = getRuntimeContext().getTaskInfo().getIndexOfThisSubtask();
        final int attemptNum = getRuntimeContext().getTaskInfo().getAttemptNumber();
        final SubtaskFeedbackKey<Message> key =
                this.key.withSubTaskIndex(indexOfThisSubtask, attemptNum);

        FeedbackChannelBroker broker = FeedbackChannelBroker.get();
        this.channel = broker.getChannel(key);
    }

    @Override
    public void processElement(StreamRecord<EventMessage<K>> streamRecord) {
        Message value = streamRecord.getValue();
        LOG.debug("FeedbackSinkOperator processElement {}", value);
        // send the message to the feedback channel directly
        channel.put(value);
    }

    @Override
    public void prepareSnapshotPreBarrier(long checkpointId) throws Exception {
        super.prepareSnapshotPreBarrier(checkpointId);
        Message sentinel = new CheckpointMessage(checkpointId);
        LOG.debug(
                "FeedbackSinkOperator send checkpoint message with id {} to feedback channel.",
                checkpointId);
        channel.put(sentinel);
    }

    @Override
    public void close() throws Exception {
        IOUtils.closeQuietly(channel);
        super.close();
    }
}
