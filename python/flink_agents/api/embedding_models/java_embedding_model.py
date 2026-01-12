################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#################################################################################
from flink_agents.api.decorators import java_resource
from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
)


@java_resource
class JavaEmbeddingModelConnection(BaseEmbeddingModelConnection):
    """Java-based implementation of EmbeddingModelConnection that wraps a Java embedding
    model object.

    This class serves as a bridge between Python and Java embedding model environments,
    but unlike JavaEmbeddingModelSetup, it does not provide direct embedding
    functionality in Python.
    """

    java_class_name: str=""

@java_resource
class JavaEmbeddingModelSetup(BaseEmbeddingModelSetup):
    """Java-based implementation of EmbeddingModelSetup that bridges Python and Java
    embedding model functionality.

    This class wraps a Java embedding model setup object and provides Python interface
    compatibility while delegating actual embedding operations to the underlying Java
    implementation.
    """

    java_class_name: str=""
