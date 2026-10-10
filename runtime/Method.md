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

# Runtime method contracts

## `KafkaActionStateStore.put(...)`

This method persists an action-state update before publishing that update to the attempt-local
cache.

1. Encode the composite state key and send the state record to Kafka.
2. Wait for the returned producer future, so asynchronous broker acknowledgement failures are
   observed by the caller.
3. Flush the producer and then update the local cache.

If sending or flushing fails, the method throws and leaves the local cache unchanged. If waiting
for the acknowledgement is interrupted, the method restores the thread's interrupted status and
rethrows `InterruptedException`.
