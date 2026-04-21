
from flink_agents.plan.actions.chat_model_action import _clean_llm_response


def test_clean_llm_response_with_json_block():
    input_str = "```json\n{\"key\": \"value\"}\n```"
    expected = "{\"key\": \"value\"}"
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_generic_code_block():
    input_str = "```\n{\"key\": \"value\"}\n```"
    expected = "{\"key\": \"value\"}"
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_whitespace():
    input_str = "  ```json\n{\"key\": \"value\"}\n```  "
    expected = "{\"key\": \"value\"}"
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_without_block():
    input_str = "{\"key\": \"value\"}"
    expected = "{\"key\": \"value\"}"
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_text_around():
    input_str = "Here is the json: ```json {\"key\": \"value\"} ```"
    expected = "Here is the json: ```json {\"key\": \"value\"} ```"
    assert _clean_llm_response(input_str) == expected


def test_clean_llm_response_with_multiple_lines_in_block():
    input_str = "```json\n{\n  \"key\": \"value\"\n}\n```"
    expected = "{\n  \"key\": \"value\"\n}"
    assert _clean_llm_response(input_str) == expected
