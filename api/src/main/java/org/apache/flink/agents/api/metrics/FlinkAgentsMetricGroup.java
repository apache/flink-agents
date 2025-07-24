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

package org.apache.flink.agents.api.metrics;

import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Histogram;
import org.apache.flink.metrics.Meter;

/**
 * Abstract base class providing metric group for action execution. This metric group offers access
 * to various types of metrics.
 */
public interface FlinkAgentsMetricGroup {

    /**
     * Create or retrieve a sub-metric group with the given name.
     *
     * @param name The name of the sub metric group.
     * @return the sub-metric group instance.
     */
    FlinkAgentsMetricGroup getSubGroup(String name);

    /**
     * Create or retrieve a gauge with the given name.
     *
     * <p>Note: We use StringGauge here to ensure consistency across Python and Java interactions,
     * avoiding potential type conflicts by standardizing on String as the value type.
     *
     * @param name The name of the gauge.
     * @return the updatable gauge instance.
     */
    UpdatableGauge getGauge(String name);

    /**
     * Create or retrieve a counter with the given name.
     *
     * @param name The name of the counter.
     * @return the counter instance.
     */
    Counter getCounter(String name);

    /**
     * Create or retrieve a meter with the given name.
     *
     * @param name The name of the meter.
     * @return the meter instance.
     */
    Meter getMeter(String name);

    /**
     * Create or retrieve a meter with the given name and associate it with the provided counter.
     *
     * @param name The name of the meter.
     * @param counter The counter to associate with the meter.
     * @return the meter instance.
     */
    Meter getMeter(String name, Counter counter);

    /**
     * Create or retrieve a histogram with the given name using the default window size.
     *
     * @param name The name of the histogram.
     * @return the histogram instance.
     */
    Histogram getHistogram(String name);

    /**
     * Create or retrieve a histogram with the given name and specified window size.
     *
     * @param name The name of the histogram.
     * @param windowSize The sliding window size for histogram statistics.
     * @return the histogram instance.
     */
    Histogram getHistogram(String name, int windowSize);
}
