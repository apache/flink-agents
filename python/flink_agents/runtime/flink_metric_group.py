################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#################################################################################
from typing import Any

from typing_extensions import override

from flink_agents.api.metric_group import Counter, Gauge, Histogram, Meter, MetricGroup


class FlinkMetricGroup(MetricGroup):
    """Implementation of MetricGroup for flink execution environment."""

    def __init__(self, j_metric_group: Any) -> None:
        """Initialize a flink metric group with the given java metric group.

        Parameters
        ----------
        j_metric_group : Any
            Java metric group used to synchronize data with the Flink metric system.
        """
        self._j_metric_group = j_metric_group

    @override
    def get_sub_group(self, name: str) -> "FlinkMetricGroup":
        return FlinkMetricGroup(self._j_metric_group.getSubGroup(name))

    @override
    def get_counter(self, name: str) -> "FlinkCounter":
        return FlinkCounter(self._j_metric_group.getCounter(name))

    @override
    def get_meter(self, name: str) -> "FlinkMeter":
        return FlinkMeter(self._j_metric_group.getMeter(name))

    @override
    def get_histogram(self, name: str, window_size: int = 100) -> "FlinkHistogram":
        return FlinkHistogram(self._j_metric_group.getHistogram(name, window_size))

    @override
    def get_gauge(self, name: str) -> "FlinkGauge":
        return FlinkGauge(self._j_metric_group.getGauge(name))


class FlinkCounter(Counter):
    """Implementation of Counter for flink execution environment."""

    def __init__(self, j_counter: Any) -> None:
        """Initialize a flink runner context with the given java runner context.

        Parameters
        ----------
        j_counter : Any
            Java counter used for measuring the count of events.
        """
        self._j_counter = j_counter

    @override
    def inc(self, n: int = 1) -> None:
        """Increment the current count by the given value."""
        self._j_counter.inc(n)

    @override
    def dec(self, n: int = 1) -> None:
        """Decrement the current count by the given value."""
        self._j_counter.dec(n)

    @override
    def get_count(self) -> int:
        """Return the current count."""
        return self._j_counter.getCount()


class FlinkMeter(Meter):
    """Implementation of Meter for flink execution environment."""

    def __init__(self, j_meter: Any) -> None:
        """Initialize a flink meter with the given java meter.

        Parameters
        ----------
        j_meter : Any
            Java meter measures throughput.
        """
        self._j_meter = j_meter

    @override
    def mark(self, n: int = 1) -> None:
        """Mark the occurrence of n events."""
        self._j_meter.markEvent(n)

    @override
    def get_rate(self) -> float:
        """Return the current event rate per second."""
        return self._j_meter.getRate()


class FlinkHistogram(Histogram):
    """Implementation of Histogram for flink execution environment."""

    def __init__(self, j_histogram: Any) -> None:
        """Initialize a flink histogram with the given java histogram.

        Parameters
        ----------
        j_histogram : Any
            Java histogram used for recording values and computing statistical
            summaries.
        """
        self._j_histogram = j_histogram
        self._j_statistics = j_histogram.getStatistics()

    @override
    def update(self, value: int) -> None:
        """Record a new value into the histogram."""
        self._j_histogram.update(value)

    @override
    def get_mean(self) -> float:
        """Return the average value."""
        return self._j_statistics.getMean()

    @override
    def get_max(self) -> int:
        """Return the maximum recorded value."""
        return self._j_statistics.getMax()

    @override
    def get_min(self) -> int:
        """Return the minimum recorded value."""
        return self._j_statistics.getMin()


class FlinkGauge(Gauge):
    """Implementation of Gauge for flink execution environment."""

    def __init__(self, j_gauge: Any) -> None:
        """Initialize a flink gauge with the given java gauge.

        Parameters
        ----------
        j_gauge : Any
            Java gauge for recording a string value.
        """
        self._j_gauge = j_gauge

    @override
    def update(self, value: float) -> None:
        """Update the gauge with the given value."""
        self._j_gauge.update(value)

    @override
    def get_value(self) -> float:
        """Return the current value of the gauge."""
        return self._j_gauge.getValue()
