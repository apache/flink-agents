---
title: ReAct Agent
weight: 1
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

ReAct Agent is a general paradigm that combines reasoning and action capabilities to solve complex tasks. Leveraging this paradigm, the user only needs to specify the goal with prompt and provide available tools, and the LLM will decide how to achieve the goal and take actions autonomously

{{< hint info >}}
For guidance on choosing Java or Python, see [Should I choose Java or Python?]({{< ref "docs/faq/faq#q3-should-i-choose-java-or-python" >}}).
{{< /hint >}}

## ReAct Agent Example

{{< tabs "ReAct Agent Example" >}}

{{< tab "Python" >}}
```python
my_react_agent = ReActAgent(
    chat_model=chat_model_descriptor,
    prompt=my_prompt,
    output_schema=MyBaseModelDataType, # or output_schema=my_row_type_info
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ReActAgent myReActAgent =
        new ReActAgent(
                chatModelDescriptor,
                myPrompt,
                MyBaseModelDataType.class
                // or myRowTypeInfo
        );
```
{{< /tab >}}

{{< /tabs >}}

## Initialize Arguments
### Chat Model
User should specify the chat model used in the ReAct Agent.

We use `ResourceDescriptor` to describe the chat model, includes chat model type and chat model arguments. See [Chat Model]({{< ref "docs/development/chat_models" >}}) for more details.

{{< tabs "ChatModel ResourceDescriptor" >}}

{{< tab "Python" >}}
```python
chat_model_descriptor = ResourceDescriptor(
    clazz=Constant.OLLAMA_CHAT_MODEL_SETUP,
    connection="my_ollama_connection",
    model="qwen3:8b",
    tools=["my_tool1, my_tool2"],
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ResourceDescriptor chatModelDescriptor =
                ResourceDescriptor.Builder.newBuilder(Constant.OLLAMA_CHAT_MODEL_SETUP)
                        .addInitialArgument("connection", "myOllamaConnection")
                        .addInitialArgument("model", "qwen3:8b")
                        .addInitialArgument(
                                "tools", List.of("myTool1", "myTool2"))
                        .build();
```
{{< /tab >}}

{{< /tabs >}}

### Prompt
User can provide prompt to instruct agent. 

The prompt should contain two messages. The first message tells the agent what to do, and gives the input and output example. The second message tells how to convert input element to text string.

{{< tabs "Prompt" >}}

{{< tab "Python" >}}
```python
system_prompt_str = """
    Analyze 
    ...
    
    Example input format: 
    ...
    
    Ensure your response can be parsed by Python JSON, using this format as an example:
    ...
    """
    
# Prompt for review analysis react agent.
my_prompt = Prompt.from_messages(
    messages=[
        ChatMessage(
            role=MessageRole.SYSTEM,
            content=system_prompt_str,
        ),
        # For react agent, if the input element is not primitive types,
        # framework will deserialize input element to dict and fill the prompt.
        # Note, the input element should be primitive types, BaseModel or Row.
        ChatMessage(
            role=MessageRole.USER,
            content="""
            "id": {id},
            "review": {review}
            """,
        ),
    ],
) 
```
{{< /tab >}}

{{< tab "Java" >}}
```java
String systemPromptString =
        "Analyze ..."
                + "Example input format:\n"
                + "..."
                + "Ensure your response can be parsed by Java JSON, using this format as an example:\n"
                + "...";
    
// Prompt for review analysis react agent.
Prompt myPrompt = Prompt.fromMessages(
        Arrays.asList(
                new ChatMessage(MessageRole.SYSTEM, systemPromptString),
                new ChatMessage(
                        MessageRole.USER,
                        "{\"id\": \"{id}\",\n" + "\"review\": \"{review}\"}")));
```
{{< /tab >}}

{{< /tabs >}}

If the input element is primitive types, like `int`, `str` and so on, the second message should be

{{< tabs "Prepare Agents Execution Environment" >}}

{{< tab "Python" >}}
```python
ChatMessage(
    role=MessageRole.USER,
    content="{input}"
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
new ChatMessage(MessageRole.USER, "{input}")
```
{{< /tab >}}

{{< /tabs >}}

See [Prompt]({{< ref "docs/development/prompts" >}}) for more details.

### Output Schema
User can set output schema to configure the ReAct Agent output type. If output schema is set, the ReAct Agent will deserialize the llm response to expected type. 

The output schema should be `BaseModel` or `RowTypeInfo`.

{{< tabs "Output Schema" >}}

{{< tab "Python" >}}
```python
class MyBaseModelDataType(BaseModel):
    id: str
    score: int
    reasons: list[str]

# Currently, for RowTypeInfo, only support BasicType fields.
my_row_type_info = RowTypeInfo(
        [BasicTypeInfo.STRING_TYPE_INFO(), BasicTypeInfo.INT_TYPE_INFO()],
        ["id", "score"],
    )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@JsonSerialize
@JsonDeserialize
public static class MyBaseModelDataType {
    private final String id;
    private final int score;
    private final List<String> reasons;

    @JsonCreator
    public ProductReviewAnalysisRes(
            @JsonProperty("id") String id,
            @JsonProperty("score") int score,
            @JsonProperty("reasons") List<String> reasons) {
        this.id = id;
        this.score = score;
        this.reasons = reasons;
    }

    public ProductReviewAnalysisRes() {
        id = null;
        score = 0;
        reasons = List.of();
    }

    public String getId() {
        return id;
    }

    public int getScore() {
        return score;
    }

    public List<String> getReasons() {
        return reasons;
    }

    @Override
    public String toString() {
        return String.format(
                "ProductReviewAnalysisRes{id='%s', score=%d, reasons=%s}", id, score, reasons);
    }
}

// Currently, for RowTypeInfo, only support BasicType fields.
RowTypeInfo myRowTypeInfo =
        new RowTypeInfo(
                new TypeInformation[] {
                        BasicTypeInfo.STRING_TYPE_INFO, BasicTypeInfo.INT_TYPE_INFO
                },
                new String[] {"id", "score"});
```
{{< /tab >}}

{{< /tabs >}}

