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
import httpx
import pytest


@pytest.fixture(name="http_calls")
def record_dashscope_requests(
    monkeypatch: pytest.MonkeyPatch,
) -> tuple[list[httpx.Request], list[httpx.Response]]:
    """Record requests sent by the real SDK and serve the queued responses."""
    requests: list[httpx.Request] = []
    responses: list[httpx.Response] = []

    def handle_request(
        _transport: httpx.HTTPTransport, request: httpx.Request
    ) -> httpx.Response:
        requests.append(request)
        assert responses, (
            f"Unexpected DashScope request: {request.method} {request.url.path}"
        )
        return responses.pop(0)

    monkeypatch.setattr(httpx.HTTPTransport, "handle_request", handle_request)
    return requests, responses
