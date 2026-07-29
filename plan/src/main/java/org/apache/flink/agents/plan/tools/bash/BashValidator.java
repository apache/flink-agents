/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.agents.plan.tools.bash;

import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterBash;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AST-based bash command validator backed by tree-sitter-bash.
 *
 * <p>Mirrors the Python {@code flink_agents.plan.tools.bash.bash_validator}: walks the parsed AST
 * and rejects any named node whose type is not on the {@link #ALLOWED_NAMED} allowlist (e.g. {@code
 * command_substitution}, {@code subshell}, {@code for_statement}, etc.); for every {@code command}
 * node it requires the executable to be either in {@code allowedCommands} or to resolve to a path
 * under one of {@code allowedScriptDirs}.
 */
public final class BashValidator {

    /**
     * Named AST node types we accept. Anything named but missing from this set is treated as a
     * potentially dangerous shell construct and rejected. Unnamed nodes (literal punctuation like
     * {@code |}, {@code &&}, {@code (}) are always allowed — they're just syntax tokens.
     *
     * <p>Kept in sync with the Python {@code _ALLOWED_NAMED} set.
     */
    public static final Set<String> ALLOWED_NAMED =
            Set.of(
                    "program",
                    "command",
                    "command_name",
                    // `export VAR=...`, `readonly`, `declare`, `local`, `typeset`
                    "declaration_command",
                    "pipeline",
                    "list",
                    "redirected_statement",
                    "file_redirect",
                    "file_descriptor",
                    "variable_assignment",
                    "variable_name",
                    "special_variable_name", // $@ $? $* $#
                    "word",
                    "string",
                    "string_content",
                    "raw_string",
                    "ansi_c_string",
                    "translated_string",
                    "concatenation",
                    "number",
                    "simple_expansion", // $VAR
                    "expansion", // ${VAR}
                    "arithmetic_expansion", // $((...))
                    "binary_expression",
                    "unary_expression",
                    "parenthesized_expression",
                    "array");

    private static final Object PARSER_LOCK = new Object();
    private static volatile TSParser parser;

    private BashValidator() {}

    private static TSParser parser() {
        TSParser p = parser;
        if (p == null) {
            synchronized (PARSER_LOCK) {
                p = parser;
                if (p == null) {
                    TSParser created = new TSParser();
                    created.setLanguage(new TreeSitterBash());
                    parser = created;
                    p = created;
                }
            }
        }
        return p;
    }

    /**
     * Validate a bash command. Returns {@link Optional#empty()} when allowed, or a non-empty
     * descriptive error otherwise.
     */
    public static Optional<String> validate(
            String command,
            List<String> allowedCommands,
            List<String> allowedScriptDirs,
            @Nullable String cwd) {
        if (command == null || command.trim().isEmpty()) {
            return Optional.of("Empty command.");
        }
        TSTree tree;
        synchronized (PARSER_LOCK) {
            tree = parser().parseString(null, command);
        }
        TSNode root = tree.getRootNode();
        if (root.hasError()) {
            return Optional.of("Command has syntax errors.");
        }
        if (root.getChildCount() == 0) {
            return Optional.of("Empty command.");
        }
        return walk(root, command, allowedCommands, allowedScriptDirs, cwd);
    }

    private static Optional<String> walk(
            TSNode node,
            String command,
            List<String> allowedCommands,
            List<String> allowedScriptDirs,
            @Nullable String cwd) {
        if (node.isNamed() && !ALLOWED_NAMED.contains(node.getType())) {
            String snippet = nodeText(node, command);
            if (snippet.length() > 80) {
                snippet = snippet.substring(0, 80);
            }
            return Optional.of(
                    "Disallowed shell construct '" + node.getType() + "' in: '" + snippet + "'");
        }
        if ("command".equals(node.getType())) {
            Optional<String> err =
                    validateCommand(node, command, allowedCommands, allowedScriptDirs, cwd);
            if (err.isPresent()) {
                return err;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Optional<String> err =
                    walk(node.getChild(i), command, allowedCommands, allowedScriptDirs, cwd);
            if (err.isPresent()) {
                return err;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateCommand(
            TSNode commandNode,
            String command,
            List<String> allowedCommands,
            List<String> allowedScriptDirs,
            @Nullable String cwd) {
        TSNode nameNode = commandNode.getChildByFieldName("name");
        if (nameNode == null || nameNode.isNull()) {
            // Bare variable-assignment parsed as command — nothing to validate.
            return Optional.empty();
        }
        String executable = nodeText(nameNode, command);
        if (allowedCommands.contains(executable)) {
            return Optional.empty();
        }
        if (isUnderAllowedDirs(executable, allowedScriptDirs, cwd)) {
            return Optional.empty();
        }
        Set<String> sortedCommands = new HashSet<>(allowedCommands);
        Set<String> sortedDirs = new HashSet<>(allowedScriptDirs);
        return Optional.of(
                "Command '"
                        + executable
                        + "' is not allowed. Allowed commands: "
                        + sortedCommands
                        + ". Allowed script dirs: "
                        + sortedDirs
                        + ".");
    }

    /** Return true when {@code pathStr} resolves to a path under any of the allowed dirs. */
    public static boolean isUnderAllowedDirs(
            String pathStr, List<String> allowedDirs, @Nullable String cwd) {
        Path base;
        try {
            base = Path.of(pathStr);
        } catch (Exception e) {
            return false;
        }
        if (!base.isAbsolute() && cwd != null) {
            base = Path.of(cwd).resolve(base);
        }
        Path resolved;
        try {
            resolved = base.toAbsolutePath().toRealPath();
        } catch (IOException e) {
            try {
                resolved = base.toAbsolutePath().normalize();
            } catch (Exception ee) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        for (String allowed : allowedDirs) {
            try {
                Path allowedRoot;
                try {
                    allowedRoot = Path.of(allowed).toAbsolutePath().toRealPath();
                } catch (IOException io) {
                    allowedRoot = Path.of(allowed).toAbsolutePath().normalize();
                }
                if (resolved.startsWith(allowedRoot)) {
                    return true;
                }
            } catch (Exception ignored) {
                // skip invalid allowed root
            }
        }
        return false;
    }

    private static String nodeText(TSNode node, String command) {
        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start < 0 || end > bytes.length || start > end) {
            return "";
        }
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }
}
