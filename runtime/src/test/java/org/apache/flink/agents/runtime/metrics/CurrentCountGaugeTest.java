/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flink.agents.runtime.metrics;

import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentCountGaugeTest {

    private static final String GAUGE_NAME = "current";

    private FlinkAgentsMetricGroupImpl metricGroup;
    private CurrentCountGauge gauge;

    @BeforeEach
    void setUp() {
        MetricGroup parentMetricGroup =
                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup();
        metricGroup = new FlinkAgentsMetricGroupImpl(parentMetricGroup);
        gauge = new CurrentCountGauge(metricGroup, GAUGE_NAME);
    }

    @Test
    void decrementAtZeroRemainsZero() {
        gauge.decrement();

        assertThat(value()).isZero();
    }

    @Test
    void negativeSetValueIsClampedToZero() {
        gauge.set(-1L);

        assertThat(value()).isZero();
    }

    private long value() {
        return (Long) metricGroup.getGauge(GAUGE_NAME).getValue();
    }
}
