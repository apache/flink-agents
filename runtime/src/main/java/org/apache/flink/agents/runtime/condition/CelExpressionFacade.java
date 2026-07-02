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

package org.apache.flink.agents.runtime.condition;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.types.CelType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelParser;
import dev.cel.parser.CelParserFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.plan.condition.CelMacroPolicy;
import org.apache.flink.annotation.VisibleForTesting;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CEL Parse → Validate → Check → Program pipeline. Compiled {@link CelRuntime.Program} instances
 * are cached process-wide by source string.
 */
public final class CelExpressionFacade {

    /** Immutable {@code constant name → value} map bound to the CEL {@code EventType} variable. */
    static final Map<String, Object> EVENT_TYPE_CONSTANTS = Map.copyOf(EventType.allConstants());

    /** Cel-java has no wall-clock timeout, so these caps bound expression size/depth. */
    private static final CelOptions CEL_OPTIONS =
            CelOptions.current()
                    .maxExpressionCodePointSize(8_192)
                    .maxParseRecursionDepth(32)
                    .comprehensionMaxIterations(1_000)
                    .build();

    /**
     * Only the custom has() macro is enabled; all others are rejected by {@link CelMacroPolicy}.
     */
    private static final CelParser CEL_PARSER =
            CelParserFactory.standardCelParserBuilder()
                    .setOptions(CEL_OPTIONS)
                    .addMacros(CelMacroPolicy.HAS)
                    .build();

    /**
     * Vars always declared at type-check; mirrors {@link CelConditionEvaluator#createActivation}.
     */
    private static final Map<String, CelType> BASE_VARS =
            Map.of(
                    "type",
                    SimpleType.STRING,
                    "id",
                    SimpleType.DYN,
                    "EventType",
                    MapType.create(SimpleType.STRING, SimpleType.STRING),
                    "attributes",
                    MapType.create(SimpleType.STRING, SimpleType.DYN));

    private static final CelRuntime CEL_RUNTIME =
            CelRuntimeFactory.standardCelRuntimeBuilder().setOptions(CEL_OPTIONS).build();

    /** Process-wide bounded LRU cache of compiled CEL programs, keyed by source string. */
    static final int PROGRAM_CACHE_MAX_SIZE = 1024;

    private static final Map<String, CelRuntime.Program> PROGRAM_CACHE =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, CelRuntime.Program>(
                            256, 0.75f, /* accessOrder */ true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, CelRuntime.Program> eldest) {
                            return size() > PROGRAM_CACHE_MAX_SIZE;
                        }
                    });

    private CelExpressionFacade() {}

    /** Parses {@code source} into an untyped AST (no type-check). */
    public static CelAbstractSyntaxTree parse(String source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException(
                    "CelExpressionFacade.parse: source must be non-null and non-empty");
        }
        try {
            return CEL_PARSER.parse(source).getAst();
        } catch (CelValidationException e) {
            throw new IllegalArgumentException(
                    "Invalid CEL expression: \"" + source + "\" — " + e.getMessage(), e);
        }
    }

    /** Compiles {@code source} into a cached, thread-safe program. */
    public static CelRuntime.Program toProgram(String source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException(
                    "CelExpressionFacade.toProgram: source must be non-null and non-empty");
        }
        return PROGRAM_CACHE.computeIfAbsent(source, CelExpressionFacade::compile);
    }

    private static CelRuntime.Program compile(String source) {
        CelAbstractSyntaxTree parsed;
        try {
            parsed = parse(source);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid CEL condition expression: \"" + source + "\" — " + e.getMessage(), e);
        }
        return compileFromAst(source, parsed);
    }

    private static CelRuntime.Program compileFromAst(String source, CelAbstractSyntaxTree parsed) {
        CelMacroPolicy.findFirstDisallowedMacro(parsed)
                .ifPresent(
                        macro -> {
                            throw new IllegalArgumentException(
                                    CelMacroPolicy.formatDisallowedMessage(macro, source));
                        });

        validateEventTypeReferences(parsed);

        try {
            CelAbstractSyntaxTree checked = compilerFor(parsed).check(parsed).getAst();
            return CEL_RUNTIME.createProgram(checked);
        } catch (CelValidationException | CelEvaluationException e) {
            throw new IllegalArgumentException(
                    "Invalid CEL condition expression: \"" + source + "\" — " + e.getMessage(), e);
        }
    }

    /**
     * Builds a type-checker for {@code parsed}: base vars plus every identifier appearing in the
     * expression declared as DYN.
     */
    private static CelCompiler compilerFor(CelAbstractSyntaxTree parsed) {
        CelCompilerBuilder builder =
                CelCompilerFactory.standardCelCompilerBuilder().setOptions(CEL_OPTIONS);
        BASE_VARS.forEach(builder::addVar);
        for (String ident : collectIdentifiers(parsed)) {
            if (!BASE_VARS.containsKey(ident)) {
                builder.addVar(ident, SimpleType.DYN);
            }
        }
        return builder.build();
    }

    /**
     * Throws {@link IllegalArgumentException} when any {@code EventType.X} in {@code ast} names an
     * unknown constant.
     */
    private static void validateEventTypeReferences(CelAbstractSyntaxTree ast) {
        CelNavigableAst.fromAst(ast)
                .getRoot()
                .allNodes()
                .filter(node -> node.getKind() == CelExpr.ExprKind.Kind.SELECT)
                .map(node -> node.expr().select())
                .filter(
                        select ->
                                select.operand().getKind() == CelExpr.ExprKind.Kind.IDENT
                                        && "EventType".equals(select.operand().ident().name()))
                .forEach(
                        select -> {
                            if (!EVENT_TYPE_CONSTANTS.containsKey(select.field())) {
                                throw new IllegalArgumentException(
                                        "Unknown EventType constant: EventType." + select.field());
                            }
                        });
    }

    private static Set<String> collectIdentifiers(CelAbstractSyntaxTree ast) {
        return CelNavigableAst.fromAst(ast)
                .getRoot()
                .allNodes()
                .filter(node -> node.getKind() == CelExpr.ExprKind.Kind.IDENT)
                .map(node -> node.expr().ident().name())
                .collect(Collectors.toSet());
    }

    @VisibleForTesting
    static void clearProgramCacheForTests() {
        PROGRAM_CACHE.clear();
    }

    @VisibleForTesting
    static long programCacheSizeForTests() {
        return PROGRAM_CACHE.size();
    }
}
