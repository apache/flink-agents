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

import org.apache.flink.agents.plan.condition.TriggerCondition.EventTypeCondition;
import org.apache.flink.agents.plan.condition.TriggerCondition.ExpressionCondition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriggerConditionTest {

    @Test
    void classifiesBareEventTypes() {
        assertEventType("  a.b.c  ", "a.b.c");
        assertEventType("order-created.v1", "order-created.v1");
    }

    @Test
    void classifiesQuotedEventTypes() {
        TriggerCondition singleQuoted = assertEventType("'order-created.v1'", "order-created.v1");
        TriggerCondition doubleQuoted = assertEventType("\"order-created.v1\"", "order-created.v1");

        assertThat(singleQuoted).isEqualTo(doubleQuoted);
    }

    @Test
    void classifiesReservedExpressionCandidates() {
        assertConditionExpression("true", "true");
        assertConditionExpression("false", "false");
        assertConditionExpression("null", "null");
        assertConditionExpression("EventType.InputEvent", "EventType.InputEvent");

        assertEventType("in", "in");
        assertEventType("has", "has");
        assertEventType("size", "size");
    }

    @Test
    void classifiesNonEventTypesAsExpressions() {
        assertConditionExpression("  a . b . c  ", "a . b . c");
        assertConditionExpression("(a.b.c)", "(a.b.c)");
        assertConditionExpression("'has whitespace'", "'has whitespace'");
        assertConditionExpression("'has\\backslash'", "'has\\backslash'");
        assertConditionExpression("'has\"quote'", "'has\"quote'");
        assertConditionExpression("'has\u0001control'", "'has\u0001control'");
    }

    @Test
    void rejectsNullOrBlankEntries() {
        assertThatThrownBy(() -> TriggerCondition.classify(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-null and non-blank");
        assertThatThrownBy(() -> TriggerCondition.classify("  \t\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-null and non-blank");
    }

    @Test
    void trimsConditionExpression() {
        assertConditionExpression("  score > 1  ", "score > 1");
    }

    private static TriggerCondition assertEventType(String source, String expectedEventType) {
        TriggerCondition condition = TriggerCondition.classify(source);
        assertThat(condition).isInstanceOf(EventTypeCondition.class);
        assertThat(((EventTypeCondition) condition).eventType()).isEqualTo(expectedEventType);
        return condition;
    }

    private static TriggerCondition assertConditionExpression(String source, String expectedText) {
        TriggerCondition condition = TriggerCondition.classify(source);
        assertThat(condition).isInstanceOf(ExpressionCondition.class);
        assertThat(((ExpressionCondition) condition).text()).isEqualTo(expectedText);
        return condition;
    }
}
