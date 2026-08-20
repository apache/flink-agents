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
################################################################################
"""Python mirror of the Java ``TaskLifecycleListener``."""

from typing import Any


class TaskLifecycleListener:
    """Observes the per-record and per-task lifecycle of the action operator.

    Mirrors the Java ``TaskLifecycleListener``: the operator drives Python
    actions on the JVM and forwards each lifecycle callback here over pemja.
    Every callback defaults to a no-op, so an implementation overrides only
    the ones it cares about.
    """

    def on_record_start(self, key: Any) -> None:
        """The first task of an input record is about to be prepared."""

    def on_task_prepared(self, task: Any) -> None:
        """A task's context is wired up and it is ready to run."""

    def on_task_transferred(self, from_task: Any, to_task: Any) -> None:
        """A non-finished task handed its context to the task it generated."""

    def on_task_finished(self, task: Any) -> None:
        """A task reached its terminal state."""

    def on_record_finished(self, key: Any) -> None:
        """Every task spawned by an input record has completed."""
