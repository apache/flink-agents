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
#  limitations under the License.
################################################################################
"""MCP server providing prompts and tools for calculation tasks."""
import dotenv
from mcp.server.fastmcp import FastMCP

dotenv.load_dotenv()

# Create MCP server
mcp = FastMCP("MathServer")


@mcp.prompt()
def ask_sum(a: int, b: int) -> str:
    """Generate a prompt asking to calculate the sum of two numbers.

    This prompt will be used by chat models to request calculations.

    Args:
        a: The first operand
        b: The second operand

    Returns:
        A formatted prompt string
    """
    return f"Calculate the sum of {a} and {b}?"


@mcp.tool()
async def add(a: int, b: int) -> int:
    """Calculate the sum of two numbers.

    This tool can be called by chat models to perform the actual calculation.

    Args:
        a: The first operand
        b: The second operand

    Returns:
        The sum of a and b
    """
    return a + b


mcp.run("streamable-http")
