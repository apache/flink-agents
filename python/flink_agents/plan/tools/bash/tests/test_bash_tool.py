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
# limitations under the License.
################################################################################
"""Tests for BashTool and its AST-based command validation."""

from pathlib import Path

import pytest

from flink_agents.plan.tools.bash.bash_tool import BashTool
from flink_agents.plan.tools.bash.bash_validator import validate_command


@pytest.fixture(scope="module")
def script_dir(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """An allowed-script directory containing a small runnable script."""
    root = tmp_path_factory.mktemp("allowed_scripts")
    scripts = root / "scripts"
    scripts.mkdir()
    script = scripts / "hello.py"
    script.write_text("print('ok')\n")
    return root.resolve()


class TestValidateCommand:
    """Tests for the tree-sitter-backed `validate_command` helper."""

    # -- allowlist basics --------------------------------------------------

    def test_allow_whitelisted_command(self) -> None:
        assert validate_command("gh issue list", ["gh", "git"], []) is None

    def test_reject_command_not_in_whitelist(self) -> None:
        error = validate_command("rm -rf /", ["gh", "git"], [])
        assert error is not None
        assert "not allowed" in error

    def test_empty_allowed_commands_rejects(self) -> None:
        assert validate_command("gh issue list", [], []) is not None

    def test_reject_empty_command(self) -> None:
        assert validate_command("", ["gh"], []) is not None
        assert validate_command("   ", ["gh"], []) is not None

    # -- script dirs -------------------------------------------------------

    def test_allow_script_under_allowed_dir(self, script_dir: Path) -> None:
        script = script_dir / "scripts" / "hello.py"
        assert validate_command(f"{script} --flag", [], [str(script_dir)]) is None

    def test_reject_script_outside_allowed_dirs(self, script_dir: Path) -> None:
        assert validate_command("/tmp/unknown.py", [], [str(script_dir)]) is not None

    # -- pipes, &&, ||, ; — each sub-command validated --------------------

    def test_allow_pipe_all_whitelisted(self) -> None:
        assert validate_command("gh issue list | git log", ["gh", "git"], []) is None

    def test_reject_pipe_one_not_whitelisted(self) -> None:
        error = validate_command("gh issue list | grep bug", ["gh", "git"], [])
        assert error is not None
        assert "grep" in error

    def test_reject_and_one_not_whitelisted(self) -> None:
        error = validate_command("gh issue list && rm -rf /", ["gh", "git"], [])
        assert error is not None
        assert "rm" in error

    def test_reject_or_one_not_whitelisted(self) -> None:
        error = validate_command("gh issue list || rm -rf /", ["gh", "git"], [])
        assert error is not None
        assert "rm" in error

    def test_reject_semicolon_one_not_whitelisted(self) -> None:
        assert (
            validate_command("gh issue list; rm -rf /", ["gh", "git"], []) is not None
        )

    def test_reject_newline_one_not_whitelisted(self) -> None:
        assert (
            validate_command("gh issue list\nrm -rf /", ["gh", "git"], []) is not None
        )

    # -- quoting and escaping ---------------------------------------------

    def test_allow_operators_inside_single_quotes(self) -> None:
        assert (
            validate_command("gh issue list --label 'bug|feature'", ["gh"], []) is None
        )

    def test_allow_operators_inside_double_quotes(self) -> None:
        assert validate_command('gh issue list --label "a&&b"', ["gh"], []) is None

    def test_allow_escaped_operator(self) -> None:
        assert validate_command("gh issue list --query a\\|b", ["gh"], []) is None

    # -- env var prefix inside a command ----------------------------------

    def test_allow_env_prefix(self) -> None:
        assert validate_command("FOO=bar echo hi", ["echo"], []) is None

    def test_reject_env_prefix_command_not_whitelisted(self) -> None:
        assert validate_command("FOO=bar rm -rf /", ["echo"], []) is not None

    # -- injection vectors: MUST be rejected ------------------------------

    def test_reject_dollar_paren_substitution(self) -> None:
        error = validate_command("echo $(rm /)", ["echo"], [])
        assert error is not None
        assert "command_substitution" in error

    def test_reject_backtick_substitution(self) -> None:
        error = validate_command("echo `rm /`", ["echo"], [])
        assert error is not None
        assert "command_substitution" in error

    def test_reject_substitution_in_double_quotes(self) -> None:
        error = validate_command('echo "$(rm /)"', ["echo"], [])
        assert error is not None
        assert "command_substitution" in error

    def test_reject_substitution_via_default_expansion(self) -> None:
        error = validate_command("echo ${FOO:-$(rm /)}", ["echo"], [])
        assert error is not None
        assert "command_substitution" in error

    def test_reject_process_substitution(self) -> None:
        error = validate_command("cat <(true)", ["cat"], [])
        assert error is not None
        assert "process_substitution" in error

    def test_reject_subshell(self) -> None:
        error = validate_command("(rm /)", ["rm"], [])
        assert error is not None
        assert "subshell" in error

    def test_reject_for_loop(self) -> None:
        error = validate_command("for f in *; do echo $f; done", ["echo"], [])
        assert error is not None
        assert "for_statement" in error

    def test_reject_while_loop(self) -> None:
        error = validate_command("while true; do echo x; done", ["echo"], [])
        assert error is not None

    def test_reject_if_statement(self) -> None:
        error = validate_command("if true; then echo hi; fi", ["echo"], [])
        assert error is not None

    def test_reject_function_definition(self) -> None:
        error = validate_command("f() { echo hi; }", ["echo"], [])
        assert error is not None

    def test_reject_heredoc(self) -> None:
        error = validate_command("cat <<EOF\n$(rm /)\nEOF", ["cat"], [])
        assert error is not None

    # -- still allow common safe features ---------------------------------

    def test_allow_simple_expansion(self) -> None:
        assert validate_command("echo $USER", ["echo"], []) is None

    def test_allow_brace_expansion(self) -> None:
        assert validate_command("echo {a,b,c}", ["echo"], []) is None

    def test_allow_redirect_to_file(self) -> None:
        assert validate_command("echo hi > /tmp/out", ["echo"], []) is None

    def test_allow_stderr_redirect(self) -> None:
        assert validate_command("echo hi 2>&1", ["echo"], []) is None


class TestBashTool:
    """Integration tests for BashTool.call."""

    @pytest.fixture
    def tool(self) -> BashTool:
        return BashTool()

    def test_execute_whitelisted_command(self, tool: BashTool) -> None:
        result = tool.call(
            command="echo hello",
            timeout=10,
            allowed_commands=["echo"],
            allowed_script_dirs=[],
        )
        assert result == "hello"

    def test_execute_piped_whitelisted_commands(self, tool: BashTool) -> None:
        result = tool.call(
            command='echo "(2 ^ 3)" | bc',
            timeout=10,
            allowed_commands=["echo", "bc"],
            allowed_script_dirs=[],
        )
        assert result == "8"

    def test_reject_non_whitelisted_command(self, tool: BashTool) -> None:
        result = tool.call(
            command="rm -rf /",
            timeout=10,
            allowed_commands=["echo"],
            allowed_script_dirs=[],
        )
        assert "Command rejected" in result

    def test_reject_piped_non_whitelisted(self, tool: BashTool) -> None:
        result = tool.call(
            command="echo hello | cat",
            timeout=10,
            allowed_commands=["echo"],
            allowed_script_dirs=[],
        )
        assert "Command rejected" in result

    def test_reject_command_substitution(self, tool: BashTool) -> None:
        """$(...) must be rejected even when outer command is allowed."""
        result = tool.call(
            command="echo $(touch /tmp/injected)",
            timeout=10,
            allowed_commands=["echo"],
            allowed_script_dirs=[],
        )
        assert "Command rejected" in result

    def test_no_allowed_commands_rejects_everything(self, tool: BashTool) -> None:
        result = tool.call(command="echo hello", timeout=10)
        assert "Command rejected" in result

    # -- cwd parameter -----------------------------------------------------

    def test_cwd_enables_relative_script_path(
        self, tool: BashTool, script_dir: Path
    ) -> None:
        """With cwd set to an allowed dir, a relative script path resolves under it."""
        result = tool.call(
            command="python3 scripts/hello.py",
            timeout=10,
            cwd=str(script_dir),
            allowed_commands=[],
            allowed_script_dirs=[str(script_dir)],
        )
        assert "Command rejected" not in result
        assert "ok" in result

    def test_cwd_outside_allowed_dirs_rejected(
        self, tool: BashTool, script_dir: Path
    ) -> None:
        result = tool.call(
            command="echo hello",
            timeout=10,
            cwd="/tmp",
            allowed_commands=["echo"],
            allowed_script_dirs=[str(script_dir)],
        )
        assert "Command rejected" in result
        assert "cwd" in result

    def test_relative_path_without_cwd_rejected(
        self, tool: BashTool, script_dir: Path
    ) -> None:
        """Bare relative script path (no cwd) is rejected."""
        result = tool.call(
            command="python3 scripts/hello.py",
            timeout=10,
            allowed_commands=[],
            allowed_script_dirs=[str(script_dir)],
        )
        assert "Command rejected" in result
