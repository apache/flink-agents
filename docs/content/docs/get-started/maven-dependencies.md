---
title: 'Maven Dependencies'
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

Flink Agents is organized into multiple Maven modules. This guide explains how to add Flink Agents dependencies to your project's `pom.xml` file.

## Prerequisites

Before adding Flink Agents dependencies, ensure your project uses:
- **Java 11** or higher
- **Maven 3** or higher
- **Apache Flink 1.20.3** (for production use)

## Core Modules

### Flink Agents API

The API module provides the core interfaces and classes for building Flink Agents applications.

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-agents-api</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

**Note:** Replace `0.2-SNAPSHOT` with the actual release version when available.

### Flink Agents Runtime

The runtime module contains the execution engine for running Flink Agents jobs.

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-agents-runtime</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

### Flink Agents Plan

The plan module provides functionality for creating and managing agent plans.

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-agents-plan</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

## Integration Modules

### Chat Models

Flink Agents provides integrations with various chat model providers. Add the specific integration you need:

#### OpenAI

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-agents-integrations-chat-models-openai</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

#### Anthropic (Claude)

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-agents-integrations-chat-models-anthropic</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

#### Azure AI

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-agents-integrations-chat-models-azureai</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

#### Ollama

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-agents-integrations-chat-models-ollama</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

### Vector Stores

#### Elasticsearch

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-agents-integrations-vector-stores-elasticsearch</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

### Embedding Models

#### Ollama Embeddings

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-agents-integrations-embedding-models-ollama</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

## Complete Example

Here's a complete example `pom.xml` for a Flink Agents project using the OpenAI chat model:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-flink-agents-app</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <flink.version>1.20.3</flink.version>
        <flink-agents.version>0.2-SNAPSHOT</flink-agents.version>
    </properties>

    <dependencies>
        <!-- Flink Agents Core -->
        <dependency>
            <groupId>org.apache.flink</groupId>
            <artifactId>flink-agents-api</artifactId>
            <version>${flink-agents.version}</version>
        </dependency>

        <dependency>
            <groupId>org.apache.flink</groupId>
            <artifactId>flink-agents-runtime</artifactId>
            <version>${flink-agents.version}</version>
        </dependency>

        <!-- Flink Agents Integration: OpenAI -->
        <dependency>
            <groupId>org.apache.flink</groupId>
            <artifactId>flink-agents-integrations-chat-models-openai</artifactId>
            <version>${flink-agents.version}</version>
        </dependency>

        <!-- Apache Flink Dependencies -->
        <dependency>
            <groupId>org.apache.flink</groupId>
            <artifactId>flink-streaming-java</artifactId>
            <version>${flink.version}</version>
            <scope>provided</scope>
        </dependency>

        <dependency>
            <groupId>org.apache.flink</groupId>
            <artifactId>flink-clients</artifactId>
            <version>${flink.version}</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.8.1</version>
                <configuration>
                    <source>11</source>
                    <target>11</target>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## Dependency Scopes

When adding Flink dependencies, use the `provided` scope for Flink core dependencies since they are already available in the Flink runtime:

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-streaming-java</artifactId>
    <version>${flink.version}</version>
    <scope>provided</scope>
</dependency>
```

For Flink Agents dependencies, use the default scope (compile):

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-agents-api</artifactId>
    <version>0.2-SNAPSHOT</version>
    <!-- scope defaults to compile -->
</dependency>
```

## Version Management

### Using SNAPSHOT Versions

For development, you can use SNAPSHOT versions. Make sure to add the Apache snapshot repository:

```xml
<repositories>
    <repository>
        <id>apache-snapshots</id>
        <name>Apache Snapshot Repository</name>
        <url>https://repository.apache.org/content/repositories/snapshots/</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

### Using Release Versions

Once Flink Agents is released, replace `0.2-SNAPSHOT` with the actual release version (e.g., `0.2.0`). Release versions are available from Maven Central and don't require additional repository configuration.

## Building from Source

If you're building Flink Agents from source and want to use it in your project:

1. Build Flink Agents:
   ```shell
   cd flink-agents
   ./tools/build.sh
   ```

2. Install to your local Maven repository:
   ```shell
   mvn clean install -DskipTests
   ```

3. Use the version in your `pom.xml`:
   ```xml
   <dependency>
       <groupId>org.apache.flink</groupId>
       <artifactId>flink-agents-api</artifactId>
       <version>0.2-SNAPSHOT</version>
   </dependency>
   ```

## Additional Resources

- [Installation Guide]({{< ref "docs/get-started/installation" >}})
- [Quickstart Guide]({{< ref "docs/get-started/quickstart" >}})
- [Development Documentation]({{< ref "docs/development" >}})

