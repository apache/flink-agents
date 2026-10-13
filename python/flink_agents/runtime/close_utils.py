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
"""Helpers for "close everything, then surface the first failure" cleanup paths.

Shared by the runtime components whose ``close()`` must keep going after a
failure: ``ResourceCache``, ``FlinkRunnerContext`` and ``SkillManager``. One
implementation is what keeps those three contracts identical rather than merely
similar.

This module deliberately imports nothing from the project: ``skill_manager``
cannot reach a helper parked in ``resource_cache``, because
``skill_manager -> resource_cache -> resource_context -> skill_manager`` is an
import cycle.
"""

import logging
from collections.abc import Callable

_LOG = logging.getLogger(__name__)


def failure_of(close: Callable[[], None]) -> Exception | None:
    """Run ``close``, returning any failure instead of raising it.

    Keeps the caller's cleanup loop free of a ``try`` block so one bad component
    cannot end the iteration.

    Parameters
    ----------
    close : Callable[[], None]
        Cleanup callback to run.

    Returns:
    -------
    Exception | None
        The failure raised by ``close``, or ``None`` when it succeeded.
    """
    try:
        close()
    except Exception as e:
        return e
    return None


def first_or_logged(
    failure: Exception | None, previous: Exception | None, what: str
) -> Exception | None:
    """Keep the first failure and log any later one.

    The Python analogue of Flink's ``ExceptionUtils.firstOrSuppressed``. Later
    failures are logged rather than attached, because ``ExceptionGroup`` requires
    3.11 and this package supports 3.10.

    Parameters
    ----------
    failure : Exception | None
        Failure produced by the component just closed, if any.
    previous : Exception | None
        First failure seen so far, if any.
    what : str
        Short description of the component, used in the log message.

    Returns:
    -------
    Exception | None
        The failure to keep: the first one seen.
    """
    if failure is None:
        return previous
    if previous is None:
        return failure
    _LOG.warning("Suppressed failure closing %s.", what, exc_info=failure)
    return previous
