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

package org.apache.flink.agents.runtime.message;

import org.apache.flink.agents.api.Event;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.util.function.SerializableFunction;

import java.util.OptionalLong;

/**
 * A special type used to wrap {@link org.apache.flink.agents.api.Event}, which must include a key
 * and the actual event. This type will be used for data transmission between Flink operators.
 *
 * @param <K> The type of the key.
 */
public class EventMessage<K> implements Message {

    private static final long serialVersionUID = 1L;

    /** The key of the event. */
    private final K key;

    private final Event event;

    public EventMessage(K key, Event event) {
        this.key = key;
        this.event = event;
    }

    public K getKey() {
        return key;
    }

    public Event getEvent() {
        return event;
    }

    public String getEventType() {
        return event.getEventType();
    }

    @Override
    public OptionalLong isBarrierMessage() {
        return OptionalLong.empty();
    }

    public boolean isInputEvent() {
        return event.isInputEvent();
    }

    public boolean isOutputEvent() {
        return event.isOutputEvent();
    }

    @Override
    public String toString() {
        return "EventMessage{" + "key=" + key + ", event=" + event + '}';
    }

    public static final class EventMessageKeySelector<K>
            implements SerializableFunction<EventMessage<K>, K>, KeySelector<EventMessage<K>, K> {

        private static final long serialVersionUID = 1;

        @Override
        public K apply(EventMessage<K> eventMessage) {
            return getKey(eventMessage);
        }

        @Override
        public K getKey(EventMessage<K> eventMessage) {
            return eventMessage.getKey();
        }
    }
}
