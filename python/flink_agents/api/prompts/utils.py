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
from string import Formatter
from typing import Any

from typing_extensions import override


class SafeFormatter(Formatter):
    """Safe string formatter that does not raise KeyError if key is missing."""

    @override
    def get_value(self, key: Any, args: Any, kwargs: Any) -> Any:
        if isinstance(key, int):
            return args[key]
        else:
            if key in kwargs:
                return kwargs[key]
            else:
                return str(key)


FORMATTER = SafeFormatter()
