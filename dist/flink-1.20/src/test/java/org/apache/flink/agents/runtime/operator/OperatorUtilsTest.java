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

import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.StreamMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Test for {@link OperatorUtils}. */
public class OperatorUtilsTest {

    @Test
    void testSetChainStrategyAlways() {
        // Create a test operator
        TestOperator operator = new TestOperator();

        // Initially, the chaining strategy should be HEAD (default)
        assertThat(operator.getChainingStrategy()).isEqualTo(ChainingStrategy.HEAD);

        // Set chaining strategy to ALWAYS
        OperatorUtils.setChainStrategy(operator, ChainingStrategy.ALWAYS);

        // Verify the strategy was set correctly
        assertThat(operator.getChainingStrategy()).isEqualTo(ChainingStrategy.ALWAYS);
    }

    @Test
    void testSetChainStrategyNever() {
        // Create a test operator
        TestOperator operator = new TestOperator();

        // Set chaining strategy to NEVER
        OperatorUtils.setChainStrategy(operator, ChainingStrategy.NEVER);

        // Verify the strategy was set correctly
        assertThat(operator.getChainingStrategy()).isEqualTo(ChainingStrategy.NEVER);
    }

    @Test
    void testSetChainStrategyHead() {
        // Create a test operator
        TestOperator operator = new TestOperator();

        // First set to ALWAYS
        OperatorUtils.setChainStrategy(operator, ChainingStrategy.ALWAYS);
        assertThat(operator.getChainingStrategy()).isEqualTo(ChainingStrategy.ALWAYS);

        // Then change back to HEAD
        OperatorUtils.setChainStrategy(operator, ChainingStrategy.HEAD);

        // Verify the strategy was updated correctly
        assertThat(operator.getChainingStrategy()).isEqualTo(ChainingStrategy.HEAD);
    }

    @Test
    void testSetChainStrategyWithDifferentOperators() {
        // Test with StreamMap operator
        StreamMap<String, String> mapOperator = new StreamMap<>(value -> value.toUpperCase());

        // Set chaining strategy
        OperatorUtils.setChainStrategy(mapOperator, ChainingStrategy.ALWAYS);

        // Verify the strategy was set correctly
        assertThat(mapOperator.getChainingStrategy()).isEqualTo(ChainingStrategy.ALWAYS);
    }

    /** Simple test operator for testing purposes. */
    private static class TestOperator extends AbstractStreamOperator<String> {}
}
