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

package org.apache.flink.agents.runtime.eventlog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Truncates a Jackson {@link JsonNode} tree per configurable thresholds.
 *
 * <p>Three truncation strategies are applied in a single recursive pass:
 *
 * <ul>
 *   <li><b>String truncation</b>: Strings longer than {@code maxStringLength} are replaced with a
 *       wrapper: {@code {"truncatedString": "first N chars...", "omittedChars": M}}
 *   <li><b>Array truncation</b>: Arrays larger than {@code maxArrayElements} are replaced with a
 *       wrapper: {@code {"truncatedList": [first N elements], "omittedElements": M}}
 *   <li><b>Depth truncation</b>: At max depth, object nodes retain only scalar fields; nested
 *       objects/arrays are dropped: {@code {"truncatedObject": {scalars...}, "omittedFields": N}}
 * </ul>
 *
 * <p>Setting any threshold to {@code 0} disables that specific truncation strategy. If all
 * thresholds are {@code 0}, no truncation occurs.
 *
 * <p>Protected fields at the top level of the event node ({@code eventType}, {@code id}, {@code
 * attributes}) are never truncated.
 */
public class JsonTruncator {

    private static final Set<String> PROTECTED_FIELDS =
            new HashSet<>(Arrays.asList("eventType", "id", "attributes"));

    private final int maxStringLength;
    private final int maxArrayElements;
    private final int maxDepth;

    /**
     * Creates a new truncator with the given thresholds.
     *
     * @param maxStringLength maximum character length for string values; 0 to disable
     * @param maxArrayElements maximum number of array elements retained; 0 to disable
     * @param maxDepth maximum object nesting depth; 0 to disable
     */
    public JsonTruncator(int maxStringLength, int maxArrayElements, int maxDepth) {
        this.maxStringLength = maxStringLength;
        this.maxArrayElements = maxArrayElements;
        this.maxDepth = maxDepth;
    }

    /**
     * Truncates the given event node in place according to configured thresholds.
     *
     * <p>Protected fields ({@code eventType}, {@code id}, {@code attributes}) at the top level of
     * the event node are never truncated.
     *
     * @param eventNode the top-level event JSON object to truncate
     * @return {@code true} if any field was truncated, {@code false} if the node was unchanged
     */
    public boolean truncate(ObjectNode eventNode) {
        if (eventNode == null) {
            return false;
        }
        return truncateObject(eventNode, 1, true);
    }

    /**
     * Recursively truncates an object node.
     *
     * @param node the object node to process
     * @param depth current depth (1 = top-level event node)
     * @param isTopLevel whether this is the top-level event node (for protected field checks)
     * @return true if any truncation occurred
     */
    private boolean truncateObject(ObjectNode node, int depth, boolean isTopLevel) {
        boolean truncated = false;

        // At max depth, collapse the entire object to retain only scalars
        if (maxDepth > 0 && depth >= maxDepth) {
            return collapseAtMaxDepth(node, isTopLevel);
        }

        List<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);

        for (String fieldName : fieldNames) {
            if (isTopLevel && PROTECTED_FIELDS.contains(fieldName)) {
                continue;
            }

            JsonNode child = node.get(fieldName);
            if (child == null) {
                continue;
            }

            if (child.isTextual()) {
                JsonNode replacement = truncateString(child.textValue());
                if (replacement != null) {
                    node.set(fieldName, replacement);
                    truncated = true;
                }
            } else if (child.isArray()) {
                // First recurse into retained elements, then truncate the array if needed
                truncated |= truncateArrayContents((ArrayNode) child, depth + 1);
                JsonNode replacement = truncateArray((ArrayNode) child);
                if (replacement != null) {
                    node.set(fieldName, replacement);
                    truncated = true;
                }
            } else if (child.isObject()) {
                truncated |= truncateObject((ObjectNode) child, depth + 1, false);
            }
        }

        return truncated;
    }

    /**
     * Truncates a string if it exceeds maxStringLength.
     *
     * @return a wrapper ObjectNode if truncated, or null if no truncation needed
     */
    private JsonNode truncateString(String value) {
        if (maxStringLength <= 0 || value == null || value.length() <= maxStringLength) {
            return null;
        }
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
        wrapper.put("truncatedString", value.substring(0, maxStringLength) + "...");
        wrapper.put("omittedChars", value.length() - maxStringLength);
        return wrapper;
    }

    /**
     * Truncates an array if it exceeds maxArrayElements.
     *
     * @return a wrapper ObjectNode if truncated, or null if no truncation needed
     */
    private JsonNode truncateArray(ArrayNode array) {
        if (maxArrayElements <= 0 || array.size() <= maxArrayElements) {
            return null;
        }
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
        ArrayNode retained = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < maxArrayElements; i++) {
            retained.add(array.get(i));
        }
        wrapper.set("truncatedList", retained);
        wrapper.put("omittedElements", array.size() - maxArrayElements);
        return wrapper;
    }

    /**
     * Recursively truncates contents within an array (strings and nested structures).
     *
     * @return true if any truncation occurred within array elements
     */
    private boolean truncateArrayContents(ArrayNode array, int depth) {
        boolean truncated = false;
        for (int i = 0; i < array.size(); i++) {
            JsonNode element = array.get(i);
            if (element.isTextual()) {
                JsonNode replacement = truncateString(element.textValue());
                if (replacement != null) {
                    array.set(i, replacement);
                    truncated = true;
                }
            } else if (element.isObject()) {
                truncated |= truncateObject((ObjectNode) element, depth, false);
            } else if (element.isArray()) {
                truncated |= truncateArrayContents((ArrayNode) element, depth + 1);
                JsonNode replacement = truncateArray((ArrayNode) element);
                if (replacement != null) {
                    array.set(i, replacement);
                    truncated = true;
                }
            }
        }
        return truncated;
    }

    /**
     * At max depth, retain only scalar fields and drop nested objects/arrays.
     *
     * @return true if any fields were dropped
     */
    private boolean collapseAtMaxDepth(ObjectNode node, boolean isTopLevel) {
        List<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);

        ObjectNode scalarFields = JsonNodeFactory.instance.objectNode();
        int omittedCount = 0;
        boolean hasNonScalar = false;

        for (String fieldName : fieldNames) {
            if (isTopLevel && PROTECTED_FIELDS.contains(fieldName)) {
                // Protected fields are kept as-is, even if non-scalar
                scalarFields.set(fieldName, node.get(fieldName));
                continue;
            }

            JsonNode child = node.get(fieldName);
            if (child.isObject() || child.isArray()) {
                omittedCount++;
                hasNonScalar = true;
            } else {
                // Apply string truncation to scalar string fields even at max depth
                if (child.isTextual()) {
                    JsonNode replacement = truncateString(child.textValue());
                    if (replacement != null) {
                        scalarFields.set(fieldName, replacement);
                    } else {
                        scalarFields.set(fieldName, child);
                    }
                } else {
                    scalarFields.set(fieldName, child);
                }
            }
        }

        if (!hasNonScalar) {
            // No non-scalar fields to drop; but string truncation may have occurred
            // Check if any scalar field was replaced
            boolean stringTruncated = false;
            for (String fieldName : fieldNames) {
                if (isTopLevel && PROTECTED_FIELDS.contains(fieldName)) {
                    continue;
                }
                JsonNode original = node.get(fieldName);
                if (original.isTextual()) {
                    JsonNode replacement = truncateString(original.textValue());
                    if (replacement != null) {
                        node.set(fieldName, replacement);
                        stringTruncated = true;
                    }
                }
            }
            return stringTruncated;
        }

        // Replace the node contents with the wrapped version
        node.removeAll();
        node.set("truncatedObject", scalarFields);
        node.put("omittedFields", omittedCount);
        return true;
    }
}
