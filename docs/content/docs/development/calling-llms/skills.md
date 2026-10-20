---
title: "Skills"
weight: 7
type: docs
aliases:
  - /docs/development/calling-llms/skills/example/
  - /docs/get-started/quickstart/skills_agent/
  - /docs/development/skills/
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

A **Skill** is a directory containing instructions in `SKILL.md`, optionally with
scripts and reference files. Use skills for procedures the model should follow;
use a [tool]({{< ref "docs/development/calling-llms/tool_use" >}}) for a single,
well-defined operation.

See the official [Agent Skills overview](https://agentskills.io/home) for an
introduction to the format and its use cases.

Skills use **progressive disclosure**: the model first receives their names and
descriptions, then calls `load_skill` to read the instructions for a relevant
skill. It can request additional files and use the built-in `bash` tool to run
permitted commands. This limits how much skill content enters the model's context.

The following example defines a `math-calculator` skill, registers its source,
and enables it on a chat model. The [complete example](#complete-example) links
to the Agent and Flink job source.

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
| `name` | Required | Skill identifier. 1–64 characters, lowercase letters, numbers and hyphens only (no leading/trailing hyphen). Must match the value referenced in the chat model's `skills` list. |
| `description` | Required | 1–1024 characters. Loaded at discovery time — write it so the agent can decide *when* to use the skill. State both what it does and when to use it. |
| `license` | Optional | License of the skill. |
| `compatibility` | Optional | Free-text note on runtime requirements (e.g. required commands), up to 500 characters. |
| `metadata` | Optional | Additional metadata as string key-value pairs. |

The Markdown body contains the instructions returned to the model by `load_skill`. It may reference bundled scripts and reference files using paths relative to the skill directory; see [Using Skill Files and Commands](#using-skill-files-and-commands).

A skill source is a directory holding one or more such skill subdirectories (or a `.zip` of that layout):

```
skills/
└── math-calculator/
    ├── SKILL.md
    ├── references/
    │   └── formulas.md
    └── scripts/
        └── calculate.py
```

## Declare Skills in an Agent

Register a `Skills` object as a `SKILLS` Resource to declare where to load skills
from. The example below uses the Declaration API with `@skills` / `@Skills` and
returns a `Skills` object built with one of its factory methods. See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for other registration methods and scopes.

{{< tabs "Declare Skills in Agent" >}}

{{< tab "Python" >}}
```python
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

Declare multiple `@skills` / `@Skills` methods to combine sources. Identical source
entries are de-duplicated. Java processes these methods by method name; Python
uses declaration order. If sources contain the same skill name, the later source
wins.

The resource name (`my_skills` / `mySkills`) identifies the source declaration.
The model selects individual skills by their `SKILL.md` names, such as
`math-calculator`, as shown below.

<span id="configure-the-prompt"></span>
<span id="configure-the-model"></span>

## Enable Skills on a Chat Model

Specify the skills you want the model to choose from in the chat model setup's
`skills` list, using the names defined in their `SKILL.md` files.

The example below enables one skill, `math-calculator`. You can list multiple
skills; the model uses their descriptions to select the relevant skills for the
current task.

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
            // Expose declared skills to this model by name.
            .addInitialArgument("skills", List.of("math-calculator"))
            // Whitelist the shell commands the bash tool is allowed to run.
            .addInitialArgument("allowed_commands", List.of("echo", "bc"))
            .build();
}
```
{{< /tab >}}

{{< /tabs >}}

When skills are enabled:

- The framework includes their names and descriptions in the system messages.
- The framework adds `load_skill` and `bash` alongside any configured tools.
  You do not need to register them yourself. The model must support tool calling.
- `allowed_commands` permits the shell commands used by the skill, here `echo`
  and `bc`. For other command and script permissions, see
  [Using Skill Files and Commands](#using-skill-files-and-commands).

Call the configured model using either chat events or `ctx.chat`, as described in
[Chat with Models]({{< ref "docs/development/calling-llms/chat" >}}).
For constructor-based Skills configuration, see
[Built-in ReAct Agent Skills]({{< ref "docs/development/react_agent#skills" >}}).

## Skill Sources

Use these factories in a Skills declaration to select where its files come from.
Directories and ZIP archives must contain the skill subdirectories shown in
[Skill Format](#skill-format).

| Source | Python factory | Java factory | Runtime requirement |
|--------|----------------|--------------|---------------------|
| Local directory or ZIP | `Skills.from_local_dir(*paths)` | `Skills.fromLocalDir(String...)` | Paths must be accessible on each TaskManager running the agent. |
| Remote ZIP | `Skills.from_url(*urls)` | `Skills.fromUrl(String...)` | TaskManagers must be able to download the archives over HTTPS. |
| Python package | `Skills.from_package((package, resource), ...)` | `Skills.fromPackage(package, resource)` | The package and its resource files must be installed in the task's Python environment. |
| Java classpath | `Skills.from_classpath(*resources)` | `Skills.fromClasspath(String...)` | The containing JARs or resource directories must be on the job's classpath. |

Sources are opened and `SKILL.md` files are parsed when the runtime initializes
Skills. Remote archives are downloaded and extracted at that time, even if the
model never activates a skill. Progressive disclosure controls what is sent to
the model. Sources are not automatically refreshed while the job is running.

### Remote Sources

HTTPS is the default and provides server authentication and transport integrity.
To also pin the expected archive content, supply an independently obtained
SHA-256 digest:

| Option | Python factory | Java factory |
|--------|----------------|--------------|
| HTTPS with a digest | `Skills.from_url_with_sha256(url, digest)` | `Skills.fromUrlWithSha256(url, digest)` |
| Allow HTTP | `Skills.from_url_unsafe(*urls)` | `Skills.fromUrlUnsafe(String...)` |
| Allow HTTP with a digest | `Skills.from_url_unsafe_with_sha256(url, digest)` | `Skills.fromUrlUnsafeWithSha256(url, digest)` |

A digest is optional. When supplied, it must contain 64 hexadecimal characters;
uppercase and lowercase are accepted. A mismatch fails loading before extraction.
In YAML, use `url_sources` for `sha256` or `allow_insecure_http: true`; see the
[Skills fields in the YAML API]({{< ref "docs/development/create-agent/yaml#resources" >}}).

URLs must not contain embedded user information. Use DNS-compatible ASCII hostnames
(`skill-server`, not `skill_server`), Punycode for internationalized hostnames, and
percent-encoding for spaces. These rules also apply to redirect targets; scoped
IPv6 addresses with zone identifiers are unsupported.

{{< hint warning >}}
Use skill sources you trust: their instructions influence the model, and their
scripts may run with the task's permissions. HTTPS and digest checks do not make
untrusted content safe. Enable plain HTTP only on trusted development networks.
{{< /hint >}}

### Java Classpath and Python Packages

Both `classpath` and `package` sources work from Java and Python agents running
in Flink. For example, replace the factory in the declaration above with:

{{< tabs "Cross-Language Skill Sources" >}}
{{< tab "Python using a JAR" >}}
```python
Skills.from_classpath("skills")
```

Package `skills/math-calculator/SKILL.md` and any supporting files in a JAR, for
example from `src/main/resources/skills/`. Add that JAR to the Flink job's
classpath. The path `skills` is relative to the classpath root, not the local
filesystem.
{{< /tab >}}
{{< tab "Java using a Python package" >}}
```java
Skills.fromPackage("my_skills_pkg", "skills");
```

Include `skills/math-calculator/SKILL.md` and its supporting files as package data
in `my_skills_pkg`, and install the package in the task's Python environment.
The path `skills` is relative to the package root. This source requires the Flink
Agents Python dependencies and Python runtime even if all Actions and model
providers are implemented in Java.
{{< /tab >}}
{{< /tabs >}}

See [API and Language Choices]({{< ref "docs/development/create-agent/choose-apis-and-languages#integrate-the-agent-into-a-flink-job" >}})
for Java/Python dependencies and
[Deployment]({{< ref "docs/operations/deployment#run-in-flink" >}}) for job submission.
YAML supports the same `classpath` and `package` sources; see the
[YAML API]({{< ref "docs/development/create-agent/yaml#resources" >}}).
Missing resources or required runtime dependencies fail source loading.

<span id="how-skills-work"></span>

## Using Skill Files and Commands

`load_skill(name="math-calculator")` returns the instruction body, the skill's
base directory, and its resource paths.
`load_skill(name="math-calculator", path="references/formulas.md")` reads the bundled
reference file shown in [Skill Format](#skill-format). The `path` is relative to
the skill directory.

The `bash` tool runs commands under the following chat model settings:

| Setting | Required | Description |
|---------|----------|-------------|
| `allowed_commands` | Optional | Command names the tool may run, such as `echo` and `bc`. Defaults to an empty list. |
| `allowed_script_dirs` | Optional | Additional directories from which scripts may be executed. Defaults to an empty list; the framework also permits the directories of the skills enabled on this model. |

Each command must either be on the command list or resolve to a path under a
permitted script directory. Directly executed scripts need executable permissions;
all scripts need their runtime dependencies. To run `python scripts/calculate.py`, allow
`python` and set the tool's `cwd` argument to the skill's base directory returned
by `load_skill`. An explicit `cwd` must be within a permitted script directory;
without it, the tool uses the task process's working directory.

{{< hint warning >}}
These checks are not an operating-system sandbox. Allowing an interpreter such as
`python` permits more than running the bundled script. Grant only the commands
and directories needed by trusted skills.
{{< /hint >}}

File redirects are rejected; descriptor duplication and closure such as `2>&1`
and `2>&-` are allowed. Assignments to environment variables that change command
resolution or executable loading, such as `PATH`, `BASH_ENV`, `LD_*`, and `DYLD_*`,
are rejected. Required commands and dependencies must already be installed on
the TaskManagers; the `compatibility` field documents requirements but does not
install them.

## Complete Example

The complete example sends arithmetic questions such as `What is (2 + 3) * 4?` to `MathAgent`. Its model loads `math-calculator`, uses `bash` to run `bc`, and returns the numeric result through the normal chat response path. For the chat request and response Actions, see [Chat with Models]({{< ref "docs/development/calling-llms/chat" >}}).

| Language | Example job | Agent definition | Skill instructions |
|----------|-------------|------------------|--------------------|
| Python | {{< gh_link file="python/flink_agents/examples/quickstart/skills_agent_example.py" name="skills_agent_example.py" >}} | {{< gh_link file="python/flink_agents/examples/quickstart/agents/math_agent.py" name="MathAgent" >}} | {{< gh_link file="python/flink_agents/examples/quickstart/resources/skills/math-calculator/SKILL.md" name="SKILL.md" >}} |
| Java | {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/SkillsAgentExample.java" name="SkillsAgentExample.java" >}} | {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/agents/MathAgent.java" name="MathAgent" >}} | {{< gh_link file="examples/src/main/resources/skills/math-calculator/SKILL.md" name="SKILL.md" >}} |

The Python example loads its bundled skill through `Skills.from_package`; the Java example uses `Skills.fromClasspath`. Make `bash` and `bc` available on the TaskManagers and prepare the Ollama model configured by the example (`qwen3.5:9b`).

Follow [Setup]({{< ref "docs/get-started/setup" >}}) for dependencies and [Build Your First Agent]({{< ref "docs/get-started/quickstart/create-agent/single-agent#run-the-example" >}}) for the build and submission workflow, using the corresponding Skills example job above. See [Integrate with Flink]({{< ref "docs/development/integrate_with_flink" >}}) for stream integration details.
