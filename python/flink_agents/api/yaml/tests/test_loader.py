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
from pathlib import Path

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import MessageRole
from flink_agents.api.events.chat_event import ChatResponseEvent
from flink_agents.api.events.event import InputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.function import JavaFunction, PythonFunction
from flink_agents.api.prompts.prompt import LocalPrompt
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.api.skills import Skills, SkillSourceSpec
from flink_agents.api.tools.function_tool import FunctionTool
from flink_agents.api.yaml.loader import (
    _build_skills,
    build_agents,
    load_yaml,
    resolve_function,
)
from flink_agents.api.yaml.specs import SkillsSpec
from flink_agents.api.yaml.tests.fixtures import loader_targets

_FIXTURES = Path(__file__).parent / "fixtures"

_TARGETS_MODULE = "flink_agents.api.yaml.tests.fixtures.loader_targets"


def test_resolve_function_python_with_module_attr() -> None:
    func = resolve_function(
        name="anything", function=f"{_TARGETS_MODULE}:increment"
    )
    assert isinstance(func, PythonFunction)
    assert func.module == _TARGETS_MODULE
    assert func.qualname == "increment"
    # still callable
    assert func.as_callable() is loader_targets.increment


def test_resolve_function_python_with_class_method() -> None:
    # ``module:Class.method`` — the right side becomes
    # ``PythonFunction.qualname`` verbatim and ``as_callable`` does the
    # ``Class.method`` split internally.
    func = resolve_function(
        name="bump", function=f"{_TARGETS_MODULE}:Counter.bump"
    )
    assert isinstance(func, PythonFunction)
    assert func.module == _TARGETS_MODULE
    assert func.qualname == "Counter.bump"
    assert func.as_callable() is loader_targets.Counter.bump


def test_resolve_function_no_function_fails() -> None:
    with pytest.raises(ValueError, match="'function' is required"):
        resolve_function(name="x", function=None)


def test_resolve_function_missing_colon_fails() -> None:
    # The dotted form used to be valid; under the new ``:`` syntax it
    # must be rejected so the user gets a clear "use module:qualname"
    # hint instead of a deep import failure.
    with pytest.raises(ValueError, match="module-or-class.:.qualname"):
        resolve_function(name="x", function=f"{_TARGETS_MODULE}.increment")


def test_resolve_function_multiple_colons_fails() -> None:
    with pytest.raises(ValueError, match="module-or-class.:.qualname"):
        resolve_function(name="x", function="a:b:c")


def test_resolve_function_empty_module_fails() -> None:
    with pytest.raises(ValueError, match="module-or-class.:.qualname"):
        resolve_function(name="x", function=":increment")


def test_resolve_function_empty_qualname_fails() -> None:
    with pytest.raises(ValueError, match="module-or-class.:.qualname"):
        resolve_function(name="x", function=f"{_TARGETS_MODULE}:")


def test_resolve_function_missing_target_raises_importerror() -> None:
    # PythonFunction loads lazily; trigger the import via as_callable().
    func = resolve_function(
        name="x",
        function=f"{_TARGETS_MODULE}:does_not_exist",
    )
    with pytest.raises((ImportError, AttributeError)):
        func.as_callable()


def test_build_agents_rejects_duplicate_agent_within_file(tmp_path: Path) -> None:
    yaml_text = (
        "agents:\n"
        "  - name: dup\n"
        "    actions:\n"
        "      - name: increment\n"
        f"        function: {_TARGETS_MODULE}:increment\n"
        "        trigger_conditions: [input]\n"
        "  - name: dup\n"
        "    actions:\n"
        "      - name: decrement\n"
        f"        function: {_TARGETS_MODULE}:decrement\n"
        "        trigger_conditions: [input]\n"
    )
    p = tmp_path / "dup.yaml"
    p.write_text(yaml_text)
    with pytest.raises(ValueError, match="dup"):
        build_agents(p)


def test_build_agents_from_single_agent_yaml() -> None:
    agents, shared_resources, shared_actions = build_agents(
        _FIXTURES / "single_agent.yaml"
    )
    assert list(agents) == ["incrementer"]
    agent = agents["incrementer"]
    assert isinstance(agent, Agent)
    assert "increment" in agent.actions
    events, func, config = agent.actions["increment"]
    assert events == [InputEvent.EVENT_TYPE]
    assert isinstance(func, PythonFunction)
    assert func.qualname == "increment"
    assert config is None
    assert shared_resources == {t: {} for t in shared_resources}
    assert shared_actions == {}


def test_build_agents_resolves_event_alias_and_clazz_alias() -> None:
    agents, _, _ = build_agents(_FIXTURES / "with_descriptors.yaml")
    agent = agents["chat_agent"]

    inc_events, _, _ = agent.actions["increment"]
    dec_events, _, _ = agent.actions["decrement"]
    assert inc_events == [InputEvent.EVENT_TYPE]
    assert dec_events == [ChatResponseEvent.EVENT_TYPE]

    conn = agent.resources[ResourceType.CHAT_MODEL_CONNECTION]["ollama_conn"]
    assert isinstance(conn, ResourceDescriptor)
    expected_module, _, expected_class = (
        ResourceName.ChatModel.OLLAMA_CONNECTION.rpartition(".")
    )
    assert conn.target_module == expected_module
    assert conn.target_clazz == expected_class
    assert conn.arguments == {
        "base_url": "http://localhost:11434",
        "request_timeout": 30,
    }


def test_build_agents_loads_tools_and_prompts() -> None:
    agents, _, _ = build_agents(_FIXTURES / "with_tools_and_prompts.yaml")
    agent = agents["tool_agent"]

    tool = agent.resources[ResourceType.TOOL]["notify"]
    assert isinstance(tool, FunctionTool)
    assert isinstance(tool.func, PythonFunction)
    assert tool.func.qualname == "notify"

    text_prompt = agent.resources[ResourceType.PROMPT]["text_prompt"]
    assert isinstance(text_prompt, LocalPrompt)
    assert text_prompt.template == "hello {name}"

    msg_prompt = agent.resources[ResourceType.PROMPT]["messages_prompt"]
    assert isinstance(msg_prompt, LocalPrompt)
    assert len(msg_prompt.template) == 2
    assert msg_prompt.template[0].role == MessageRole.SYSTEM
    assert msg_prompt.template[1].content == "{q}"


def test_build_agents_handles_shared_resources_and_actions() -> None:
    agents, shared_resources, shared_actions = build_agents(
        _FIXTURES / "with_shared.yaml"
    )

    # shared resources surfaced to caller
    assert "shared_conn" in shared_resources[ResourceType.CHAT_MODEL_CONNECTION]
    # shared actions stored as ActionSpec for cross-agent reference resolution
    assert "shared_inc" in shared_actions

    # both a1 and a2 own a copy of shared_inc after caller-side merge?
    # NO — build_agents only handles in-file. The merge happens in load_yaml.
    # Here we assert build_agents leaves string refs *unresolved* for the caller:
    a1 = agents["a1"]
    a2 = agents["a2"]
    assert "shared_inc" not in a1.actions  # not yet merged in
    assert "own_dec" in a1.actions
    assert "shared_inc" not in a2.actions


def test_load_yaml_registers_single_agent_on_env() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    load_yaml(env, _FIXTURES / "single_agent.yaml")
    assert "incrementer" in env._agents


def test_load_yaml_registers_multiple_agents() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    load_yaml(env, _FIXTURES / "multi_agent.yaml")
    assert set(env._agents.keys()) == {"a1", "a2"}


def test_load_yaml_merges_shared_action_into_agents() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    load_yaml(env, _FIXTURES / "with_shared.yaml")
    a1 = env._agents["a1"]
    a2 = env._agents["a2"]
    assert "shared_inc" in a1.actions
    assert "shared_inc" in a2.actions
    events_a1, func_a1, _ = a1.actions["shared_inc"]
    events_a2, func_a2, _ = a2.actions["shared_inc"]
    assert events_a1 == [InputEvent.EVENT_TYPE]
    assert events_a2 == [InputEvent.EVENT_TYPE]
    assert isinstance(func_a1, PythonFunction)
    assert func_a1.qualname == "increment"
    assert isinstance(func_a2, PythonFunction)
    assert func_a2.qualname == "increment"


def test_load_yaml_registers_shared_resources_on_env() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    load_yaml(env, _FIXTURES / "with_shared.yaml")
    assert "shared_conn" in env.resources[ResourceType.CHAT_MODEL_CONNECTION]


def test_load_yaml_string_ref_to_missing_shared_action_errors(tmp_path: Path) -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    bad = tmp_path / "bad_missing_shared_action.yaml"
    bad.write_text("agents:\n  - name: a\n    actions:\n      - undefined_action\n")
    with pytest.raises(ValueError, match="undefined_action"):
        load_yaml(env, bad)


def test_load_yaml_multi_call_merges() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    load_yaml(env, _FIXTURES / "multi_file_a.yaml")
    load_yaml(env, _FIXTURES / "multi_file_b.yaml")
    assert {"file_a_agent", "file_b_agent"} <= set(env._agents.keys())
    assert "conn_from_a" in env.resources[ResourceType.CHAT_MODEL_CONNECTION]
    assert "conn_from_b" in env.resources[ResourceType.CHAT_MODEL_CONNECTION]


def test_load_yaml_accepts_list_of_paths() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    load_yaml(env, [_FIXTURES / "multi_file_a.yaml", _FIXTURES / "multi_file_b.yaml"])
    assert {"file_a_agent", "file_b_agent"} <= set(env._agents.keys())


def test_load_yaml_duplicate_agent_across_calls_errors() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    load_yaml(env, _FIXTURES / "multi_file_a.yaml")
    with pytest.raises(ValueError, match="file_a_agent"):
        load_yaml(env, _FIXTURES / "multi_file_a.yaml")


def test_load_yaml_duplicate_shared_resource_within_file_errors(tmp_path) -> None:
    # In-file duplicate detection used to differ between ``build_agents``
    # (raise) and ``load_yaml`` (silent last-wins). Both entrypoints now
    # go through the same builder, so ``load_yaml`` rejects too.
    bad = tmp_path / "dup_in_file.yaml"
    bad.write_text(
        "agents:\n"
        "  - name: a\n"
        "chat_model_connections:\n"
        "  - name: conn\n"
        "    clazz: x.Y\n"
        "  - name: conn\n"
        "    clazz: x.Z\n"
    )
    env = AgentsExecutionEnvironment.get_execution_environment()
    with pytest.raises(ValueError, match="Duplicate shared resource name 'conn'"):
        load_yaml(env, bad)


def test_load_yaml_duplicate_shared_action_within_file_errors(tmp_path) -> None:
    bad = tmp_path / "dup_action_in_file.yaml"
    bad.write_text(
        "agents:\n"
        "  - name: a\n"
        "actions:\n"
        "  - name: shared\n"
        "    trigger_conditions: [input]\n"
        "  - name: shared\n"
        "    trigger_conditions: [input]\n"
    )
    env = AgentsExecutionEnvironment.get_execution_environment()
    with pytest.raises(ValueError, match="Duplicate shared action name 'shared'"):
        load_yaml(env, bad)


def test_load_yaml_duplicate_shared_resource_across_calls_errors(tmp_path) -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    load_yaml(env, _FIXTURES / "multi_file_a.yaml")
    dup = tmp_path / "dup.yaml"
    dup.write_text(
        "agents:\n  - name: other\n"
        "chat_model_connections:\n"
        "  - name: conn_from_a\n"
        "    clazz: ollama\n"
    )
    with pytest.raises(ValueError, match="conn_from_a"):
        load_yaml(env, dup)


def test_apply_by_agent_name_runs_yaml_loaded_agent() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    load_yaml(env, _FIXTURES / "single_agent.yaml")

    input_list = []
    output_list = env.from_list(input_list).apply("incrementer").to_list()
    input_list.append({"key": "bob", "value": 1})
    input_list.append({"key": "john", "value": 2})
    env.execute()
    assert output_list == [{"bob": 2}, {"john": 3}]


def test_apply_by_unknown_name_errors() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    with pytest.raises(ValueError, match="ghost"):
        env.from_list([]).apply("ghost")


def test_build_agents_loads_skills_per_agent_and_shared() -> None:
    agents, shared_resources, _ = build_agents(_FIXTURES / "with_skills.yaml")
    agent = agents["skills_agent"]

    own = agent.resources[ResourceType.SKILLS]["agent_skills"]
    assert isinstance(own, Skills)
    assert own.sources == [
        SkillSourceSpec(scheme="local", params={"path": "./agent_skill_dir"})
    ]

    shared = shared_resources[ResourceType.SKILLS]["shared_skills"]
    assert isinstance(shared, Skills)
    assert shared.sources == [
        SkillSourceSpec(scheme="local", params={"path": "./shared_skill_dir"}),
        SkillSourceSpec(scheme="local", params={"path": "./more"}),
    ]


def test_build_skills_merges_all_schemes() -> None:
    spec = SkillsSpec.model_validate(
        {
            "name": "s",
            "paths": ["./a"],
            "urls": ["https://x/s.zip"],
            "classpath": ["com/example/s"],
            "package": [{"package": "my_pkg", "resource": "skills/"}],
        }
    )
    skills = _build_skills(spec)
    assert skills.sources == [
        SkillSourceSpec(scheme="local", params={"path": "./a"}),
        SkillSourceSpec(scheme="url", params={"url": "https://x/s.zip"}),
        SkillSourceSpec(scheme="classpath", params={"resource": "com/example/s"}),
        SkillSourceSpec(
            scheme="package", params={"package": "my_pkg", "resource": "skills/"}
        ),
    ]


def test_load_yaml_registers_shared_skills_on_env() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()
    load_yaml(env, _FIXTURES / "with_skills.yaml")
    shared = env.resources[ResourceType.SKILLS]["shared_skills"]
    assert isinstance(shared, Skills)
    assert shared.sources == [
        SkillSourceSpec(scheme="local", params={"path": "./shared_skill_dir"}),
        SkillSourceSpec(scheme="local", params={"path": "./more"}),
    ]


def test_build_agents_supports_type_java(tmp_path: Path) -> None:
    yaml_text = (
        "agents:\n"
        "  - name: a\n"
        "    chat_model_connections:\n"
        "      - name: java_conn\n"
        "        type: java\n"
        "        clazz: ollama\n"
        "        endpoint: http://localhost:11434\n"
        "        requestTimeout: 120\n"
    )
    p = tmp_path / "java_resource.yaml"
    p.write_text(yaml_text)
    agents, _, _ = build_agents(p)
    agent = agents["a"]

    conn = agent.resources[ResourceType.CHAT_MODEL_CONNECTION]["java_conn"]
    # clazz is the Python-side Java wrapper
    assert conn.target_clazz == "JavaChatModelConnection"
    # java_clazz arg points at the Java implementation
    assert (
        conn.arguments["java_clazz"]
        == "org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelConnection"
    )
    # other kwargs flow through
    assert conn.arguments["endpoint"] == "http://localhost:11434"
    assert conn.arguments["requestTimeout"] == 120


def test_build_agents_rejects_type_java_for_unsupported_resource(
    tmp_path: Path,
) -> None:
    # MCP_SERVER has no Python-side Java wrapper, so type=java must error.
    yaml_text = (
        "agents:\n"
        "  - name: a\n"
        "    mcp_servers:\n"
        "      - name: x\n"
        "        type: java\n"
        "        clazz: anything\n"
    )
    p = tmp_path / "bad_java.yaml"
    p.write_text(yaml_text)
    with pytest.raises(ValueError, match="java"):
        build_agents(p)


def test_clazz_alias_resolves_per_section(tmp_path: Path) -> None:
    yaml_text = (
        "agents:\n"
        "  - name: a\n"
        "    chat_model_connections:\n"
        "      - name: conn\n"
        "        clazz: ollama\n"
        "        base_url: http://x\n"
        "    chat_model_setups:\n"
        "      - name: setup\n"
        "        clazz: ollama\n"
        "        connection: conn\n"
        "    embedding_model_connections:\n"
        "      - name: e_conn\n"
        "        clazz: ollama\n"
        "        base_url: http://y\n"
    )
    p = tmp_path / "per_section.yaml"
    p.write_text(yaml_text)
    agents, _, _ = build_agents(p)
    agent = agents["a"]

    conn = agent.resources[ResourceType.CHAT_MODEL_CONNECTION]["conn"]
    setup = agent.resources[ResourceType.CHAT_MODEL]["setup"]
    e_conn = agent.resources[ResourceType.EMBEDDING_MODEL_CONNECTION]["e_conn"]
    assert conn.target_clazz == "OllamaChatModelConnection"
    assert setup.target_clazz == "OllamaChatModelSetup"
    assert e_conn.target_clazz == "OllamaEmbeddingModelConnection"


def test_resolve_function_builds_java_function_for_java_language() -> None:
    func = resolve_function(
        name="firstAction",
        function="com.example.MyAgent:firstAction",
        language="java",
        parameter_types=[
            "org.apache.flink.agents.api.Event",
            "org.apache.flink.agents.api.context.RunnerContext",
        ],
    )
    assert isinstance(func, JavaFunction)
    assert func.qualname == "com.example.MyAgent"
    assert func.method_name == "firstAction"
    assert func.parameter_types == [
        "org.apache.flink.agents.api.Event",
        "org.apache.flink.agents.api.context.RunnerContext",
    ]


def test_resolve_function_java_supports_inner_classes() -> None:
    func = resolve_function(
        name="m",
        function="com.example.Outer$Inner:m",
        language="java",
        parameter_types=[],
    )
    assert isinstance(func, JavaFunction)
    assert func.qualname == "com.example.Outer$Inner"
    assert func.method_name == "m"


def test_resolve_function_python_is_default_language() -> None:
    func1 = resolve_function(
        name="x", function=f"{_TARGETS_MODULE}:increment"
    )
    func2 = resolve_function(
        name="x",
        function=f"{_TARGETS_MODULE}:increment",
        language="python",
    )
    assert isinstance(func1, PythonFunction)
    assert isinstance(func2, PythonFunction)
    assert func1.module == func2.module
    assert func1.qualname == func2.qualname


def test_build_agents_action_func_is_python_function() -> None:
    agents, _, _ = build_agents(_FIXTURES / "single_agent.yaml")
    agent = agents["incrementer"]
    events, func, _ = agent.actions["increment"]
    assert isinstance(func, PythonFunction)
    assert func.qualname == "increment"


def test_build_agents_builds_java_action(tmp_path: Path) -> None:
    yaml_text = (
        "agents:\n"
        "  - name: a\n"
        "    actions:\n"
        "      - name: a1\n"
        "        type: java\n"
        "        function: com.example.MyAgent:handle\n"
        "        trigger_conditions: [input]\n"
    )
    p = tmp_path / "java_action.yaml"
    p.write_text(yaml_text)
    agents, _, _ = build_agents(p)
    agent = agents["a"]
    _, func, _ = agent.actions["a1"]
    assert isinstance(func, JavaFunction)
    assert func.qualname == "com.example.MyAgent"
    assert func.method_name == "handle"
    assert func.parameter_types == [
        "org.apache.flink.agents.api.Event",
        "org.apache.flink.agents.api.context.RunnerContext",
    ]


def test_build_agents_rejects_java_tool_missing_parameter_types(
    tmp_path: Path,
) -> None:
    yaml_text = (
        "agents:\n"
        "  - name: a\n"
        "    tools:\n"
        "      - name: t1\n"
        "        type: java\n"
        "        function: com.example.Tools:add\n"
        "    actions:\n"
        "      - name: noop\n"
        f"        function: {_TARGETS_MODULE}:increment\n"
        "        trigger_conditions: [input]\n"
    )
    p = tmp_path / "java_tool_no_params.yaml"
    p.write_text(yaml_text)
    with pytest.raises(ValueError, match="parameter_types"):
        build_agents(p)


def test_build_agents_builds_java_tool_descriptor(tmp_path: Path) -> None:
    """YAML parsing of a Java tool yields an api ``FunctionTool`` wrapping
    a ``JavaFunction`` descriptor — no JVM needed at parse time.

    Metadata extraction (via py4j on the plan side) is wired up later;
    see ``flink_agents.plan.tools.function_tool.FunctionTool.metadata``
    which currently raises ``NotImplementedError`` for Java tools.
    """
    yaml_text = (
        "agents:\n"
        "  - name: a\n"
        "    tools:\n"
        "      - name: add\n"
        "        type: java\n"
        "        function: com.example.Tools:add\n"
        "        parameter_types: [int, int]\n"
        "    actions:\n"
        "      - name: noop\n"
        f"        function: {_TARGETS_MODULE}:increment\n"
        "        trigger_conditions: [input]\n"
    )
    p = tmp_path / "java_tool.yaml"
    p.write_text(yaml_text)
    agents, _, _ = build_agents(p)
    agent = agents["a"]

    tool = agent.resources[ResourceType.TOOL]["add"]
    assert isinstance(tool, FunctionTool)
    assert isinstance(tool.func, JavaFunction)
    assert tool.func.qualname == "com.example.Tools"
    assert tool.func.method_name == "add"
    assert tool.func.parameter_types == ["int", "int"]
