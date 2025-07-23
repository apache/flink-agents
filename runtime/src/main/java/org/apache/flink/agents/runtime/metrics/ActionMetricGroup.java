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
import org.apache.flink.util.clock.SystemClock;

/**
 * ActionMetricGroup class extends FlinkAgentsMetricGroupImpl and is used to monitor and measure the
 * performance metrics of executing actions. It provides metrics for the number of actions currently
 * executing, the number of actions executed per second, and the execution time of actions.
 */
public class ActionMetricGroup extends FlinkAgentsMetricGroupImpl {

    private final Counter numOfActionsExecuting;

    private final Meter numOfActionsExecutedPerSec;

    private final Histogram actionExecutionTime;

    private long startTime;

    public ActionMetricGroup(MetricGroup parentMetricGroup) {
        super(parentMetricGroup);

        this.numOfActionsExecuting = getCounter("numOfActionsExecuting");
        Counter numOfActionsExecuted = getCounter("numOfActionsExecuted");
        this.numOfActionsExecutedPerSec =
                getMeter("numOfActionsExecutedPerSec", numOfActionsExecuted);
        this.actionExecutionTime = getHistogram("actionExecutionTime");
    }

    /**
     * Marks that an action has started executing. Increments the executing actions counter and
     * records the start time.
     */
    public void markActionExecuting() {
        numOfActionsExecuting.inc();
        startTime = SystemClock.getInstance().relativeTimeMillis();
    }

    /**
     * Marks that an action has finished executing. Decrements the executing actions counter, marks
     * an event on the executed meter, and records the execution time in the histogram.
     */
    public void markActionExecuted() {
        long endTime = SystemClock.getInstance().relativeTimeMillis();
        numOfActionsExecuting.dec();
        numOfActionsExecutedPerSec.markEvent();
        actionExecutionTime.update(endTime - startTime);
    }
}
