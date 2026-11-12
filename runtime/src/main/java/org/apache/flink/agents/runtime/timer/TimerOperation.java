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
import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.memory.DataInputDeserializer;
import org.apache.flink.core.memory.DataOutputSerializer;

import java.util.Arrays;
import java.util.Objects;

/** An ordered Action effect. Relative times are resolved before journaling Action completion. */
public class TimerOperation {
    private String timerId;
    private long time;
    private boolean relative;
    private byte[] eventBytes;

    public TimerOperation() {}

    public TimerOperation(String timerId, long time, boolean relative, byte[] eventBytes) {
        this.timerId = timerId;
        this.time = time;
        this.relative = relative;
        this.eventBytes = eventBytes;
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

    public TimerOperation resolve(long now) {
        return relative
                ? new TimerOperation(timerId, Math.addExact(now, time), false, eventBytes)
                : this;
    }

    /** Copies an Event for delivery in a new run. */
    public static byte[] snapshot(Event event) {
        Objects.requireNonNull(event, "event");
        try {
            TypeSerializer<Event> serializer = serializer();
            Event copy = serializer.copy(event);
            copy.setSourceTimestamp(null);
            copy.setOccurrenceId(null);
            DataOutputSerializer out = new DataOutputSerializer(256);
            serializer.serialize(copy, out);
            return out.getCopyOfBuffer();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot snapshot timer Event", e);
        }
    }

    public Event restoreEvent() throws Exception {
        return serializer().deserialize(new DataInputDeserializer(eventBytes));
    }

    private static TypeSerializer<Event> serializer() {
        return TypeInformation.of(Event.class).createSerializer(new SerializerConfigImpl());
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
                && Arrays.equals(eventBytes, that.eventBytes);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(timerId, time, relative) + Arrays.hashCode(eventBytes);
    }
}
