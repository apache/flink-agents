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
import subprocess
from pathlib import Path

from ollama import Client

current_dir = Path(__file__).parent


def pull_model(ollama_model: str) -> Client:
    """Run ollama pull ollama_model."""
    try:
        # prepare ollama server
        subprocess.run(
            ["bash", f"{current_dir}/scripts/ollama_pull_model.sh", ollama_model],
            timeout=120,
            check=True,
        )
        client = Client()
        models = client.list()

        model_found = False
        for model in models["models"]:
            if model.model == ollama_model:
                model_found = True
                break

        if not model_found:
            client = None  # type: ignore
    except Exception:
        client = None  # type: ignore

    return client


def check_result(*, result_dir: Path, groud_truth_dir: Path) -> None:
    """Util function for checking flink job execution result."""
    actual_result = []
    for file in result_dir.iterdir():
        if file.is_dir():
            for child in file.iterdir():
                with child.open() as f:
                    actual_result.extend(f.readlines())
        if file.is_file():
            with file.open() as f:
                actual_result.extend(f.readlines())

    with groud_truth_dir.open() as f:
        expected = f.readlines().sort()

    actual_result = actual_result.sort()
    assert actual_result == expected
