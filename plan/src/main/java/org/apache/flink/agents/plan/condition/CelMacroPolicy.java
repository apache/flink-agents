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

package org.apache.flink.agents.plan.condition;

import com.google.common.collect.ImmutableList;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelMacroExprFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * CEL macro rules for trigger conditions: the custom {@code has()} macro, the macro whitelist, and
 * the reserved identifiers.
 */
public final class CelMacroPolicy {

    /**
     * Custom parse-time {@code has()} macro: {@code has(a.b)} tests field presence on {@code a};
     * {@code has(score)} tests presence of key {@code score} in the {@code attributes} map.
     */
    public static final CelMacro HAS = CelMacro.newGlobalMacro("has", 1, CelMacroPolicy::expandHas);

    /** CEL's logical-AND overload id; used to AND-join the desugared has() presence chain. */
    private static final String LOGICAL_AND_FUNCTION = "_&&_";

    private static Optional<CelExpr> expandHas(
            CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
        CelExpr arg = arguments.get(0);
        if (arg.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT && !arg.select().testOnly()) {
            return Optional.of(expandHasChain(exprFactory, arg));
        }
        if (arg.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT
                && !RESERVED_IDENTIFIERS.contains(arg.ident().name())) {
            // has(score) → key presence in the attributes map.
            return Optional.of(
                    exprFactory.newSelect(
                            exprFactory.newIdentifier("attributes"), arg.ident().name(), true));
        }
        return Optional.of(
                exprFactory.reportError(
                        "invalid argument to has() macro: expected a field selection like"
                                + " has(a.b) or an attribute name like has(score)"));
    }

    /**
     * Desugars select chain {@code a.b...z} into {@code has(a) && has(a.b) && ... && has(a.b...z)}.
     * The {@code &&} short-circuits, so a missing intermediate key yields false instead of letting
     * cel-java throw on a deeper select.
     */
    private static CelExpr expandHasChain(CelMacroExprFactory exprFactory, CelExpr select) {
        CelExpr operand = select.select().operand();
        CelExpr presence = exprFactory.newSelect(operand, select.select().field(), true);
        if (operand.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT
                && !operand.select().testOnly()) {
            // Recurse into the deeper levels first so they are AND-ed to the left of this one.
            return exprFactory.newGlobalCall(
                    LOGICAL_AND_FUNCTION, expandHasChain(exprFactory, operand), presence);
        }
        if (operand.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT
                && !RESERVED_IDENTIFIERS.contains(operand.ident().name())) {
            // Guard a bare root like has(score): key presence in the attributes map.
            return exprFactory.newGlobalCall(
                    LOGICAL_AND_FUNCTION,
                    exprFactory.newSelect(
                            exprFactory.newIdentifier("attributes"), operand.ident().name(), true),
                    presence);
        }
        return presence;
    }

    /** The complete set of CEL standard macro names. */
    public static final Set<String> CEL_STANDARD_MACROS =
            Set.of("has", "exists", "exists_one", "all", "filter", "map");

    /** Macros allowed in trigger condition expressions. */
    public static final Set<String> ALLOWED_MACROS = Set.of("has");

    /** Returns the first disallowed macro call found in {@code ast}, or empty if none. */
    public static Optional<String> findFirstDisallowedMacro(CelAbstractSyntaxTree ast) {
        return CelNavigableAst.fromAst(ast)
                .getRoot()
                .allNodes()
                .filter(node -> node.getKind() == CelExpr.ExprKind.Kind.CALL)
                .map(node -> node.expr().call().function())
                .filter(fn -> CEL_STANDARD_MACROS.contains(fn) && !ALLOWED_MACROS.contains(fn))
                .findFirst();
    }

    /** Formats the disallowed-macro error message; kept aligned with the Python template. */
    public static String formatDisallowedMessage(String macro, String source) {
        return "CEL expression uses disallowed macro '"
                + macro
                + "': \""
                + source
                + "\". Only allows: "
                + new TreeSet<>(ALLOWED_MACROS)
                + ".";
    }

    /** Names rejected as bare event-type aliases and never shadowed by user attributes. */
    public static final Set<String> RESERVED_IDENTIFIERS;

    static {
        Set<String> set =
                new HashSet<>(
                        Set.of(
                                // Framework-owned activation variables.
                                "type",
                                "attributes",
                                "EventType",
                                // CEL literals.
                                "true",
                                "false",
                                "null",
                                // CEL operators / type-conversion functions / container types.
                                "in",
                                "int",
                                "uint",
                                "double",
                                "string",
                                "bool",
                                "bytes",
                                "list"));
        // All CEL standard macro names.
        set.addAll(CEL_STANDARD_MACROS);
        RESERVED_IDENTIFIERS = Collections.unmodifiableSet(set);
    }

    private CelMacroPolicy() {}
}
