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
import re
import threading

from flink_agents.runtime.flink_runner_context import (
    close_async_thread_pool,
    create_async_thread_pool,
)


def test_async_workers_carry_descriptive_names() -> None:
    pool = create_async_thread_pool(2)
    try:
        name = pool.submit(lambda: threading.current_thread().name).result()
        # ThreadPoolExecutor appends _<worker-id> to the prefix.
        assert re.fullmatch(r"flink-agents-python-async-\d+_\d+", name), name
    finally:
        close_async_thread_pool(pool)


def test_pool_prefixes_distinct_across_instances() -> None:
    first = create_async_thread_pool(1)
    second = create_async_thread_pool(1)
    try:
        first_name = first.submit(lambda: threading.current_thread().name).result()
        second_name = second.submit(lambda: threading.current_thread().name).result()
        assert first_name.rsplit("_", 1)[0] != second_name.rsplit("_", 1)[0]
    finally:
        close_async_thread_pool(first)
        close_async_thread_pool(second)
