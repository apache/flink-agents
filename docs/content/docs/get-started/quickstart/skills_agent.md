---
title: 'Skills'
weight: 3
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

A [Skill]({{< ref "docs/development/skills" >}}) is a self-contained package of instructions, and optionally scripts and reference files, that teaches the agent how to perform a specialized task. Skills are loaded with *progressive disclosure*: only each skill's name and description are shown to the agent up front, and the full instructions are pulled in on demand when the agent decides a skill is relevant.

This quickstart builds a small streaming agent that answers arithmetic questions. Rather than letting the LLM compute by itself, the agent exposes a `math-calculator` skill; for each question the agent loads the skill and follows its instructions to compute the result with the `bc` calculator through the built-in `bash` tool. It demonstrates the full skill lifecycle — discovery, activation, and execution — in a Flink streaming job.

## Code Walkthrough

### Define the Skill

A skill is a directory containing a `SKILL.md` file with YAML frontmatter (loaded at discovery time) and a Markdown body (loaded on activation). Here is `skills/math-calculator/SKILL.md`:

````markdown
---
name: math-calculator
description: Calculate mathematical expressions using shell commands. Use when the user asks to perform arithmetic calculations like addition, subtraction, multiplication, division, or powers.
license: Apache-2.0
compatibility: Requires bash with bc (basic calculator)
---

# Math Calculator Skill

## When to Use
Use this skill when the user asks to evaluate a numeric expression.

## Method
Pipe the expression into the `bc` (basic calculator) command:

```bash
echo "(2 + 3) * 4" | bc
# Output: 20
```
````

### Create the Agent

The agent declares where to load skills from with the `@skills`/`@Skills` decorator/annotation, and enables the skill on its chat model by listing it in `skills` together with the `allowed_commands` whitelist for the `bash` tool. For more details, please refer to the [Skills]({{< ref "docs/development/skills" >}}) documentation.

{{< tabs "Create the Agent" >}}

{{< tab "Python" >}}
```python
class MathAgent(Agent):
    """An agent that answers arithmetic questions using the math-calculator skill."""

    @skills
    @staticmethod
    def my_skills() -> Skills:
        """Declare where to load skills from."""
        # Skills are bundled under this example package, loaded by package name.
        return Skills.from_package(
            ("flink_agents.examples.quickstart", "resources/skills")
        )

    @prompt
    @staticmethod
    def system_prompt() -> Prompt:
        """System prompt instructing the agent to use the skill."""
        return Prompt.from_messages(
            messages=[
                ChatMessage(
                    role=MessageRole.SYSTEM,
                    content="You are a helpful math assistant. Use the "
                    "math-calculator skill when asked to evaluate an expression. "
                    "You must load the skill first and strictly follow its "
                    "instructions. Reply with only the final numeric result.",
                )
            ],
        )

    @chat_model_setup
    @staticmethod
    def math_model() -> ResourceDescriptor:
        """ChatModel with the math-calculator skill enabled."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model="qwen3:8b",
            prompt="system_prompt",
            # Expose the declared skill to this model by name.
            skills=["math-calculator"],
            # Whitelist the shell commands the built-in bash tool may run.
            allowed_commands=["echo", "bc"],
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Process input event and send a chat request to evaluate the question."""
        question: str = InputEvent.from_event(event).input
        ctx.send_event(
            ChatRequestEvent(
                model="math_model",
                messages=[ChatMessage(role=MessageRole.USER, content=question)],
            )
        )

    @action(ChatResponseEvent.EVENT_TYPE)
    @staticmethod
    def process_chat_response(event: Event, ctx: RunnerContext) -> None:
        """Process chat response event and send the answer as output."""
        chat_response = ChatResponseEvent.from_event(event)
        ctx.send_event(OutputEvent(output=chat_response.response.content))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
/** An agent that answers arithmetic questions using the math-calculator skill. */
public class MathAgent extends Agent {

    /** Load skills from the skills/ directory packaged on the classpath. */
    @org.apache.flink.agents.api.annotation.Skills
    public static Skills mySkills() {
        return Skills.fromClasspath("skills");
    }

    /** System prompt instructing the agent to use the skill. */
    @Prompt
    public static org.apache.flink.agents.api.prompt.Prompt systemPrompt() {
        return org.apache.flink.agents.api.prompt.Prompt.fromMessages(
                Collections.singletonList(
                        new ChatMessage(
                                MessageRole.SYSTEM,
                                "You are a helpful math assistant. Use the math-calculator skill "
                                        + "when asked to evaluate an expression. You must load the "
                                        + "skill first and strictly follow its instructions. Reply "
                                        + "with only the final numeric result.")));
    }

    /** ChatModel with the math-calculator skill enabled. */
    @ChatModelSetup
    public static ResourceDescriptor mathModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("prompt", "systemPrompt")
                // Expose the declared skill to this model by name.
                .addInitialArgument("skills", List.of("math-calculator"))
                // Whitelist the shell commands the built-in bash tool may run.
                .addInitialArgument("allowed_commands", List.of("echo", "bc"))
                .build();
    }

    /** Process input event and send a chat request to evaluate the question. */
    @Action(EventType.InputEvent)
    public static void processInput(InputEvent event, RunnerContext ctx) {
        ctx.sendEvent(
                new ChatRequestEvent(
                        "mathModel",
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput()))));
    }

    /** Process chat response event and send the answer as output. */
    @Action(EventType.ChatResponseEvent)
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getResponse().getContent()));
    }
}
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- `@skills`/`@Skills` declares a skill source. `Skills.from_package` (Python) loads skills bundled inside an installed package by `(package, resource)`; `Skills.fromClasspath` (Java) loads them from a classpath resource packaged in the jar.
- A declared skill is exposed to a model only when the model lists it in `skills`. The `load_skill` and `bash` tools are then added automatically.
- `allowed_commands` whitelists the shell commands the `bash` tool may run — keep it as narrow as the skill requires.

### Integrate the Agent with Flink

Register the Ollama chat model connection, create a stream of questions, and apply the agent.

{{< tabs "Integrate the Agent with Flink" >}}

{{< tab "Python" >}}
```python
# Set up the Flink streaming environment and the Agents execution environment.
env = StreamExecutionEnvironment.get_execution_environment()
agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

# Add Ollama chat model connection to be used by the MathAgent.
agents_env.add_resource(
    "ollama_server", ResourceType.CHAT_MODEL_CONNECTION, ollama_server_descriptor
)

# A small stream of arithmetic questions to answer.
question_stream = env.from_collection(
    ["What is (2 + 3) * 4?", "Compute 2 ^ 10.", "What is 144 divided by 12?"]
)

# Use the MathAgent to answer each question with the math-calculator skill.
answer_stream = (
    agents_env.from_datastream(input=question_stream, key_selector=lambda x: x)
    .apply(MathAgent())
    .to_datastream()
)

# Print the answers to stdout, then execute the Flink pipeline.
answer_stream.print()
agents_env.execute()
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Set up the Flink streaming environment and the Agents execution environment.
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.setParallelism(1);
AgentsExecutionEnvironment agentsEnv =
        AgentsExecutionEnvironment.getExecutionEnvironment(env);

// Add Ollama chat model connection to be used by the MathAgent.
agentsEnv.addResource(
        "ollamaChatModelConnection",
        ResourceType.CHAT_MODEL_CONNECTION,
        CustomTypesAndResources.OLLAMA_SERVER_DESCRIPTOR);

// A small stream of arithmetic questions to answer.
DataStream<String> questionStream =
        env.fromData("What is (2 + 3) * 4?", "Compute 2 ^ 10.", "What is 144 divided by 12?");

// Use the MathAgent to answer each question with the math-calculator skill.
DataStream<Object> answerStream =
        agentsEnv.fromDataStream(questionStream).apply(new MathAgent()).toDataStream();

// Print the answers to stdout, then execute the Flink pipeline.
answerStream.print();
agentsEnv.execute();
```
{{< /tab >}}

{{< /tabs >}}

## Run the Example

### Prerequisites

* Unix-like environment (we use Linux, Mac OS X, Cygwin, WSL)
* Git
* Java 11+
* Python 3.10, 3.11 or 3.12
* `bc` (basic calculator), used by the `math-calculator` skill — preinstalled on most Unix-like systems

### Preparation

#### Prepare Flink and Flink Agents

Follow the [installation]({{< ref "docs/get-started/installation" >}}) instructions to setup Flink and the Flink Agents.

#### Clone the Flink Agents Repository (if not done already)

```bash
git clone https://github.com/apache/flink-agents.git
cd flink-agents
```

{{< hint info >}}
For python examples, you can skip this step and submit the python file in installed flink-agents wheel.
{{< /hint >}}

#### Deploy a Standalone Flink Cluster

You can deploy a standalone Flink cluster in your local environment with the following command.

{{< tabs "Deploy a Standalone Flink Cluster" >}}

{{< tab "Python" >}}
```bash
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')
$FLINK_HOME/bin/start-cluster.sh
```
{{< /tab >}}

{{< tab "Java" >}}
1. Build Flink Agents from source to generate example jar. See [installation]({{< ref "docs/get-started/installation" >}}) for more details.
2. Start the Flink cluster
    ```bash
    $FLINK_HOME/bin/start-cluster.sh
    ```

{{< hint info >}}
To run example on JDK 21+, append jvm option `--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED` to [env.java.opts.all](https://nightlies.apache.org/flink/flink-docs-stable/docs/deployment/config/#env-java-opts-all) in `$FLINK_HOME/conf/config.yaml` before starting the Flink cluster.
{{< /hint >}}
{{< /tab >}}

{{< /tabs >}}
You can refer to the [local cluster](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/try-flink/local_installation/#starting-and-stopping-a-local-cluster) instructions for more detailed steps.

{{< hint info >}}
If you can't navigate to the web UI at [localhost:8081](localhost:8081), you can find the reason in `$FLINK_HOME/log`. If the reason is port conflict, you can change the port in `$FLINK_HOME/conf/config.yaml`.
{{< /hint >}}

#### Prepare Ollama

Download and install Ollama from the official [website](https://ollama.com/download).

{{< hint info >}}
Ollama server **0.9.0** or higher is required.
{{< /hint >}}

Then pull the qwen3:8b model, which is required by the quickstart examples.

```bash
ollama pull qwen3:8b
```

### Submit Flink Agents Job to Standalone Flink Cluster

#### Submit to Flink Cluster

{{< tabs "Submit to Flink Cluster" >}}

{{< tab "Python" >}}
```bash
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')

# Run the agent skills example
$FLINK_HOME/bin/flink run -py ./flink-agents/python/flink_agents/examples/quickstart/skills_agent_example.py
# or submit the example python file in installed flink-agents wheel
$FLINK_HOME/bin/flink run -py  $PYTHONPATH/flink_agents/examples/quickstart/skills_agent_example.py
```
{{< /tab >}}

{{< tab "Java" >}}
```bash
$FLINK_HOME/bin/flink run -c org.apache.flink.agents.examples.SkillsAgentExample ./flink-agents/examples/target/flink-agents-examples-$VERSION.jar
```
{{< /tab >}}

{{< /tabs >}}

Now you should see a Flink job submitted to the Flink Cluster in Flink web UI [localhost:8081](localhost:8081).

After a few minutes, you can check for the output in the TaskManager output log.
