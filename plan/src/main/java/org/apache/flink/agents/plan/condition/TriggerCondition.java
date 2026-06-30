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

package org.apache.flink.agents.plan.condition;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One classified entry from an action's {@code trigger_conditions}. */
public abstract class TriggerCondition {

    private static final Pattern EVENT_TYPE =
            Pattern.compile(
                    "^(?:"
                            + "([A-Za-z_][A-Za-z0-9_-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*)*)"
                            + "|(['\"])([^\\s'\"\\\\\\p{Cntrl}]+)\\2"
                            + ")$");
    private static final Set<String> EXPRESSION_LITERALS = Set.of("true", "false", "null");

    TriggerCondition() {}

    /** Classifies an entry as an event-type condition or an expression condition. */
    public static TriggerCondition classify(String source) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("Trigger condition must be non-null and non-blank");
        }
        String entry = source.trim();
        Matcher matcher = EVENT_TYPE.matcher(entry);
        if (matcher.matches()) {
            String bareType = matcher.group(1);
            String eventType = bareType != null ? bareType : matcher.group(3);
            boolean reservedExpression =
                    eventType.startsWith("EventType.")
                            || (bareType != null && EXPRESSION_LITERALS.contains(bareType));
            if (!reservedExpression) {
                return new EventTypeCondition(eventType);
            }
        }
        return new ExpressionCondition(entry);
    }

    /** A trigger condition that matches one exact event type. */
    public static final class EventTypeCondition extends TriggerCondition {

        private final String eventType;

        private EventTypeCondition(String eventType) {
            this.eventType = eventType;
        }

        public String eventType() {
            return eventType;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EventTypeCondition
                    && eventType.equals(((EventTypeCondition) other).eventType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventType);
        }

        @Override
        public String toString() {
            return "EventTypeCondition{eventType='" + eventType + "'}";
        }
    }

    /** A trigger condition expressed as a Boolean expression. */
    public static final class ExpressionCondition extends TriggerCondition {

        private final String text;

        private ExpressionCondition(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ExpressionCondition
                    && text.equals(((ExpressionCondition) other).text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(text);
        }

        @Override
        public String toString() {
            return "ExpressionCondition{text='" + text + "'}";
        }
    }
}
