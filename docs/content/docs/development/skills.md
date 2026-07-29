---
title: Skills
weight: 10
type: docs
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

## Overview

A **Skill** is a self-contained package of instructions, and optionally scripts and reference files, that teaches the agent how to perform a specialized task. A skill is just a directory containing a `SKILL.md` file. Flink Agents discovers the skills you declare, lets the agent decide which one is relevant to the current request, and loads its full instructions only when needed.

Skills follow a **progressive disclosure** model, so that providing the agent with many capabilities does not bloat every request:

1. **Discovery** — at startup, only each skill's `name` and `description` are injected into the system prompt (a few dozen tokens per skill), so the agent knows what is available.
2. **Activation** — when the agent judges a skill relevant, it calls the built-in `load_skill` tool to read the full `SKILL.md` instructions into the context.
3. **Execution** — the agent follows the loaded instructions, running any bundled scripts or shell commands through the built-in `bash` tool, and reading additional reference files only on demand.

Skills are a good fit when a capability is best described as a procedure (a runbook the agent follows) rather than a single function call. For a single, well-typed operation, prefer a [tool]({{< ref "docs/development/tool_use" >}}) instead.

## Skill Format

A skill is a directory whose name matches the skill, containing a `SKILL.md` file with YAML frontmatter and a Markdown body:

````markdown
---
name: math-calculator
description: Calculate mathematical expressions using shell commands. Use when the user asks to perform arithmetic like addition, subtraction, multiplication, division, or powers.
license: Apache-2.0
compatibility: Requires bash with bc (basic calculator)
---

# Math Calculator Skill

## When to Use
Use this skill whenever the user asks to evaluate a numeric expression.

## Method
Evaluate expressions with the `bc` calculator:

```bash
echo "(2 + 3) * 4" | bc
# Output: 20
```
````

**Frontmatter fields:**

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Skill identifier. 1–64 characters, lowercase letters, numbers and hyphens only (no leading/trailing hyphen). Must match the value referenced in the chat model's `skills` list. |
| `description` | Yes | 1–1024 characters. Loaded at discovery time — write it so the agent can decide *when* to use the skill. State both what it does and when to use it. |
| `license` | No | License of the skill. |
| `compatibility` | No | Free-text note on runtime requirements (e.g. required commands), up to 500 characters. |

The Markdown body is the full instruction set loaded on activation. It may reference bundled files using paths relative to the skill directory (for example `python scripts/gen_joke.py`); those scripts and reference files are loaded only when the agent actually needs them.

A skill source is a directory holding one or more such skill subdirectories (or a `.zip` of that layout):

```
skills/
├── math-calculator/
│   └── SKILL.md
└── joke-generator/
    ├── SKILL.md
    └── scripts/
        └── gen_joke.py
```

## Declare Skills in an Agent

Declare where to load skills from with the `@skills`/`@Skills` decorator/annotation. The method returns a `Skills` resource built with one of its factory methods.

{{< tabs "Declare Skills in Agent" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import skills
from flink_agents.api.skills import Skills


class MathAgent(Agent):

    @skills
    @staticmethod
    def my_skills() -> Skills:
        # Load all skill subdirectories under a local directory.
        return Skills.from_local_dir("/path/to/skills")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.agents.Agent;
// The @Skills annotation and the Skills resource share a simple name but live
// in different packages, so fully-qualify the annotation when importing the class.
import org.apache.flink.agents.api.skills.Skills;

public class MathAgent extends Agent {

    @org.apache.flink.agents.api.annotation.Skills
    public static Skills mySkills() {
        // Load all skill subdirectories from a classpath resource (packaged in the jar).
        return Skills.fromClasspath("skills");
    }
}
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- Use the decorator/annotation to declare a skill source.
  - In Python, use `@skills`.
  - In Java, use `@Skills`.
- Declare more than one `@skills`/`@Skills` method on the same agent to combine sources; the runtime merges them and de-duplicates identical entries.
- Declaring a skill source only makes the skills *available*. A skill is exposed to a chat model only when that model lists it in its `skills` (see [Enable Skills on a Chat Model](#enable-skills-on-a-chat-model)).

### Skill Sources

Each factory method creates a source with a different scheme:

| Factory method (Python / Java) | Scheme | Description |
|--------------------------------|--------|-------------|
| `Skills.from_local_dir(*paths)` / `Skills.fromLocalDir(String...)` | `local` | One or more local directories, or `.zip` files, holding skill subdirectories. The path must be resolvable on the Flink TaskManager that runs the agent. |
| `Skills.from_url(*urls)` / `Skills.fromUrl(String...)` | `url` | One or more `http(s)` URLs, each pointing to a `.zip` whose top level holds the skill subdirectories. |
| `Skills.from_package(*pairs)` | `package` | **Python only.** One or more `(package, resource)` tuples locating skills inside an installed Python package. |
| `Skills.fromClasspath(String...)` | `classpath` | **Java only.** One or more classpath resource paths (e.g. under `src/main/resources/skills`). When packaged into a jar, the resource is materialized to a temp directory at runtime. |

{{< hint info >}}
The `package` scheme is Python-only and the `classpath` scheme is Java-only. A plan written in one language using the other language's scheme deserializes fine, but fails fast at load time. Use `local` or `url` for cross-language skill sources.
{{< /hint >}}

## Enable Skills on a Chat Model

A declared skill becomes usable only when a chat model opts in by listing it in `skills`. When `skills` is set, the framework automatically:

- injects the discovery prompt (the names and descriptions of the listed skills) into the system messages, and
- adds the two built-in tools the agent needs — `load_skill` (to read a skill's full instructions) and `bash` (to run its commands and scripts).

{{< tabs "Enable Skills on a Chat Model" >}}

{{< tab "Python" >}}
```python
@chat_model_setup
@staticmethod
def math_model() -> ResourceDescriptor:
    return ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_SETUP,
        connection="ollama_server",
        model="qwen3.5:9b",
        prompt="system_prompt",
        # Expose declared skills to this model by name.
        skills=["math-calculator"],
        # Whitelist the shell commands the bash tool is allowed to run.
        allowed_commands=["echo", "bc"],
    )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@ChatModelSetup
public static ResourceDescriptor mathModel() {
    return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
            .addInitialArgument("connection", "ollamaChatModelConnection")
            .addInitialArgument("model", "qwen3.5:9b")
            .addInitialArgument("prompt", "systemPrompt")
            // Expose declared skills to this model by name.
            .addInitialArgument("skills", List.of("math-calculator"))
            // Whitelist the shell commands the bash tool is allowed to run.
            .addInitialArgument("allowed_commands", List.of("echo", "bc"))
            .build();
}
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- `skills` lists the skill names (matching the `name` field in each `SKILL.md`) the agent may use.
- `allowed_commands` is a whitelist of shell command names the built-in `bash` tool may execute. Any command not on the list is rejected, so keep it as narrow as the skills require (for example `echo` and `bc` for arithmetic).
- The `load_skill` and `bash` tools are added automatically — you do not declare them in `tools`. They are added alongside any tools you do declare.
- Make sure the system prompt instructs the agent to load the relevant skill before acting, for example: *"You must load the skill first and strictly follow its instructions."* Without this nudge, smaller models may answer directly instead of consulting the skill.

Skills work with both the [Workflow Agent]({{< ref "docs/development/workflow_agent" >}}) (configure the chat model via `@chat_model_setup`/`@ChatModelSetup` as above) and the [ReAct Agent]({{< ref "docs/development/react_agent" >}}) (set `skills` and `allowed_commands` on the `ReActAgent`'s chat model descriptor, and register the `Skills` resource on the execution environment with `add_resource(..., ResourceType.SKILLS, ...)`).

## How Skills Work

Once enabled, a request flows through the three progressive-disclosure stages:

1. **Discovery.** The discovery prompt lists each available skill's `name` and `description` and explains how to load one. This is the only skill content the agent sees by default:
   ```text
   ## Available Skills
   <available_skills>
   <skill>
   <name>math-calculator</name>
   <description>Calculate mathematical expressions using shell commands. Use when ...</description>
   </skill>
   </available_skills>
   ```
2. **Activation.** When the agent decides a skill applies, it calls `load_skill(name="math-calculator")`. The framework returns the full `SKILL.md` body, including the skill's base directory and the absolute paths of its bundled resources.
3. **Execution.** Following the loaded instructions, the agent invokes `bash` to run commands (e.g. `echo "(2 + 3) * 4" | bc`) or bundled scripts (e.g. `python scripts/gen_joke.py`), and loads additional reference files only when an instruction points to them.

This keeps each request lean — a skill the agent never activates costs only its one-line description.
