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

# Runtime API contracts

## `ActionStateStore.put(...)`

The existing Java method signature and serialized action-state format are unchanged. No HTTP,
cURL, JSON, YAML, or IDL contract changes are introduced.

For the Kafka implementation, a successful return now means that the producer has acknowledged the
record, the producer has been flushed, and the attempt-local cache contains the same state. An
asynchronous acknowledgement failure is surfaced to the caller and does not populate the cache.
An interrupted acknowledgement wait is rethrown with the thread's interrupted status preserved.
