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

import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Histogram;
import org.apache.flink.metrics.Meter;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link FlinkAgentsMetricGroupImpl}. */
public class FlinkAgentsMetricGroupImplTest {

    private FlinkAgentsMetricGroupImpl metricGroup;

    @BeforeEach
    void setUp() {
        MetricGroup parentMetricGroup =
                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup();
        metricGroup = new FlinkAgentsMetricGroupImpl(parentMetricGroup);
    }

    @Test
    void testGetSubGroup() {
        String name = "testGroup";
        FlinkAgentsMetricGroupImpl result = metricGroup.getSubGroup(name);

        assertNotNull(result);
        assertEquals(result, metricGroup.getSubGroup(name));
    }

    @Test
    void testGetSubGroupWithKeyValue() {
        String key = "model";
        String value = "gpt-4";
        FlinkAgentsMetricGroupImpl result = metricGroup.getSubGroup(key, value);

        assertNotNull(result);
        assertEquals(result, metricGroup.getSubGroup(key, value));
    }

    @Test
    void testKeyValueSubGroupIsolatedFromNamedSubGroup() {
        FlinkAgentsMetricGroupImpl named = metricGroup.getSubGroup("model");
        FlinkAgentsMetricGroupImpl kv = metricGroup.getSubGroup("model", "gpt-4");

        assertNotSame(named, kv);

        named.getCounter("c").inc(10);
        kv.getCounter("c").inc(99);

        assertEquals(10, named.getCounter("c").getCount());
        assertEquals(99, kv.getCounter("c").getCount());
    }

    @Test
    void testDifferentValuesCreateDistinctSubGroups() {
        FlinkAgentsMetricGroupImpl gpt4 = metricGroup.getSubGroup("model", "gpt-4");
        FlinkAgentsMetricGroupImpl gpt35 = metricGroup.getSubGroup("model", "gpt-3.5");

        assertNotSame(gpt4, gpt35);

        gpt4.getCounter("promptTokens").inc(100);
        gpt35.getCounter("promptTokens").inc(200);

        assertEquals(100, gpt4.getCounter("promptTokens").getCount());
        assertEquals(200, gpt35.getCounter("promptTokens").getCount());
    }

    @Test
    void testGetGauge() {
        String name = "testGauge";
        UpdatableGaugeImpl result = metricGroup.getGauge(name);

        assertNotNull(result);
        assertSame(result, metricGroup.getGauge(name));
    }

    @Test
    void testGetCounter() {
        String name = "testCounter";
        Counter result = metricGroup.getCounter(name);

        assertNotNull(result);
        assertEquals(result, metricGroup.getCounter(name));
    }

    @Test
    void testGetMeterWithouCounter() {
        String name = "testMeter";
        Meter result = metricGroup.getMeter(name);

        assertNotNull(result);
        assertEquals(result, metricGroup.getMeter(name));
    }

    @Test
    void testGetMeterWithCounter() {
        String name = "testMeter";
        Counter counter = new SimpleCounter();

        Meter result = metricGroup.getMeter(name, counter);

        assertNotNull(result);
        assertEquals(result, metricGroup.getMeter(name));
    }

    @Test
    void testGetHistogramWithoutWindowSize() {
        String name = "testHistogram";
        Histogram result = metricGroup.getHistogram(name);

        assertNotNull(result);
        assertEquals(result, metricGroup.getHistogram(name));
    }

    @Test
    void testGetHistogramWithWindowSize() {
        String name = "testHistogram";
        int windowSize = 200;
        Histogram result = metricGroup.getHistogram(name, windowSize);

        assertNotNull(result);
        assertEquals(result, metricGroup.getHistogram(name));
    }
}
