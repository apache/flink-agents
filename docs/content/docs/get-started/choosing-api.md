---
title: 'Choosing Between Java API and Python API'
weight: 2
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

Apache Flink Agents provides native APIs in both **Java** and **Python**, allowing you to build streaming agents in your preferred language. Both APIs expose the same core functionality and integrate seamlessly with Flink's distributed processing engine. This guide helps you understand the strengths of each approach and make an informed decision for your project.

---

## Java API

### Advantages

**Performance and Scalability**
- Compiled bytecode executes significantly faster than interpreted Python, resulting in lower latency and higher throughput for compute-intensive workloads.
- Direct memory management and JVM optimizations enable efficient processing of large-scale streams with predictable garbage collection behavior.
- Better suited for processing high-frequency data streams (millions of events per second) with strict latency requirements.

**Type Safety and Compile-Time Guarantees**
- Static typing catches errors at compile time, preventing entire classes of runtime bugs.
- IDE support provides excellent autocomplete, refactoring, and inline documentation.
- Explicit type declarations make code more maintainable and self-documenting.

**Concurrency and Threading**
- Native support for fine-grained concurrency control through threads, locks, and concurrent data structures.
- Mature ecosystems for parallel execution and reactive programming (e.g., Project Reactor, Netty).
- Superior performance when handling concurrent I/O operations.

**Production Readiness**
- Extensively tested in mission-critical systems at enterprise scale for decades.
- Rich ecosystem of monitoring, profiling, and debugging tools (JProfiler, YourKit, async-profiler).
- Strong backward compatibility and long-term support from a mature platform.
- Robust error handling and exception transparency for troubleshooting.

**Ecosystem and Integration**
- Access to the vast Java ecosystem, including enterprise libraries, frameworks, and tools.
- Seamless integration with existing Java-based infrastructure and systems.
- Mature solutions for caching, security, and data access patterns.

### Disadvantages

**Development Speed**
- More verbose syntax requires more boilerplate code compared to Python.
- Longer iteration cycles due to compilation step and classpath management.
- Higher cognitive load for developers unfamiliar with the JVM.

**ML/AI Integration**
- Limited availability of advanced ML/AI libraries compared to Python; many cutting-edge models prioritize Python support.
- Model inference and training tools often require Python bridges or external services.
- Fewer pre-built integrations with popular ML platforms and services.

**Learning Curve**
- Java concepts (generics, annotation processors, dependency injection, build systems like Maven) have a steeper learning curve.
- Requires understanding JVM concepts and tooling (classpath, bytecode, heap management).

**Dependency Management**
- Complex dependency resolution can lead to version conflicts and classpath issues (JAR Hell).
- Maven/Gradle build systems, while powerful, introduce additional complexity.

---

## Python API

### Advantages

**Rapid Prototyping and Development**
- Dynamic typing and concise syntax enable faster development cycles and quick experimentation.
- Minimal boilerplate code allows developers to focus on business logic.
- Immediate feedback without compilation steps speeds up iteration.

**Ease of Use**
- Lower barrier to entry; developers can be productive immediately without deep platform knowledge.
- Simple, readable syntax that emphasizes clarity and maintainability.
- Extensive inline help and community documentation.

**ML/AI Ecosystem**
- Access to industry-leading ML/AI libraries: NumPy, pandas, scikit-learn, TensorFlow, PyTorch, LangChain, Hugging Face.
- Native support for vector operations and mathematical computing.
- Rich integrations with LLM providers, embedding models, and vector databases.
- Ideal for agents that incorporate ML models, embeddings, and advanced NLP techniques.

**Scripting and Experimentation**
- Excellent for data exploration, ad hoc analysis, and interactive development.
- Jupyter notebooks and interactive shells enable exploratory workflows.
- Quick adaptation to changing requirements and experimental features.

**Developer Productivity**
- Shorter development time for data-driven and ML-centric applications.
- Strong community support with abundant libraries and frameworks tailored for AI/ML.
- Excellent tooling for prototyping and testing ideas quickly.

### Disadvantages

**Performance Overhead**
- Interpreted execution is slower than compiled Java bytecode, resulting in higher latency and lower throughput for compute-intensive operations.
- GIL (Global Interpreter Lock) in CPython can limit parallelism in multi-threaded scenarios, though Flink agents typically work around this through process-level distribution.
- Higher memory footprint per worker compared to JVM processes.

**Scalability Limitations**
- Not as well-suited for extremely high-throughput scenarios requiring millisecond-level latencies at massive scale.
- Startup time is slower compared to pre-compiled Java, which can impact dynamic scaling scenarios.
- Resource consumption scales less efficiently than Java for equivalent workloads.

**Type Safety**
- Runtime errors from type mismatches are only discovered during execution.
- Lack of compile-time verification increases the need for comprehensive unit and integration testing.
- Refactoring requires careful manual verification or extensive test coverage.

**Production Complexity**
- Deployment requires managing Python environments, dependencies, and virtual environments in production.
- Less mature tooling for production monitoring and profiling compared to Java.
- Container-based deployments are common but add operational overhead.
- Debugging production issues can be more challenging due to dynamic typing.

**Ecosystem Fragmentation**
- Package management through pip can lead to dependency conflicts and version incompatibilities.
- Libraries may have inconsistent APIs and varying levels of maintenance.

---

## Comparison Table

| **Aspect** | **Java API** | **Python API** |
|---|---|---|
| **Throughput (events/sec)** | Millions+ (high) | Hundreds of thousands (moderate) |
| **Latency (p99)** | Sub-millisecond to milliseconds | Milliseconds to tens of milliseconds |
| **Startup Time** | Seconds (JVM warmup) | Seconds (fewer, lighter startup) |
| **Type Safety** | Compile-time checked | Runtime checked |
| **Learning Curve** | Steep (JVM concepts, build tools) | Gentle (familiar syntax, rapid feedback) |
| **ML/AI Ecosystem** | Limited, requires bridges | Rich, native support |
| **Concurrency Model** | Fine-grained (threads, locks) | Process-level distribution |
| **Development Speed** | Slower (verbosity, compilation) | Faster (concise, interactive) |
| **Debugging** | Mature tools (debuggers, profilers) | Good (pdb, but fewer advanced tools) |
| **Production Readiness** | Excellent (proven at massive scale) | Good (requires container/environment management) |
| **Memory Efficiency** | Efficient JVM heap management | Higher per-process footprint |
| **IDE Support** | Excellent (IntelliJ, Eclipse) | Good (VS Code, PyCharm) |
| **Deployment Complexity** | Container-based (simpler) | Container + Python environment management |
| **Use Cases** | High-volume streams, strict SLAs | ML/AI agents, experimentation, scripting |

---

## Decision Guide

### When to Choose Java API

Choose the **Java API** if your project exhibits one or more of these characteristics:

- **High-throughput, low-latency requirements**: Processing millions of events per second with strict latency SLAs (sub-millisecond).
- **Existing Java infrastructure**: Your organization has standardized on Java and has mature Java operations and expertise.
- **Mission-critical systems**: Building systems where reliability, performance predictability, and extensive tooling are non-negotiable.
- **Type-safe codebase**: Strict typing requirements for correctness and maintainability in large codebases.
- **Limited ML/AI needs**: Your agents primarily orchestrate business logic rather than incorporating complex ML models.
- **Enterprise integrations**: Need seamless integration with existing enterprise systems, frameworks, and security infrastructure.
- **Long-term maintenance**: Building systems that will be maintained and evolved over years with multiple teams.

### When to Choose Python API

Choose the **Python API** if your project exhibits one or more of these characteristics:

- **ML/AI-centric workloads**: Building agents that incorporate LLMs, embeddings, vector databases, or custom ML models.
- **Rapid prototyping and experimentation**: Need to iterate quickly on agent designs and test multiple approaches.
- **Data science teams**: Your team consists primarily of data scientists or ML engineers more comfortable with Python.
- **Moderate throughput**: Processing streams at thousands to hundreds of thousands of events per second is acceptable.
- **Shorter time-to-market**: Prioritizing development speed over maximum performance.
- **Exploratory development**: Building proof-of-concept agents and experimenting with novel approaches.
- **Cross-language compatibility**: Leveraging existing Python tools, libraries, or integrations already in use by your team.
- **Interactive development**: Using Jupyter notebooks or interactive shells for exploratory agent development.

---

## Practical Migration Path

**Starting with Python**

If you're uncertain, consider starting with Python API for initial development. Python's rapid iteration cycles are ideal for:
- Validating agent design and behavior
- Integrating and testing LLM interactions
- Building proof-of-concept implementations

**Scaling to Java**

Once your agent design is stable and performance requirements become clear, you can:
- Port proven logic to Java API for production workloads
- Reuse agent designs and configurations across implementations
- Maintain both implementations for different deployment scenarios

**Hybrid Deployments**

Many organizations deploy Python agents for:
- Development and testing environments
- Lower-throughput, ML-intensive agents
- Rapid experimentation and feature development

And Java agents for:
- Production workloads with strict SLAs
- High-throughput event processing
- Mission-critical systems

---

## Technical Considerations

### Dependency Availability

- **Python API**: Full support for embedding models and most vector stores. Emerging support for additional integrations.
- **Java API**: Full support for some integrations (e.g., Elasticsearch), with others in development. Limited ML library support.

Check the [embedding models]({{< ref "docs/development/embedding_models" >}}) and [vector stores]({{< ref "docs/development/vector_stores" >}}) documentation for current integration status.

### Runtime Requirements

| **API** | **Minimum Version** | **JDK Requirement** |
|---|---|---|
| **Python API** | Python 3.8+ | JDK 11+ |
| **Java API** | JDK 11+ | JDK 11+ (17+ for MCP features) |

For details, see [Installation]({{< ref "docs/get-started/installation" >}}).

---

## Summary

Both Java and Python APIs provide complete access to Flink Agents' capabilities. Your choice should reflect:

- **Performance requirements** (throughput, latency, resource constraints)
- **Team expertise** (existing skills, available resources)
- **Project goals** (experimentation vs. production, ML-centric vs. logic-centric)
- **Integration needs** (ecosystem requirements, existing infrastructure)

Neither choice is permanent—many successful deployments combine both APIs, using Python for development and Java for performance-critical production workloads.
