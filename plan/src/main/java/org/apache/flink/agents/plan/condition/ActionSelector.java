/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

public abstract class ActionSelector {

    private static final Pattern EVENT_TYPE =
            Pattern.compile(
                    "^(?:"
                            + "([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)"
                            + "|(['\"])([^\\s'\"\\\\\\p{Cntrl}]+)\\2"
                            + ")$");
    private static final Set<String> EXPRESSION_LITERALS = Set.of("true", "false", "null");

    ActionSelector() {}

    /** Classifies an entry as an event type or a condition expression based on its text. */
    public static ActionSelector classify(String source) {
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
                return new EventTypeMatch(eventType);
            }
        }
        return new ConditionExpression(entry);
    }

    public static final class EventTypeMatch extends ActionSelector {

        private final String eventType;

        private EventTypeMatch(String eventType) {
            this.eventType = eventType;
        }

        public String eventType() {
            return eventType;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EventTypeMatch
                    && eventType.equals(((EventTypeMatch) other).eventType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventType);
        }

        @Override
        public String toString() {
            return "EventTypeMatch{eventType='" + eventType + "'}";
        }
    }

    public static final class ConditionExpression extends ActionSelector {

        private final String text;

        private ConditionExpression(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ConditionExpression
                    && text.equals(((ConditionExpression) other).text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(text);
        }

        @Override
        public String toString() {
            return "ConditionExpression{text='" + text + "'}";
        }
    }
}
