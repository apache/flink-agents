#!/usr/bin/env python3
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
"""
Check the consistency of resource class name constants between ResourceName.java and resource.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


def parse_java_resource_name(java_path: Path) -> dict:
    content = java_path.read_text(encoding="utf-8")

    string_const_re = re.compile(
        r'public\s+static\s+final\s+String\s+([A-Za-z0-9_]+)\s*=\s*"([^"]+)";',
        re.DOTALL,
    )
    class_re = re.compile(r"public\s+(?:static\s+)?final\s+class\s+(\w+)\s*\{")

    class_stack = []
    brace_depth = 0
    result = {}
    pos = 0

    while True:
        next_class = class_re.search(content, pos)
        next_string = string_const_re.search(content, pos)
        next_open = content.find("{", pos)
        next_close = content.find("}", pos)

        best_pos = len(content)
        next_event = None
        if next_class and next_class.start() < best_pos:
            best_pos = next_class.start()
            next_event = ("class", next_class)
        if next_string and next_string.start() < best_pos:
            best_pos = next_string.start()
            next_event = ("string", next_string)
        if next_open >= 0 and next_open < best_pos:
            best_pos = next_open
            next_event = ("open", None)
        if next_close >= 0 and next_close < best_pos:
            best_pos = next_close
            next_event = ("close", None)

        if next_event is None:
            break

        kind, match = next_event
        pos = best_pos + 1

        if kind == "class" and match:
            name = match.group(1)
            brace_depth += 1
            if name == "ResourceName":
                class_stack = [("ResourceName", 1)]
            elif class_stack:
                class_stack.append((name, brace_depth))
            pos = match.end()
        elif kind == "string" and match and class_stack:
            const_name, value = match.group(1), match.group(2)
            if value and value != "DECIDE_IN_RUNTIME_MCPServer":
                path = [c[0] for c in class_stack]

                if len(path) == 2:
                    key = (path[1], "Java")
                    if key not in result:
                        result[key] = {}
                    result[key][const_name] = value
                elif len(path) == 3 and path[2] == "Python":
                    key = (path[1], "Python")
                    if key not in result:
                        result[key] = {}
                    result[key][const_name] = value
            pos = match.end()
        elif kind == "open":
            brace_depth += 1
            pos = next_open + 1
        elif kind == "close":
            brace_depth -= 1
            if class_stack and brace_depth == class_stack[-1][1] - 1:
                class_stack.pop()
            pos = next_close + 1

    return result


def get_python_resource_name_map(python_path: Path) -> dict:
    root = python_path.parent.parent
    python_dir = root / "python"

    try:
        if str(python_dir) not in sys.path:
            sys.path.insert(0, str(python_dir))
        for p in python_dir.glob(".venv/lib/python*/site-packages"):
            if str(p) not in sys.path:
                sys.path.insert(0, str(p))
                break
        from flink_agents.api.resource import ResourceName

        python_map = {}
        for resource_name in ["ChatModel", "EmbeddingModel", "VectorStore"]:
            if not hasattr(ResourceName, resource_name):
                continue
            resource_cls = getattr(ResourceName, resource_name)
            py_impls = {
                attr: getattr(resource_cls, attr)
                for attr in dir(resource_cls)
                if not attr.startswith("_")
                and isinstance(getattr(resource_cls, attr), str)
            }
            if py_impls:
                python_map[(resource_name, "Python")] = py_impls
            if hasattr(resource_cls, "Java"):
                java_impls = {
                    attr: getattr(resource_cls.Java, attr)
                    for attr in dir(resource_cls.Java)
                    if not attr.startswith("_")
                    and isinstance(getattr(resource_cls.Java, attr), str)
                }
                if java_impls:
                    python_map[(resource_name, "Java")] = java_impls
        if hasattr(ResourceName, "MCP_SERVER"):
            python_map[("MCP", "Python")] = {"MCP_SERVER": ResourceName.MCP_SERVER}
        return python_map
    except ImportError:
        return _parse_python_resource_name(python_path)


def _parse_python_resource_name(python_path: Path) -> dict:
    content = python_path.read_text(encoding="utf-8")
    string_const_re = re.compile(r'^\s+([A-Za-z0-9_]+)\s*=\s*"([^"]+)"\s*$')
    class_re = re.compile(r"class\s+(\w+)\s*:")

    result = {}
    indent_stack = []  # [(indent, ["ResourceName", "ChatModel", ...])]
    base_indent = -1

    for line in content.split("\n"):
        if "class ResourceName:" in line:
            base_indent = len(line) - len(line.lstrip())
            indent_stack = [(base_indent, ["ResourceName"])]
            continue
        if base_indent < 0:
            continue

        indent = len(line) - len(line.lstrip())
        stripped = line.strip()

        while len(indent_stack) > 1 and indent <= indent_stack[-1][0] and stripped:
            indent_stack.pop()

        if stripped.startswith("class ") and class_re.match(stripped):
            name = class_re.match(stripped).group(1)
            if indent == base_indent + 4:
                indent_stack = [(base_indent, ["ResourceName"])]
                indent_stack.append((indent, ["ResourceName", name]))
            elif indent == base_indent + 8 and len(indent_stack) >= 2:
                parent_path = indent_stack[-1][1]
                indent_stack.append((indent, parent_path + [name]))

        path = indent_stack[-1][1] if indent_stack else []
        m = string_const_re.match(line)
        if m and path:
            const_name, value = m.group(1), m.group(2)
            if value:
                key = ".".join(path)
                if key not in result:
                    result[key] = {}
                result[key][const_name] = value

    python_map = {}
    for key, consts in result.items():
        parts = key.split(".")
        if len(parts) >= 2 and parts[0] == "ResourceName":
            rt = parts[1]
            if len(parts) == 2:
                python_map[(rt, "Python")] = consts
            elif len(parts) == 3 and parts[2] == "Java":
                python_map[(rt, "Java")] = consts
    if "ResourceName" in result and "MCP_SERVER" in result["ResourceName"]:
        python_map[("MCP", "Python")] = {
            "MCP_SERVER": result["ResourceName"]["MCP_SERVER"]
        }
    return python_map


_JAVA_ONLY_NAMES = frozenset(
    {
        "PYTHON_WRAPPER_CONNECTION",
        "PYTHON_WRAPPER_SETUP",
        "PYTHON_WRAPPER_VECTOR_STORE",
        "PYTHON_WRAPPER_COLLECTION_MANAGEABLE_VECTOR_STORE",
    }
)
_PYTHON_ONLY_NAMES = frozenset(
    {
        "JAVA_WRAPPER_CONNECTION",
        "JAVA_WRAPPER_SETUP",
        "JAVA_WRAPPER_VECTOR_STORE",
        "JAVA_WRAPPER_COLLECTION_MANAGEABLE_VECTOR_STORE",
    }
)


def _find_python_name_for_value(impls: dict, value: str, java_name: str) -> str | None:
    return java_name if impls.get(java_name) == value else None


def check_consistency(java_map: dict, python_map: dict) -> tuple[list[str], list[str]]:
    errors = []
    warnings = []

    all_resource_types = set()
    for rt, _ in java_map:
        all_resource_types.add(rt)
    for rt, _ in python_map:
        all_resource_types.add(rt)

    for resource_type in sorted(all_resource_types):
        if resource_type == "MCP":
            continue

        java_impls = java_map.get((resource_type, "Java"), {})
        python_java_impls = python_map.get((resource_type, "Java"), {})
        java_python_impls = java_map.get((resource_type, "Python"), {})
        python_impls = python_map.get((resource_type, "Python"), {})

        for name, value in java_impls.items():
            if name in _JAVA_ONLY_NAMES:
                continue
            if name in python_java_impls:
                if python_java_impls[name] != value:
                    errors.append(
                        f"[{resource_type}.Java.{name}] not consistent: "
                        f"Java={value!r} vs Python={python_java_impls[name]!r}"
                    )
            else:
                errors.append(
                    f"Java have {resource_type}.{name}，but Python ResourceName.{resource_type}.Java missing corresponding constant"
                )

        for name, value in java_python_impls.items():
            py_name = _find_python_name_for_value(python_impls, value, name)
            if py_name is not None:
                if python_impls[py_name] != value:
                    errors.append(
                        f"[{resource_type}.Python.{name}] not consistent: "
                        f"Java.Python={value!r} vs Python.{py_name}={python_impls[py_name]!r}"
                    )
            else:
                errors.append(
                    f"Java have {resource_type}.Python.{name}, but Python ResourceName.{resource_type} missing corresponding constant（{value!r}）"
                )

        for name in python_java_impls:
            if name not in java_impls:
                errors.append(
                    f"Python have {resource_type}.Java.{name}, but Java ResourceName.{resource_type} missing corresponding constant"
                )

        for name, value in python_impls.items():
            if name in _PYTHON_ONLY_NAMES or name.startswith("JAVA_"):
                continue
            if not _find_python_name_for_value(java_python_impls, value, name):
                if value.startswith("flink_agents."):
                    warnings.append(
                        f"Python have {resource_type}.{name}（Python), but Java ResourceName.{resource_type}.Python missing corresponding constant"
                    )

    return errors, warnings


def main() -> int:
    root = Path(__file__).resolve().parent.parent.parent
    java_path = (
        root
        / "api/src/main/java/org/apache/flink/agents/api/resource/ResourceName.java"
    )
    python_path = root / "python/flink_agents/api/resource.py"

    if not java_path.exists():
        print(f"error: can`t find {java_path}", file=sys.stderr)
        return 1
    if not python_path.exists():
        print(f"error: can`t find {python_path}", file=sys.stderr)
        return 1

    java_map = parse_java_resource_name(java_path)
    python_map = get_python_resource_name_map(python_path)

    debug = __import__("os").environ.get("RESOURCE_DEBUG")
    if debug:
        import json

        print(
            "Java map:",
            json.dumps(
                {str(k): v for k, v in java_map.items()}, indent=2, ensure_ascii=False
            ),
        )
        print(
            "Python map:",
            json.dumps(
                {str(k): v for k, v in python_map.items()}, indent=2, ensure_ascii=False
            ),
        )

    errors, warnings = check_consistency(java_map, python_map)

    if errors:
        print("ResourceName Cross-language consistency check failed:", file=sys.stderr)
        for e in errors:
            print(f"  error: {e}", file=sys.stderr)
        return 1

    if warnings:
        print("ResourceName Cross-language consistency check warn:", file=sys.stderr)
        for w in warnings:
            print(f"  warnings: {w}", file=sys.stderr)
    else:
        print("ResourceName Cross-language consistency check successful")

    return 0


if __name__ == "__main__":
    sys.exit(main())
