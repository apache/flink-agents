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
"""AST-based bash command validation using tree-sitter-bash.

Parses the command with tree-sitter-bash and walks the AST. Any named node
whose type is not in the allowed set (e.g. ``command_substitution``,
``process_substitution``, ``subshell``, ``for_statement``) causes the whole
command to be rejected. Every ``command`` node's name is checked against the
``allowed_commands`` allowlist or resolved under ``allowed_script_dirs``.

This lets the tool accept natural shell constructs like pipes, ``&&`` / ``||``
chains and file-descriptor-only redirections while blocking common injection
vectors (``$()``, backticks, file redirects, execution-changing environment
assignments, control flow, etc.).
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
        "binary_expression",
        "unary_expression",
        "parenthesized_expression",
        "array",
    }
)

_BLOCKED_ENVIRONMENT_VARIABLES = frozenset(
    {"PATH", "BASH_ENV", "ENV", "SHELLOPTS", "CDPATH"}
)
_DYNAMIC_LOADER_VARIABLE_PREFIXES = ("LD_", "DYLD_")
_FD_REDIRECT_OPERATORS = frozenset({"<&", ">&"})
_FD_CLOSE_OPERATORS = frozenset({"<&-", ">&-"})


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
    if node.type == "variable_assignment" and (
        node.parent is None or node.parent.type != "command"
    ):
        return "Standalone variable assignment without an executable is not allowed."
    if node.type == "file_redirect" and not _is_fd_only_redirect(node):
        return (
            "File redirects are not allowed; only file-descriptor duplication "
            "and closure are permitted."
        )
    if node.type == "variable_assignment":
        name_node = node.child_by_field_name("name")
        if name_node is not None:
            name = name_node.text.decode("utf-8", errors="replace")
            if _is_blocked_environment_variable(name):
                return f"Environment variable assignment '{name}' is not allowed."
    if node.type == "command":
        err = _validate_command_node(node, allowed_commands, allowed_script_dirs, cwd)
        if err is not None:
            return err
    for child in node.children:
        err = _walk(child, allowed_commands, allowed_script_dirs, cwd)
        if err is not None:
            return err
    return None


def _is_fd_only_redirect(node: Node) -> bool:
    """Return whether a redirect only duplicates or closes a file descriptor."""
    operator = next((child.type for child in node.children if not child.is_named), None)
    destination = node.child_by_field_name("destination")
    if operator in _FD_CLOSE_OPERATORS:
        return destination is None
    return (
        operator in _FD_REDIRECT_OPERATORS
        and destination is not None
        and (
            destination.type == "number"
            or (
                destination.type == "word"
                and destination.text.endswith(b"-")
                and destination.text[:-1].isdigit()
            )
        )
    )


def _is_blocked_environment_variable(name: str) -> bool:
    return name in _BLOCKED_ENVIRONMENT_VARIABLES or name.startswith(
        _DYNAMIC_LOADER_VARIABLE_PREFIXES
    )


def _validate_command_node(
    node: Node,
    allowed_commands: List[str],
    allowed_script_dirs: List[str],
    cwd: str | None,
) -> str | None:
    name_node = node.child_by_field_name("name")
    if name_node is None:
        # Fail closed for constructs such as bare variable assignments. Bash
        # can later reinterpret their values in arithmetic contexts.
        return "Command without an executable is not allowed."
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
