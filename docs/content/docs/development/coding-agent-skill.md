---
title: "Build with a Coding Agent"
weight: 8
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

# Build with a Coding Agent

The **flink-agents-dev** Skill gives your coding agent Flink Agents-specific
instructions, reference material, and YAML schemas. Use it to scaffold an
application, modify an existing Agent, or investigate and validate application code.
It covers Java, Python, and YAML authoring, subject to the APIs supported by your
target Flink Agents version.

Install it in your coding agent's environment. It is an optional development
aid; installing it does not install Flink Agents or add a dependency to your Flink
job. For Skills that an Agent loads while running, see
[Runtime Skills]({{< ref "docs/development/calling-llms/skills" >}}).

## Install the Skill

Run the following command and follow the prompts to select your coding agent:

```bash
npx skills add 'apache/flink-agents#{{< branch >}}@flink-agents-dev'
```

This installs the
[flink-agents-dev Skill](https://github.com/apache/flink-agents/tree/{{< branch >}}/dev/agent-skills/flink-agents-dev)
from the `{{< branch >}}` branch, matching this documentation. Use the documentation
for your target Flink Agents version to install the corresponding Skill. Skill
updates can be made on that branch without waiting for a new Flink Agents release.

After installation, explicitly ask your coding agent to use `flink-agents-dev`
when working on a Flink Agents application.

## Use the Skill

Describe your task and provide any choices you have already made: the Flink Agents
and Flink versions, Agent-definition API, application language, and integrations.
For an existing application, let the coding agent inspect its dependencies and code.
The Skill guides the coding agent to resolve missing framework choices before
generating the corresponding files.

When scaffolding a new application, the Skill guides the coding agent to generate:

- Project structure and dependency files for the selected versions and language.
- Agent definitions, Resource declarations, and Action/Event wiring supported by
  built-in behavior or requirements you have supplied.
- Custom Action and Tool signatures, a Flink job entry point, and validation commands
  with a report of the checks performed.

By default, business behavior that you have not specified remains a scaffold. This
includes custom Action and Tool bodies, data transformations, prompts, external
service calls, and runtime Skill instructions. Required model settings, endpoints,
and credentials are marked for configuration when their values are unknown. The
Skill does not invent these details from a high-level application idea. Unimplemented
functions contain focused TODOs and fail explicitly when invoked.

For example, to start a project:

```text
Use the flink-agents-dev Skill to scaffold a Flink Agents application.
Help me choose compatible Flink Agents and Flink versions and an Agent API.
I want an Agent that classifies incoming support requests.
Include the project dependencies, a Flink job entry point, and validation steps.
```

You can fill in the remaining parts yourself or ask the coding agent to implement
them in a follow-up. Provide the business rules, expected inputs and outputs,
service API contracts, and error behavior needed for that implementation. For example:

```text
Continue implementing the scaffold using the classification rules and input/output
examples in the attached specification. Implement the custom Actions and Tools,
and add tests for the specified behavior. Ask me about any missing requirements
before implementing behavior that the specification does not define.
```

For an existing project:

```text
Use the flink-agents-dev Skill to review this application's Agent definition
and custom Actions. Keep the versions in the project dependencies.
Check API usage, Resource references, and Event/Action connections.
Run the available checks and distinguish confirmed failures from checks
that need external services.
```

The Skill's source branch does not change your application's dependency versions.
Tell the coding agent which version the application targets. YAML validation requires
a schema for that target version; a development schema is not a substitute for a
missing release schema.

## Check the result

Review the generated files and the coding agent's validation report before running the
application. Check that:

- Dependencies and API calls match the target Flink Agents and Flink versions.
- Required configuration and unfinished business logic are clearly identified.
- YAML validation, imports or compilation, tests, and actual job execution are
  reported separately. Passing schema validation alone does not establish that an
  application runs.
- Checks requiring models or other external services are marked as untested when
  those services were unavailable.

Use [Setup]({{< ref "docs/get-started/setup" >}}) for environment requirements,
[Choose APIs and Languages]({{< ref "docs/development/create-agent/choose-apis-and-languages" >}})
for API and language choices, and
[Create an Agent]({{< ref "docs/development/create-agent/overview" >}})
for the concepts behind generated code. For YAML applications, use the
[YAML API reference]({{< ref "docs/development/create-agent/yaml" >}}); for job wiring,
see [Integrate with Flink]({{< ref "docs/development/integrate_with_flink" >}}).
