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
from abc import ABC, abstractmethod


class MetricGroup(ABC):
    """Abstract base class providing metric group for action execution.

    This metric group offers access to metrics.
    """

    @abstractmethod
    def get_sub_group(self, name: str) -> "MetricGroup":
        """Create or retrieve a sub-metric group with the given name.

        Parameters
        ----------
        name : str
            The name of the sub metric group.
        """

    @abstractmethod
    def get_counter(self, name: str) -> "Counter":
        """Create or retrieve a counter with the given name.

        Parameters
        ----------
        name : str
            The name of the counter.
        """

    @abstractmethod
    def get_meter(self, name: str) -> "Meter":
        """Create or retrieve a meter with the given name.

        Parameters
        ----------
        name : str
            The name of the meter.
        """

    @abstractmethod
    def get_gauge(self, name: str) -> "Gauge":
        """Create or retrieve a gauge with the given name.

        Parameters
        ----------
        name : str
            The name of the gauge.
        """

    @abstractmethod
    def get_histogram(self, name: str, window_size: int = 100) -> "Histogram":
        """Create or retrieve a histogram with the given name and window size.

        Parameters
        ----------
        name : str
            The name of the histogram.
        window_size : int, optional
            The sliding window size for histogram statistics.
        """


class Counter(ABC):
    """A Counter that measures a count."""

    @abstractmethod
    def inc(self, n: int = 1) -> None:
        """Increment the current count by the given value."""

    @abstractmethod
    def dec(self, n: int = 1) -> None:
        """Decrement the current count by the given value."""

    @abstractmethod
    def get_count(self) -> int:
        """Return the current count."""


class Meter(ABC):
    """Metric for measuring throughput."""

    @abstractmethod
    def mark(self, n: int =1) -> None:
        """Trigger the meter by the given value."""

    @abstractmethod
    def get_rate(self) -> float:
        """Return the current event rate per second."""


class Histogram(ABC):
    """The histogram allows to record values and create histogram statistics
    for the currently seen elements.
    """

    @abstractmethod
    def update(self, value: int) -> None:
        """Update the histogram with the given value."""

    @abstractmethod
    def get_mean(self) -> float:
        """Return the average value."""

    @abstractmethod
    def get_max(self) -> int:
        """Return the maximum recorded value."""

    @abstractmethod
    def get_min(self) -> int:
        """Return the minimum recorded value."""


class Gauge(ABC):
    """A gauge metric that returns a value by invoking a function."""

    @abstractmethod
    def update(self, value: str) -> None:
        """Update the current value of the gauge."""

    @abstractmethod
    def get_value(self) -> str:
        """Return the current value of the gauge."""
