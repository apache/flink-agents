---
title: Kubernetes Deployment
weight: 4
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

Besides submitting jobs to an existing cluster via the Flink CLI (see [Deployment]({{< ref "docs/operations/deployment" >}})), you can run Flink Agents jobs on Kubernetes using Flink's **application mode** with a standalone cluster topology:

- A `Job` runs the JobManager as a standalone application, embedding the job entry point.
- A `Deployment` runs one or more TaskManagers.

This page describes the Kubernetes manifests and the common pitfalls you are likely to hit when running the Flink Agents quickstart examples (`WorkflowSingleAgentExample`, etc.) on Kubernetes.

{{< hint info >}}
**Note**: This is not Flink's [native Kubernetes integration](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/resource-providers/native_kubernetes/). It is a plain `Job` + `Deployment` setup, which is simpler for local clusters and quick experiments.
{{< /hint >}}

## Prerequisites

- A running Kubernetes cluster (`kubectl` configured).
- A Flink Agents image containing your job JAR under `/opt/flink/usrlib/`.
- Flink 1.20.3 or later.

## Build the image

Package the examples module and build an image that includes the job JAR:

```bash
mvn -pl :flink-agents-examples package -DskipTests -Dspotless.skip=true -B

# Prepare a minimal build context that layers the JAR on top of a Flink base image
cat > /tmp/flink-agents/Dockerfile <<'EOF'
FROM <flink-base-image>
COPY flink-agents-examples-<version>.jar /opt/flink/usrlib/flink-agents-examples-<version>.jar
EOF

cp examples/target/flink-agents-examples-<version>.jar /tmp/flink-agents/
docker build -t <your-flink-agents-image> /tmp/flink-agents/
```

## Configure the chat model

The quickstart examples resolve their model connection from a `ResourceDescriptor`. When using an OpenAI-compatible endpoint, point the descriptor at your endpoint and model, and read the API key from an environment variable rather than hard-coding it:

```java
public static final ResourceDescriptor CHAT_MODEL_DESCRIPTOR =
        ResourceDescriptor.Builder.newBuilder(
                        ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION)
                .addInitialArgument("api_key", resolveApiKey())
                .addInitialArgument("api_base_url", "<your-api-base-url>")
                .addInitialArgument("timeout", 120)
                .build();

private static String resolveApiKey() {
    String apiKey = System.getenv("CHAT_MODEL_API_KEY");
    if (apiKey == null || apiKey.trim().isEmpty()) {
        throw new IllegalStateException("Environment variable CHAT_MODEL_API_KEY is not set.");
    }
    return apiKey;
}
```

Set the model ID in the agent that defines the completion:

```java
.addInitialArgument("model", "<your-model-id>")
```

The API key is then injected into the Pods from a Kubernetes `Secret`, so it never appears in the source code, the image, or the manifests. See [Inject the API key](#inject-the-api-key).

## Kubernetes manifests

The deployment consists of the following resources:

| Resource | Purpose |
|----------|---------|
| `ConfigMap/flink-config` | Flink configuration + log4j configuration |
| `Job/flink-jobmanager` | Application-mode JobManager with the job entry point |
| `Deployment/flink-taskmanager` | TaskManager (typically 1 replica) |
| `Secret/chat-model-secret` | Holds the chat model API key |

### Why an initContainer is required

The Flink image entrypoint writes to `flink-conf.yaml` at startup, but a `ConfigMap` mounted as a file is read-only. To work around this, copy the mounted configuration into a writable `emptyDir` using an initContainer:

```yaml
initContainers:
  - name: flink-config-copy
    image: <your-flink-agents-image>
    command: ["sh", "-c",
      "cp -r /opt/flink/conf/* /opt/flink/conf-writable/ &&
       cp /tmp/config/flink-conf.yaml /opt/flink/conf-writable/flink-conf.yaml &&
       cp /tmp/config/log4j-console.properties /opt/flink/conf-writable/log4j-console.properties"]
    volumeMounts:
      - name: flink-config-volume
        mountPath: /tmp/config
      - name: flink-conf-writable
        mountPath: /opt/flink/conf-writable
```

The main container then mounts `flink-conf-writable` at `/opt/flink/conf`.

### Complete manifest skeleton

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: flink-config
  namespace: flink
data:
  flink-conf.yaml: |
    jobmanager.rpc.address: flink-jobmanager
    jobmanager.rpc.port: 6123
    taskmanager.numberOfTaskSlots: 2
    parallelism.default: 2
    rest.bind-address: 0.0.0.0
  log4j-console.properties: |
    rootLogger.level = INFO
    rootLogger.appenderRef.console.ref = ConsoleAppender
    appender.console.name = ConsoleAppender
    appender.console.type = CONSOLE
    appender.console.layout.type = PatternLayout
    appender.console.layout.pattern = %d{yyyy-MM-dd HH:mm:ss,SSS} %-5p %-60c %x - %m%n
---
apiVersion: batch/v1
kind: Job
metadata:
  name: flink-jobmanager
  namespace: flink
spec:
  template:
    metadata:
      labels:
        app: flink
        component: jobmanager
    spec:
      restartPolicy: OnFailure
      initContainers:
        - name: flink-config-copy
          image: <your-flink-agents-image>
          # ... see the initContainer snippet above ...
      containers:
        - name: jobmanager
          image: <your-flink-agents-image>
          args: ["standalone-job", "--job-classname", "<your-main-class>"]
          env:
            - name: JOB_MANAGER_RPC_ADDRESS
              value: flink-jobmanager
            - name: CHAT_MODEL_API_KEY
              valueFrom:
                secretKeyRef:
                  name: chat-model-secret
                  key: CHAT_MODEL_API_KEY
          ports:
            - containerPort: 6123
              name: rpc
            - containerPort: 6124
              name: blob-server
            - containerPort: 8081
              name: webui
          volumeMounts:
            - name: flink-conf-writable
              mountPath: /opt/flink/conf
            - name: input-data
              mountPath: /opt/flink/data/input_data.txt
              subPath: input_data.txt
      volumes:
        - name: flink-config-volume
          configMap:
            name: flink-config
            items:
              - key: flink-conf.yaml
                path: flink-conf.yaml
              - key: log4j-console.properties
                path: log4j-console.properties
        - name: flink-conf-writable
          emptyDir: {}
        - name: input-data
          configMap:
            name: input-data
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: flink-taskmanager
  namespace: flink
spec:
  replicas: 1
  selector:
    matchLabels:
      app: flink
      component: taskmanager
  template:
    metadata:
      labels:
        app: flink
        component: taskmanager
    spec:
      initContainers:
        - name: flink-config-copy
          image: <your-flink-agents-image>
          # ... see the initContainer snippet above ...
      containers:
        - name: taskmanager
          image: <your-flink-agents-image>
          args: ["taskmanager"]
          env:
            - name: JOB_MANAGER_RPC_ADDRESS
              value: flink-jobmanager
            - name: CHAT_MODEL_API_KEY
              valueFrom:
                secretKeyRef:
                  name: chat-model-secret
                  key: CHAT_MODEL_API_KEY
          volumeMounts:
            - name: flink-conf-writable
              mountPath: /opt/flink/conf
            - name: input-data
              mountPath: /opt/flink/data/input_data.txt
              subPath: input_data.txt
      volumes:
        - name: flink-config-volume
          configMap:
            name: flink-config
            items:
              - key: flink-conf.yaml
                path: flink-conf.yaml
              - key: log4j-console.properties
                path: log4j-console.properties
        - name: flink-conf-writable
          emptyDir: {}
        - name: input-data
          configMap:
            name: input-data
```

## Common pitfalls

| Problem | Root cause | Fix |
|---------|-----------|-----|
| ConfigMap mounted as read-only, entrypoint fails to write `flink-conf.yaml` | The Flink entrypoint writes the config file at startup, but the `ConfigMap` mount is read-only | Copy the config into a writable `emptyDir` via an initContainer |
| Logs are silently lost | `log4j-console.properties` is missing appender definitions | Define `ConsoleAppender` / `RollingFileAppender` explicitly |
| TaskManager cannot reach the JobManager | The entrypoint overrides `jobmanager.rpc.address` using `hostname -f` | Set `JOB_MANAGER_RPC_ADDRESS` to the JobManager service name explicitly |

## Inject input data via ConfigMap

Input files can be injected through a `ConfigMap`, so you can swap test data without rebuilding the image:

```bash
kubectl create configmap input-data -n flink \
  --from-file=input_data.txt=examples/src/main/resources/input_data.txt
```

Mount it as a single file using `subPath`:

```yaml
volumeMounts:
  - name: input-data
    mountPath: /opt/flink/data/input_data.txt
    subPath: input_data.txt
volumes:
  - name: input-data
    configMap:
      name: input-data
```

{{< hint warning >}}
`subPath` single-file mounts are static: after updating the `ConfigMap`, you must restart the Pods for the change to take effect.
{{< /hint >}}

## Inject the API key

Create a `Secret` from the command line so the key never lands in any project file:

```bash
kubectl create secret generic chat-model-secret -n flink \
  --from-literal=CHAT_MODEL_API_KEY=<your-api-key>
```

Reference it in the container environment as shown in the manifest skeleton above. Both the JobManager and the TaskManager need the variable: the JobManager loads the class that resolves the key during static initialization, and the TaskManager actually invokes the model.

## Verify

```bash
kubectl apply -f <your-manifests>.yaml

# Confirm the injected file content
kubectl exec -n flink deployment/flink-taskmanager -- cat /opt/flink/data/input_data.txt

# Follow the analysis output
kubectl logs -n flink deployment/flink-taskmanager -f
```

A `Job`-based JobManager shows `Completed` once a bounded input has been fully processed, which is expected behavior rather than an error.
