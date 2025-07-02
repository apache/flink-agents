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

import org.apache.flink.agents.runtime.common.MailboxExecutorFacade;
import org.apache.flink.agents.runtime.feedback.Checkpoints;
import org.apache.flink.agents.runtime.feedback.FeedbackChannel;
import org.apache.flink.agents.runtime.feedback.FeedbackChannelBroker;
import org.apache.flink.agents.runtime.feedback.FeedbackConsumer;
import org.apache.flink.agents.runtime.feedback.FeedbackKey;
import org.apache.flink.agents.runtime.feedback.SubtaskFeedbackKey;
import org.apache.flink.agents.runtime.logger.Loggers;
import org.apache.flink.agents.runtime.logger.UnboundedFeedbackLogger;
import org.apache.flink.agents.runtime.logger.UnboundedFeedbackLoggerFactory;
import org.apache.flink.agents.runtime.message.EventMessage;
import org.apache.flink.agents.runtime.message.Message;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.state.KeyGroupStatePartitionStreamProvider;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.util.IOUtils;
import org.apache.flink.util.function.SerializableFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.Executor;

/**
 * A special operator designed to receive both the input data stream and the feedback stream from
 * the {@link FeedbackSinkOperator}. It will serve as the head node of the workflow execution loop
 * and forward the data to the {@link ActionExecutionOperator} operator.
 *
 * <p>NOTE: This code is adapted from the <a
 * href="https://github.com/apache/flink-statefun">flink-statefun</a>.
 *
 * @param <K> The type of the key of input event.
 */
public class FeedbackOperator<K> extends AbstractStreamOperator<EventMessage<K>>
        implements FeedbackConsumer<Message>,
                OneInputStreamOperator<EventMessage<K>, EventMessage<K>> {

    private static final Logger LOG = LoggerFactory.getLogger(FeedbackOperator.class);

    private static final long serialVersionUID = 1L;

    private final MailboxExecutor mailboxExecutor;

    private final FeedbackKey<Message> feedbackKey;

    private final long totalMemoryUsedForFeedbackCheckpointing;

    private transient Checkpoints<Message> checkpoints;

    private final TypeSerializer<EventMessage<K>> elementSerializer;

    private final SerializableFunction<EventMessage<K>, K> keySelector;

    private transient StreamRecord<EventMessage<K>> reusedStreamRecord;

    private transient boolean closedOrDisposed;

    FeedbackOperator(
            FeedbackKey<Message> feedbackKey,
            SerializableFunction<EventMessage<K>, K> keySelector,
            long totalMemoryUsedForFeedbackCheckpointing,
            TypeSerializer<EventMessage<K>> elementSerializer,
            MailboxExecutor mailboxExecutor,
            ProcessingTimeService processingTimeService) {
        this.feedbackKey = Objects.requireNonNull(feedbackKey);
        this.keySelector = Objects.requireNonNull(keySelector);
        this.totalMemoryUsedForFeedbackCheckpointing = totalMemoryUsedForFeedbackCheckpointing;
        this.elementSerializer = elementSerializer;
        this.mailboxExecutor = mailboxExecutor;
        this.processingTimeService = processingTimeService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);

        final IOManager ioManager = getContainingTask().getEnvironment().getIOManager();
        final int maxParallelism =
                getRuntimeContext().getTaskInfo().getMaxNumberOfParallelSubtasks();
        this.reusedStreamRecord = new StreamRecord<>(null);

        //
        // Initialize the unbounded feedback logger
        //
        UnboundedFeedbackLoggerFactory<Message> feedbackLoggerFactory =
                (UnboundedFeedbackLoggerFactory<Message>)
                        Loggers.unboundedSpillableLoggerFactory(
                                ioManager,
                                maxParallelism,
                                totalMemoryUsedForFeedbackCheckpointing,
                                elementSerializer,
                                keySelector);

        this.checkpoints = new Checkpoints<>(feedbackLoggerFactory::create);

        //
        // we first must reply previously check-pointed envelopes before we start
        // processing any new envelopes.
        //
        UnboundedFeedbackLogger<Message> logger = feedbackLoggerFactory.create();
        for (KeyGroupStatePartitionStreamProvider keyedStateInput :
                context.getRawKeyedStateInputs()) {
            logger.replyLoggedEnvelops(keyedStateInput.getStream(), this);
        }

        registerFeedbackConsumer(new MailboxExecutorFacade(mailboxExecutor, "Feedback Consumer"));
    }

    @Override
    public void processFeedback(Message element) throws Exception {
        if (closedOrDisposed) {
            return;
        }
        LOG.debug("FeedbackOperator processing feedback {}", element);
        OptionalLong maybeCheckpoint = element.isBarrierMessage();
        if (maybeCheckpoint.isPresent()) {
            // if it is a checkpoint barrier, we can commit all checkpoints up to the given
            // checkpoint id
            LOG.debug(
                    "FeedbackOperator receive a checkpoint barrier with id {}",
                    maybeCheckpoint.getAsLong());
            checkpoints.commitCheckpointsUntil(maybeCheckpoint.getAsLong());
        } else {
            // if it is a normal element, we can directly send it to the downstream operator and
            // append it to the checkpoints
            sendDownstream(element);
            checkpoints.append(element);
        }
    }

    @Override
    public void processElement(StreamRecord<EventMessage<K>> streamRecord) throws Exception {
        LOG.debug("FeedbackOperator processElement {}", streamRecord);
        sendDownstream(streamRecord.getValue());
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        super.snapshotState(context);
        checkpoints.startLogging(
                context.getCheckpointId(), context.getRawKeyedOperatorStateOutput());
    }

    @Override
    protected boolean isUsingCustomRawKeyedState() {
        return true;
    }

    @Override
    public void close() throws Exception {
        closeInternally();
        super.close();
    }

    private void closeInternally() {
        IOUtils.closeQuietly(checkpoints);
        checkpoints = null;
        closedOrDisposed = true;
    }

    private void registerFeedbackConsumer(Executor mailboxExecutor) {
        final int indexOfThisSubtask = getRuntimeContext().getTaskInfo().getIndexOfThisSubtask();
        final int attemptNum = getRuntimeContext().getTaskInfo().getAttemptNumber();
        final SubtaskFeedbackKey<Message> key =
                feedbackKey.withSubTaskIndex(indexOfThisSubtask, attemptNum);
        FeedbackChannelBroker broker = FeedbackChannelBroker.get();
        FeedbackChannel<Message> channel = broker.getChannel(key);
        channel.registerConsumer(this, mailboxExecutor);
    }

    private void sendDownstream(Message element) {
        reusedStreamRecord.replace(element);
        output.collect(reusedStreamRecord);
    }
}
