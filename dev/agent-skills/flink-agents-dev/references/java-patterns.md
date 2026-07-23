# Java Patterns

## Contents

- [Match the Installed API](#match-the-installed-api)
- [Cross-language Descriptor Resources](#cross-language-descriptor-resources)
- [YAML-referenced Implementations](#yaml-referenced-implementations)
- [Function Tools](#function-tools)
- [Java-loaded YAML](#java-loaded-yaml)
- [Programmatic ReActAgent](#programmatic-reactagent)
- [Java Checks](#java-checks)

## Match the Installed API

Inspect the target `pom.xml` or Gradle build, Java version, Flink version, Flink Agents
version, and existing examples. Reuse those versions and scopes. Do not copy versions
from a different branch or release. If no dependency metadata exists, use the
supported choices in `local-development.md` and obtain the user's selection before
writing the POM or source files.

The patterns below are the bundled offline baseline. When target dependencies are
available, inspect the installed Flink Agents JARs, source JARs, and build metadata
for changed signatures or provider integrations. A source checkout is optional.

## Cross-language Descriptor Resources

A direct Java Agent may use Python implementations of chat-model
connections/setups, embedding-model connections/setups, and vector stores. Do not
filter Resource provider choices to Java implementations merely because the Agent,
custom Actions, or entry point are Java.

After the user selects a Python implementation, build the target-version documented
descriptor with the corresponding Java-side Python wrapper and `pythonClazz` set to
the selected Python implementation FQN. Install the matching Python integration
package in the TaskManager Python environment and include the Python bridge runtime.
Do not translate the provider into a Java implementation or ask for a separate
cross-language confirmation. Verify the wrapper map before applying this pattern to
another Resource type.

## YAML-referenced Implementations

Java custom Actions use a public static method with the fixed Event and
`RunnerContext` parameters. YAML supplies those parameter types automatically; do
not add `parameter_types` to an Action. When business behavior is not supplied,
generate compilable skeletons rather than inventing event transformations:

```java
public static void processInput(Event event, RunnerContext ctx) throws Exception {
    throw new UnsupportedOperationException(
            "TODO: implement the application-specific input Action");
}

public static void processChatResponse(Event event, RunnerContext ctx) throws Exception {
    throw new UnsupportedOperationException(
            "TODO: implement the application-specific response Action");
}
```

Implement event construction only after the user supplies the required behavior.
Confirm constructors against the target version and format with the target build.
Reference the methods with `com.example.agent.Actions:processInput` and
`com.example.agent.Actions:processChatResponse`. For an inner class, use `$` on the
left side, for example `com.example.Outer$Actions:processInput`.

## Function Tools

Java Tool methods are public static methods. YAML must provide one ordered Java type
per declared parameter so overloaded methods can be resolved:

```yaml
tools:
  - name: lookupOrder
    type: java
    function: com.example.agent.Tools:lookupOrder
    parameter_types: [java.lang.String]
```

Use primitive names or fully qualified reference types. Omit generic arguments:
write `java.util.List`, not `java.util.List<String>`. Keep method annotations and
parameter metadata consistent with the target version's Tool documentation.

Generate a matching method skeleton by default:

```java
public static String queryLogs(String request) {
    throw new UnsupportedOperationException(
            "TODO: define the request contract and connect the log backend");
}
```

Do not invent clients, endpoints, request/response fields, or error handling unless
the user explicitly supplied that business contract. If only the capability is
known, do not stop to ask for identity fields, platform variants, service APIs, or
authentication. Use `String request` and `String` result as neutral placeholder
boundary types, keep the body explicitly failing, and list the unresolved contract
after scaffolding. This placeholder is not a recommendation for the final domain
API.

## Java-loaded YAML

Missing `type` defaults to Python even in the Java loader. Set `type: java` on every
Java Action, Tool, chat/embedding descriptor, and vector store. Prompts and Skills
use their own schema rather than a language `type`.

Runtime Skill behavior and source configuration are user-owned. Preserve an existing
`classpath`, `paths`, or `urls` source. For a new application, generate a minimal
`SKILL.md` TODO scaffold and list `Skills.fromClasspath(...)`,
`Skills.fromLocalDir(...)`, and `Skills.fromUrl(...)` in a factory TODO; do not ask
the user to select one and do not package or load a source speculatively. Keep the
factory compilable but explicitly failing until configured.

Never inspect the coding-agent host's Skill directories or offer to reuse a local
Skill such as `flink-diag`. Those files are business instructions for another host,
not Java classpath assets. See `yaml-patterns.md#runtime-skills` for the fields the
user can fill later.

Provider aliases and arguments are language-specific. For example, a provider may
use different aliases or camelCase arguments in Java. Copy them from the matching
Java docs or examples; schema validation alone cannot verify forwarded descriptor
arguments.

That rule refers to the selected Resource implementation language, not the Java
application language. For a Python Resource selected by a Java application, use the
Python alias/class and Python argument contract together with the Java-side wrapper
and `pythonClazz` metadata.

### MCP Limitation

In the current source, Java `AgentPlan` rejects MCP servers registered through
`Agent.addResource`, while Java `YamlLoader` maps YAML `mcp_servers` to that path.
Do not generate a Java-loaded YAML application with MCP and describe it as runnable.
Use the documented programmatic `@MCPServer` static method instead, or confirm in the
target source that this restriction no longer exists. Keep the whole Agent in a
supported definition style; do not invent wrapper Agents, loader result accessors,
or YAML-to-MCP adapters.

When this limitation requires the programmatic form, still generate every custom
Action and Tool signature whose framework contract is confirmed. Unknown provider
coordinates, endpoints, and business behavior remain explicit user-fillable
placeholders rather than fabricated implementation or an architecture checklist.

## Programmatic ReActAgent

Do not choose `ReActAgent` merely for a reasoning/tool loop; Workflow Agents already
provide that loop through built-in Actions. When the decision rules do select the
programmatic abstraction, construct it from the user-confirmed chat-model descriptor,
optional Prompt, and optional POJO class or `RowTypeInfo` output schema. This is shape
pseudocode; resolve each value after the Resource interview:

```java
ReActAgent agent =
        new ReActAgent(
                configuredChatModelDescriptor,
                confirmedPromptOrNull,
                confirmedOutputClassOrNull);
```

Build `configuredChatModelDescriptor` after the user selects its implementation.
Generate its verified mandatory arguments as TODOs rather than asking for values.
Register every Resource it references under the exact name before `.apply(agent)`;
do not introduce a provider, model, Prompt, or Tool solely to complete the example.

## Java Checks

- Validate YAML before compilation.
- Compile the exact module with Maven/Gradle and the configured JDK.
- Resolve every Java function reference to a public static method.
- Check each Tool's `parameter_types` count and order against reflection.
- Check every Action uses the framework's fixed Event/`RunnerContext` contract.
- For a Python chat-model, embedding-model, or vector-store implementation, verify
  the Java descriptor wrapper, `pythonClazz`, installed Python integration package,
  and Python resource adapter path. Do not reject it because the application code is
  Java.
- Resolve every generated framework method and constructor in target source, then
  compile it. Label unverified fragments as pseudocode rather than runnable code.
- Run focused unit tests. Only after the user fills a runtime Skill source: inspect
  the JAR for configured classpath Resources, or verify `paths`/`urls` deployment
  preconditions without treating local access as cluster proof.
- Do not claim a Flink job ran from a successful compile or JAR inspection.
- Follow `local-development.md` to generate the Maven project with the API, plan,
  runtime, and required integration artifacts in `provided` scope, then submit the
  bounded remote-style Flink job to a local MiniCluster. Use the public
  `AgentsExecutionEnvironment.getExecutionEnvironment(env)` factory backed by
  `RemoteExecutionEnvironment`; never use a local Agents environment or list APIs.
