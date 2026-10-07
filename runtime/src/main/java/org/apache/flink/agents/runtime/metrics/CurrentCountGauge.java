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

/** Maintains a non-negative current-count gauge on the operator mailbox thread. */
final class CurrentCountGauge {

    private final UpdatableGaugeImpl<Long> gauge;
    private long value;

    @SuppressWarnings("unchecked")
    CurrentCountGauge(FlinkAgentsMetricGroupImpl metricGroup, String name) {
        this.gauge = (UpdatableGaugeImpl<Long>) metricGroup.getGauge(name);
        update(0L);
    }

    void increment() {
        update(value + 1L);
    }

    void decrement() {
        update(Math.max(0L, value - 1L));
    }

    void set(long value) {
        update(Math.max(0L, value));
    }

    private void update(long value) {
        this.value = value;
        gauge.update(value);
    }
}
