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

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.ast.CelExpr;
import dev.cel.parser.CelParser;
import dev.cel.parser.CelParserFactory;

import java.util.Objects;

/**
 * A parsed {@code Action.triggerConditions} entry — either {@link TypeMatch} or {@link
 * CelExpression}. {@link #classify} turns a raw entry string into one of the two.
 */
public interface ParsedCondition {

    /** Original user-written entry string. */
    String source();

    /** Mirrors the runtime facade caps so a too-deep / too-long expression fails at classify. */
    CelOptions CEL_OPTIONS =
            CelOptions.current()
                    .maxParseRecursionDepth(32)
                    .maxExpressionCodePointSize(8_192)
                    .build();

    /**
     * Parser with the custom {@code has()} macro and the same resource caps as the runtime facade
     * parser.
     */
    CelParser CEL_PARSER =
            CelParserFactory.standardCelParserBuilder()
                    .setOptions(CEL_OPTIONS)
                    .addMacros(CelMacroPolicy.HAS)
                    .build();

    /**
     * Parses a {@code triggerConditions} entry: a non-reserved bare-identifier root becomes a
     * {@link TypeMatch}, everything else a {@link CelExpression}.
     */
    static ParsedCondition classify(String source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException(
                    "ParsedCondition.classify: source must be non-null and non-empty");
        }
        CelAbstractSyntaxTree ast;
        try {
            ast = CEL_PARSER.parse(source).getAst();
        } catch (CelValidationException e) {
            throw new IllegalArgumentException(
                    "Invalid CEL expression: \"" + source + "\" — " + e.getMessage(), e);
        }
        CelExpr root = ast.getExpr();
        if (root.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
            String name = root.ident().name();
            if (CelMacroPolicy.RESERVED_IDENTIFIERS.contains(name)) {
                throw new IllegalArgumentException(
                        "'"
                                + name
                                + "' is a CEL reserved keyword and cannot be used as an "
                                + "event type name. Did you mean: @action(\""
                                + name
                                + " == 'xxx'\") or @action(\"attributes."
                                + name
                                + "\")?");
            }
            return new TypeMatch(name);
        }
        return new CelExpression(source);
    }

    /** A plain event-type match. {@link #source()} is compared against {@code Event.getType()}. */
    final class TypeMatch implements ParsedCondition {

        private final String source;

        public TypeMatch(String source) {
            this.source = source;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TypeMatch)) return false;
            return source.equals(((TypeMatch) o).source);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source);
        }

        @Override
        public String toString() {
            return "TypeMatch{source=" + source + "}";
        }
    }

    /** A CEL expression. Source-only; compiled elsewhere. */
    final class CelExpression implements ParsedCondition {

        private final String source;

        public CelExpression(String source) {
            this.source = source;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CelExpression)) return false;
            return source.equals(((CelExpression) o).source);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source);
        }

        @Override
        public String toString() {
            return "CelExpression{source=" + source + "}";
        }
    }
}
