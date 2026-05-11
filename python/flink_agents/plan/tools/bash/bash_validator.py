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
"""AST-based bash command validation using tree-sitter-bash.

Parses the command with tree-sitter-bash and walks the AST. Any named node
whose type is not in the allowed set (e.g. ``command_substitution``,
``process_substitution``, ``subshell``, ``for_statement``) causes the whole
command to be rejected. Every ``command`` node's name is checked against the
``allowed_commands`` allowlist or resolved under ``allowed_script_dirs``.

This lets the tool accept natural shell constructs like pipes, ``&&`` / ``||``
chains and simple redirections while blocking common injection vectors (``$()``,
backticks, heredoc bodies containing substitutions, control flow, etc.).
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import TYPE_CHECKING, List

if TYPE_CHECKING:
    from tree_sitter import Node, Parser


# Named AST node types we accept. Anything named but missing is treated as a
# potentially dangerous shell construct and rejected. Unnamed nodes (literal
# punctuation like ``|``, ``&&``, ``(``, ``$(``) are always allowed — they're
# just syntax tokens, not semantic structures.
_ALLOWED_NAMED = frozenset(
    {
        "program",
        "command",
        "command_name",
        # `export VAR=...`, `readonly`, `declare`, `local`, `typeset`
        "declaration_command",
        "pipeline",
        "list",
        "redirected_statement",
        "file_redirect",
        "file_descriptor",
        "variable_assignment",
        "variable_name",
        "special_variable_name",  # $@ $? $* $#
        "word",
        "string",
        "string_content",
        "raw_string",
        "ansi_c_string",
        "translated_string",
        "concatenation",
        "number",
        "simple_expansion",  # $VAR
        "expansion",  # ${VAR}
        "arithmetic_expansion",  # $((...))
        "binary_expression",
        "unary_expression",
        "parenthesized_expression",
        "array",
    }
)


@lru_cache(maxsize=1)
def _get_parser() -> Parser:
    """Return a cached tree-sitter parser configured with the bash grammar."""
    import tree_sitter_bash
    from tree_sitter import Language, Parser

    return Parser(Language(tree_sitter_bash.language()))


def validate_command(
    command: str,
    allowed_commands: List[str],
    allowed_script_dirs: List[str],
    cwd: str | None = None,
) -> str | None:
    """Validate a bash command.

    Returns ``None`` if the command is allowed, or an error string otherwise.
    """
    if not command.strip():
        return "Empty command."

    try:
        tree = _get_parser().parse(command.encode("utf-8"))
    except Exception as exc:
        return f"Failed to parse command: {exc}"

    root = tree.root_node
    if root.has_error:
        return "Command has syntax errors."
    if not root.children:
        return "Empty command."

    return _walk(root, allowed_commands, allowed_script_dirs, cwd)


def _walk(
    node: Node,
    allowed_commands: List[str],
    allowed_script_dirs: List[str],
    cwd: str | None,
) -> str | None:
    if node.is_named and node.type not in _ALLOWED_NAMED:
        snippet = node.text.decode("utf-8", errors="replace")[:80]
        return f"Disallowed shell construct '{node.type}' in: {snippet!r}"
    if node.type == "command":
        err = _validate_command_node(node, allowed_commands, allowed_script_dirs, cwd)
        if err is not None:
            return err
    for child in node.children:
        err = _walk(child, allowed_commands, allowed_script_dirs, cwd)
        if err is not None:
            return err
    return None


def _validate_command_node(
    node: Node,
    allowed_commands: List[str],
    allowed_script_dirs: List[str],
    cwd: str | None,
) -> str | None:
    name_node = node.child_by_field_name("name")
    if name_node is None:
        # Commands without a resolvable name (edge case, e.g. bare
        # variable-assignment parsed as `command`) — nothing to validate.
        return None
    executable = name_node.text.decode("utf-8", errors="replace")
    if executable in allowed_commands:
        return None
    if is_under_allowed_dirs(executable, allowed_script_dirs, cwd):
        return None
    return (
        f"Command '{executable}' is not allowed. "
        f"Allowed commands: {sorted(allowed_commands)}. "
        f"Allowed script dirs: {sorted(allowed_script_dirs)}."
    )


def is_under_allowed_dirs(
    path_str: str,
    allowed_dirs: List[str],
    cwd: str | None = None,
) -> bool:
    """Return True if ``path_str`` resolves to a path under any allowed dir.

    When ``cwd`` is given, relative ``path_str`` is resolved against ``cwd``.
    """
    try:
        base = Path(path_str)
        if not base.is_absolute() and cwd is not None:
            base = Path(cwd) / base
        path = base.resolve()
    except (OSError, ValueError):
        return False
    for allowed in allowed_dirs:
        try:
            allowed_root = Path(allowed).resolve()
        except (OSError, ValueError):
            continue
        try:
            path.relative_to(allowed_root)
        except ValueError:
            continue
        else:
            return True
    return False
