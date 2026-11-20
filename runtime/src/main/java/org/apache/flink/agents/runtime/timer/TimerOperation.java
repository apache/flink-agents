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
package org.apache.flink.agents.runtime.timer;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.context.TimerMode;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.runtime.utils.RuntimeIdentity;
import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.memory.DataInputDeserializer;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.apache.flink.streaming.api.TimeDomain;

import java.util.Arrays;
import java.util.Objects;

/** An ordered Action effect. Relative times are resolved before journaling Action completion. */
public class TimerOperation {
    private String timerId;
    private long time;
    private boolean relative;
    private byte[] eventBytes;
    private TimerMode mode;
    private TimeDomain timeDomain;
    private byte[] ownerContextBytes;
    private Long sourceTimestamp;
    private String occurrenceId;

    public TimerOperation() {}

    public TimerOperation(
            String timerId, long time, boolean relative, byte[] eventBytes, TimerMode mode) {
        this(timerId, time, relative, eventBytes, mode, TimeDomain.PROCESSING_TIME);
    }

    public TimerOperation(
            String timerId,
            long time,
            boolean relative,
            byte[] eventBytes,
            TimerMode mode,
            TimeDomain timeDomain) {
        this.timerId = timerId;
        this.time = time;
        this.relative = relative;
        this.eventBytes = eventBytes;
        this.mode = mode;
        this.timeDomain = timeDomain;
    }

    public String getTimerId() {
        return timerId;
    }

    public long getTime() {
        return time;
    }

    public boolean isRelative() {
        return relative;
    }

    public byte[] getEventBytes() {
        return eventBytes;
    }

    public TimerMode getMode() {
        return mode;
    }

    public TimeDomain getTimeDomain() {
        return timeDomain;
    }

    public byte[] getOwnerContextBytes() {
        return ownerContextBytes;
    }

    public Long getSourceTimestamp() {
        return sourceTimestamp;
    }

    public String getOccurrenceId() {
        return occurrenceId;
    }

    public TimerOperation resolve(
            long now, TraceContext ownerContext, Long timestamp, String producerId, int ordinal)
            throws Exception {
        TimerOperation resolved =
                new TimerOperation(
                        timerId,
                        relative ? Math.addExact(now, time) : time,
                        false,
                        eventBytes,
                        mode,
                        timeDomain);
        if (eventBytes != null && mode == TimerMode.CURRENT_RUN_WAIT_FOR_TRIGGER) {
            DataOutputSerializer out = new DataOutputSerializer(256);
            contextSerializer().serialize(ownerContext, out);
            resolved.ownerContextBytes = out.getCopyOfBuffer();
            resolved.sourceTimestamp = timestamp;
            resolved.occurrenceId =
                    RuntimeIdentity.deriveEmittedEventOccurrenceId(producerId + ":timer", ordinal);
        }
        return resolved;
    }

    /** Binds a replayed registration to the run executing the reused Action result. */
    public TimerOperation withOwnerContext(TraceContext ownerContext) throws Exception {
        TimerOperation copy =
                new TimerOperation(timerId, time, relative, eventBytes, mode, timeDomain);
        DataOutputSerializer out = new DataOutputSerializer(256);
        contextSerializer().serialize(ownerContext, out);
        copy.ownerContextBytes = out.getCopyOfBuffer();
        copy.sourceTimestamp = sourceTimestamp;
        copy.occurrenceId = occurrenceId;
        return copy;
    }

    /** Serializes the Event after registration has prepared its attachment references. */
    public static byte[] snapshot(Event event, TypeSerializer<Event> serializer) {
        try {
            DataOutputSerializer out = new DataOutputSerializer(256);
            serializer.serialize(event, out);
            return out.getCopyOfBuffer();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot snapshot timer Event", e);
        }
    }

    public Event restoreEvent(TypeSerializer<Event> serializer) throws Exception {
        Event event = serializer.deserialize(new DataInputDeserializer(eventBytes));
        event.setSourceTimestamp(
                timeDomain == TimeDomain.EVENT_TIME ? Long.valueOf(time) : sourceTimestamp);
        event.setOccurrenceId(occurrenceId);
        return event;
    }

    public TraceContext restoreOwnerContext() throws Exception {
        return contextSerializer().deserialize(new DataInputDeserializer(ownerContextBytes));
    }

    private static TypeSerializer<TraceContext> contextSerializer() {
        return TypeInformation.of(TraceContext.class).createSerializer(new SerializerConfigImpl());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TimerOperation)) {
            return false;
        }
        TimerOperation that = (TimerOperation) other;
        return time == that.time
                && relative == that.relative
                && Objects.equals(timerId, that.timerId)
                && mode == that.mode
                && timeDomain == that.timeDomain
                && Arrays.equals(ownerContextBytes, that.ownerContextBytes)
                && Objects.equals(sourceTimestamp, that.sourceTimestamp)
                && Objects.equals(occurrenceId, that.occurrenceId)
                && Arrays.equals(eventBytes, that.eventBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                timerId,
                time,
                relative,
                mode,
                timeDomain,
                sourceTimestamp,
                occurrenceId,
                Arrays.hashCode(eventBytes),
                Arrays.hashCode(ownerContextBytes));
    }
}
