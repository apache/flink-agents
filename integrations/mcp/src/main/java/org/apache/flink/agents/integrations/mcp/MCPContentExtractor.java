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

package org.apache.flink.agents.integrations.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for extracting and normalizing MCP content items.
 *
 * <p>MCP servers can return various content types (text, images, embedded resources). This utility
 * converts them to Java-friendly objects.
 */
public class MCPContentExtractor {

    /**
     * Extract and normalize a single MCP content item.
     *
     * @param contentItem A content item from MCP (TextContent, ImageContent, etc.)
     * @return Normalized content as String or Map
     */
    public static Object extractContentItem(Object contentItem) {
        if (contentItem instanceof McpSchema.TextContent) {
            return extractTextContent((McpSchema.TextContent) contentItem);
        } else if (contentItem instanceof McpSchema.ImageContent) {
            return extractImageContent((McpSchema.ImageContent) contentItem);
        } else if (contentItem instanceof McpSchema.EmbeddedResource) {
            return extractEmbeddedResource((McpSchema.EmbeddedResource) contentItem);
        } else {
            // Handle unknown content types as string
            return contentItem != null ? contentItem.toString() : "";
        }
    }

    /**
     * Extract text content from MCP TextContent.
     *
     * @param textContent The text content
     * @return The text as a string
     */
    private static String extractTextContent(McpSchema.TextContent textContent) {
        return textContent.text();
    }

    /**
     * Extract image content from MCP ImageContent.
     *
     * @param imageContent The image content
     * @return A map with image details
     */
    private static Map<String, Object> extractImageContent(McpSchema.ImageContent imageContent) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", "image");
        result.put("data", imageContent.data());
        result.put("mimeType", imageContent.mimeType());
        return result;
    }

    /**
     * Extract embedded resource from MCP EmbeddedResource.
     *
     * @param embeddedResource The embedded resource
     * @return A map with resource details
     */
    private static Map<String, Object> extractEmbeddedResource(
            McpSchema.EmbeddedResource embeddedResource) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", "resource");

        var resource = embeddedResource.resource();
        if (resource instanceof McpSchema.TextResourceContents) {
            McpSchema.TextResourceContents textResource = (McpSchema.TextResourceContents) resource;
            result.put("uri", textResource.uri());
            result.put("text", textResource.text());
        } else if (resource instanceof McpSchema.BlobResourceContents) {
            McpSchema.BlobResourceContents blobResource = (McpSchema.BlobResourceContents) resource;
            result.put("uri", blobResource.uri());
            result.put("blob", blobResource.blob());
        }

        return result;
    }

    /** Private constructor to prevent instantiation. */
    private MCPContentExtractor() {
        throw new UnsupportedOperationException("Utility class");
    }
}
