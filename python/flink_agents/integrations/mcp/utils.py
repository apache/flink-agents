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
#################################################################################
from typing import Any, Dict

from mcp import types


def extract_mcp_content_item(content_item: Any) -> Dict[str, Any] | str:
    """Extract and normalize a single MCP content item.

    Args:
        content_item: A single MCP content item (TextContent, ImageContent, etc.)

    Returns:
        Dict representation of the content item

    Raises:
        ImportError: If MCP types are not available
    """
    if types is None:
        err_msg = "MCP types not available. Please install the mcp package."
        raise ImportError(err_msg)

    if isinstance(content_item, types.TextContent):
        return content_item.text
    elif isinstance(content_item, types.ImageContent):
        return {
            "type": "image",
            "data": content_item.data,
            "mimeType": content_item.mimeType,
        }
    elif isinstance(content_item, types.EmbeddedResource):
        if isinstance(content_item.resource, types.TextResourceContents):
            return {
                "type": "resource",
                "uri": content_item.resource.uri,
                "text": content_item.resource.text,
            }
        elif isinstance(content_item.resource, types.BlobResourceContents):
            return {
                "type": "resource",
                "uri": content_item.resource.uri,
                "blob": content_item.resource.blob,
            }
        else:
            err_msg = f"Unsupported content type: {type(content_item)}"
            raise TypeError(err_msg)
    else:
        # Handle unknown content types as generic dict
        return (
            content_item.model_dump()
            if hasattr(content_item, "model_dump")
            else str(content_item)
        )
