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

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.memory.long_term_memory import MemorySet, SummarizationStrategy


def test_memory_set_serialization() -> None:  # noqa:D103
    memory_set = MemorySet(
        name="chat_history",
        item_type=ChatMessage,
        capacity=100,
        compaction_strategy=SummarizationStrategy(model="llm"),
    )

    json_data = memory_set.model_dump_json()

    memory_set_deserialized = MemorySet.model_validate_json(json_data)

    assert memory_set_deserialized == memory_set
