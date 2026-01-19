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

try:
    import dotenv
    dotenv.load_dotenv()
except ImportError:
    # dotenv is optional for this test server
    pass

from mcp.server.fastmcp import FastMCP

# Create MCP server
mcp = FastMCP("BasicServer")


@mcp.prompt()
def ask_sum(a: int, b: int) -> str:
    """Prompt of add tool."""
    return f"Can you please calculate the sum of {a} and {b}?"

@mcp.tool()
async def add(a: int, b: int) -> int:
    """Get the detailed information of a specified IP address.

    Args:
        a: The first operand.
        b: The second operand.

    Returns:
        int: The sum of a and b.
    """
    return a + b

mcp.run("streamable-http")
