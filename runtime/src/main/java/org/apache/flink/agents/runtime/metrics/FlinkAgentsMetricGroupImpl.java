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

import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Histogram;
import org.apache.flink.metrics.Meter;
import org.apache.flink.metrics.MeterView;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.metrics.DescriptiveStatisticsHistogram;
import org.apache.flink.runtime.metrics.groups.ProxyMetricGroup;

import java.util.HashMap;

/**
 * Implementation of the FlinkAgentsMetricGroup interface, providing access to various types of
 * metrics. This class extends ProxyMetricGroup and manages metrics such as counters, gauges,
 * meters, and histograms.
 */
public class FlinkAgentsMetricGroupImpl extends ProxyMetricGroup<MetricGroup>
        implements FlinkAgentsMetricGroup {

    private final HashMap<String, FlinkAgentsMetricGroupImpl> subMetricGroups = new HashMap<>();

    private final HashMap<String, Counter> counters = new HashMap<>();

    private final HashMap<String, Meter> meters = new HashMap<>();

    private final HashMap<String, Histogram> histograms = new HashMap<>();

    private final HashMap<String, StringGaugeImpl> gauges = new HashMap<>();

    public FlinkAgentsMetricGroupImpl(MetricGroup parentMetricGroup) {
        super(parentMetricGroup);
    }

    public FlinkAgentsMetricGroupImpl getSubGroup(String name) {
        if (!subMetricGroups.containsKey(name)) {
            subMetricGroups.put(name, new FlinkAgentsMetricGroupImpl(super.addGroup(name)));
        }
        return subMetricGroups.get(name);
    }

    public StringGaugeImpl getGauge(String name) {
        if (!gauges.containsKey(name)) {
            gauges.put(name, super.gauge(name, new StringGaugeImpl()));
        }
        return gauges.get(name);
    }

    public Counter getCounter(String name) {
        if (!counters.containsKey(name)) {
            counters.put(name, super.counter(name));
        }
        return counters.get(name);
    }

    public Meter getMeter(String name) {
        if (!meters.containsKey(name)) {
            meters.put(name, super.meter(name, new MeterView(60)));
        }
        return meters.get(name);
    }

    public Meter getMeter(String name, Counter counter) {
        if (!meters.containsKey(name)) {
            meters.put(name, super.meter(name, new MeterView(counter)));
        }
        return meters.get(name);
    }

    public Histogram getHistogram(String name) {
        if (!histograms.containsKey(name)) {
            histograms.put(name, super.histogram(name, new DescriptiveStatisticsHistogram(100)));
        }
        return histograms.get(name);
    }

    public Histogram getHistogram(String name, int windowSize) {
        if (!histograms.containsKey(name)) {
            histograms.put(
                    name, super.histogram(name, new DescriptiveStatisticsHistogram(windowSize)));
        }
        return histograms.get(name);
    }
}
