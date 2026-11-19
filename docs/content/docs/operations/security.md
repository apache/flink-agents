---
title: Security & Threat Model
weight: 6
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

# Security & Threat Model

Apache Flink Agents executes application-defined agents that use model services,
tools, skills, and external systems. This page describes the trust assumptions,
security boundaries, and deployment responsibilities for Java, Python, and
YAML-defined agents. When running on Flink, the
[Flink security model](https://flink.apache.org/what-is-flink/security/) also applies.

## Trust Assumptions

Flink Agents assumes that **both the input data processed by the agent and the
model service it invokes are trusted**. Application developers and operators are
also trusted to select the agent code, configuration, tools, skills, dependencies,
and external services used by the application.

The input-data assumption covers the model's context, including user messages,
stream records, retrieved documents, memory, and tool responses. Data does not
become trusted merely because it arrives through a trusted connector or is stored
in an internal system. Applications incorporating external user content or other
potentially adversarial material must evaluate and control that exposure.
Flink Agents does not guarantee protection against prompt injection from
adversarial content.

A trusted model service can still produce incorrect or unexpected output,
including unintended tool calls.

## Security Boundaries and Limitations

Agent code and local tools execute with the permissions of the process running
them. In a Flink deployment, local tools run on the TaskManager. Flink Agents
provides neither an operating-system sandbox nor security isolation between
agents sharing that execution environment.

### Bash Command Execution

Bash command execution is disabled by default. Applications can enable it for a
model by adding the built-in `bash` tool to the model's `tools` list.

Once enabled, commands execute with the permissions of the process running the
agent. The tool's `allowed_commands` and `allowed_script_dirs` settings control which
commands or scripts may be executed; they do not comprehensively restrict what
permitted programs, interpreters, scripts, or their subprocesses can do. In
particular, `allowed_script_dirs` does not restrict which files a running program
may access. Applications requiring filesystem, network, or process isolation must
enforce those restrictions through the execution environment.

### Security Boundary Reference

The following table summarizes the framework's constraints and their limits.

| Boundary | What Flink Agents enforces | What it does not provide |
|----------|---------------------------|--------------------------|
| Model tool access | Model-generated calls are restricted to tools and sub-agents enabled for that model. | Isolation of the operations performed by an enabled tool. |
| Application-controlled permissions | Model-generated tool arguments cannot override application-controlled permission settings. | A guarantee that every operation permitted by those settings is appropriate for the application. |
| Bash command execution | Commands are checked against the configured command and script-directory rules. | Filesystem, network, or process isolation for permitted programs. |

## Application and Operator Responsibilities

- Select trusted model providers, tools, skills, and external services, and review
  their permissions and data-handling policies. Only send data that those services
  are authorized to receive.
- Grant the runtime and its tools only the filesystem, network, and credential
  access the application needs. Enforce stronger isolation through the execution
  environment, such as appropriately configured containers or virtual machines
  and network access controls.
- Enforce application authorization before sensitive operations. If human approval
  is required, implement that approval in the application; a prompt asking the
  model to seek permission is not an authorization mechanism.
- Protect cluster interfaces and external services with appropriate access
  controls. Protect credentials, checkpoints, memory stores, logs, and traces;
  these may contain application data, prompts, or tool results.

## Reporting and Handling Security Issues

Report suspected undisclosed vulnerabilities privately to the
[ASF Security Team](mailto:security@apache.org), following the
[ASF reporting guidance](https://www.apache.org/security/). Include the affected
version and configuration, expected and observed behavior, and the trust boundary
you believe is violated. Do not disclose the report in a public issue or pull
request. If you are unsure whether a report is security-related, use the private
channel first.

The project evaluates reports against the affected version's documented behavior,
trust assumptions, and impact. Reports of execution without application
authorization or other violations of supported security boundaries warrant
investigation. BashTool and other components remain subject to security review;
implementation defects that do not cross a supported security boundary may be
handled as ordinary bugs or security hardening improvements.
Validation that does not behave as documented remains an implementation defect
to investigate and fix; its security classification depends on the affected
boundary and impact.
