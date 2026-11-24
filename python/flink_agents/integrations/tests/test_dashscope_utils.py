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
from types import SimpleNamespace

import pytest

from flink_agents.api.chat_message import ImageBlock, UnsupportedContentBlockError
from flink_agents.integrations.dashscope_utils import (
    media_source_url,
    merge_model_kwargs,
    usage_counters,
)


def test_usage_counters_keep_scalar_fields_and_drop_bools() -> None:
    usage = SimpleNamespace(
        model_dump=lambda exclude_unset, exclude_none: {
            "total_tokens": 3,
            "cached_tokens": 0,
            "enabled": True,
            "nan_value": float("nan"),
            "inf_value": float("inf"),
            "details": {"cached": 1},
        }
    )

    assert usage_counters(usage) == {"total_tokens": 3, "cached_tokens": 0}
    assert usage_counters({"duration": 0, "ratio": "16:9"}) == {
        "duration": 0,
        "ratio": "16:9",
    }
    assert usage_counters(None) == {}


def test_merge_model_kwargs_drops_none_and_applies_additional_kwargs_overrides() -> (
    None
):
    merged = merge_model_kwargs(
        {"model": "qwen-image", "n": None, "seed": 7},
        {"seed": 42, "extra": "x"},
    )
    assert merged == {"model": "qwen-image", "seed": 42, "extra": "x"}


def test_media_source_url_enforces_allowed_schemes() -> None:
    assert (
        media_source_url(ImageBlock.from_url("image/png", "http://example.test/a.png"))
        == "http://example.test/a.png"
    )
    assert (
        media_source_url(ImageBlock.from_url("image/png", "https://example.test/a.png"))
        == "https://example.test/a.png"
    )
    assert (
        media_source_url(ImageBlock.from_base64("image/png", "YQ=="))
        == "data:image/png;base64,YQ=="
    )

    for bad_url in (
        "file:///etc/passwd",
        "/etc/passwd",
        "oss://dashscope-instant/demo.png",
    ):
        with pytest.raises(UnsupportedContentBlockError):
            media_source_url(ImageBlock.from_url("image/png", bad_url))

    assert (
        media_source_url(
            ImageBlock.from_url("image/png", "oss://dashscope-instant/demo.pdf"),
            allowed_schemes=("http", "https", "oss"),
        )
        == "oss://dashscope-instant/demo.pdf"
    )
    for local_url in ("file:///etc/passwd", "/etc/passwd"):
        with pytest.raises(UnsupportedContentBlockError):
            media_source_url(
                ImageBlock.from_url("image/png", local_url),
                allowed_schemes=("http", "https", "oss"),
            )
