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
import dev.cel.common.CelOptions;
import dev.cel.common.ast.CelExpr;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelMacroExprFactory;
import dev.cel.parser.CelParser;
import dev.cel.parser.CelParserFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Shared syntax configuration for trigger conditions. */
public final class ConditionSyntaxPolicy {

    /** Shared limits used by Plan validation and Runtime compilation and evaluation. */
    public static final CelOptions CEL_OPTIONS =
            CelOptions.current()
                    .maxParseRecursionDepth(32)
                    .maxExpressionCodePointSize(8_192)
                    .comprehensionMaxIterations(1_000)
                    .build();

    /** The custom presence helper supported by trigger conditions. */
    public static final CelMacro HAS =
            CelMacro.newGlobalMacro("has", 1, ConditionSyntaxPolicy::expandHas);

    /** Parser configured with the trigger-condition syntax policy and custom has() macro. */
    public static final CelParser PARSER =
            CelParserFactory.standardCelParserBuilder()
                    .setOptions(CEL_OPTIONS)
                    .addMacros(HAS)
                    .build();

    private static final String LOGICAL_AND_FUNCTION = "_&&_";
    private static final Set<String> CEL_STANDARD_MACROS =
            Set.of("has", "exists", "exists_one", "all", "filter", "map");
    private static final Set<String> RESERVED_IDENTIFIERS;

    static {
        Set<String> reserved =
                new HashSet<>(
                        Set.of(
                                "type",
                                "attributes",
                                "id",
                                "EventType",
                                "true",
                                "false",
                                "null",
                                "in",
                                "int",
                                "uint",
                                "double",
                                "string",
                                "bool",
                                "bytes",
                                "list"));
        reserved.addAll(CEL_STANDARD_MACROS);
        RESERVED_IDENTIFIERS = Collections.unmodifiableSet(reserved);
    }

    private static Optional<CelExpr> expandHas(
            CelMacroExprFactory expressionFactory,
            CelExpr target,
            ImmutableList<CelExpr> arguments) {
        CelExpr argument = arguments.get(0);
        if (argument.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT
                && !argument.select().testOnly()) {
            return Optional.of(expandHasChain(expressionFactory, argument));
        }
        if (argument.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT
                && !RESERVED_IDENTIFIERS.contains(argument.ident().name())) {
            return Optional.of(
                    expressionFactory.newSelect(
                            expressionFactory.newIdentifier("attributes"),
                            argument.ident().name(),
                            true));
        }
        return Optional.of(
                expressionFactory.reportError(
                        "invalid argument to has(): expected a field selection like"
                                + " has(a.b) or an attribute name like has(score)"));
    }

    private static CelExpr expandHasChain(
            CelMacroExprFactory expressionFactory, CelExpr selection) {
        CelExpr operand = selection.select().operand();
        CelExpr presence = expressionFactory.newSelect(operand, selection.select().field(), true);
        if (operand.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT
                && !operand.select().testOnly()) {
            return expressionFactory.newGlobalCall(
                    LOGICAL_AND_FUNCTION, expandHasChain(expressionFactory, operand), presence);
        }
        if (operand.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT
                && !RESERVED_IDENTIFIERS.contains(operand.ident().name())) {
            return expressionFactory.newGlobalCall(
                    LOGICAL_AND_FUNCTION,
                    expressionFactory.newSelect(
                            expressionFactory.newIdentifier("attributes"),
                            operand.ident().name(),
                            true),
                    presence);
        }
        return presence;
    }

    private ConditionSyntaxPolicy() {}
}
