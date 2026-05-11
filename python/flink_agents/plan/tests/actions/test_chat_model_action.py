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
from flink_agents.plan.actions.chat_model_action import _clean_llm_response


def test_clean_llm_response_with_json_block():
    input_str = '```json\n{"key": "value"}\n```'
    expected = '{"key": "value"}'
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_generic_code_block():
    input_str = '```\n{"key": "value"}\n```'
    expected = '{"key": "value"}'
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_whitespace():
    input_str = '  ```json\n{"key": "value"}\n```  '
    expected = '{"key": "value"}'
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_without_block():
    input_str = '{"key": "value"}'
    expected = '{"key": "value"}'
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_text_around():
    input_str = 'Here is the json: ```json {"key": "value"} ```'
    expected = 'Here is the json: ```json {"key": "value"} ```'
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_multiple_lines_in_block():
    input_str = '```json\n{\n  "key": "value"\n}\n```'
    expected = '{\n  "key": "value"\n}'
    assert _clean_llm_response(input_str) == expected
