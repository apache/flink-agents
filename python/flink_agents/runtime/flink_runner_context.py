from typing import Any

import cloudpickle
from typing_extensions import override

from flink_agents.api.event import Event, OutputEvent
from flink_agents.api.runner_context import RunnerContext


def get_runner_context(j_runner_context: Any):
    return FlinkRunnerContext(j_runner_context)

def get_python_object(bytesObject: bytes):
    return cloudpickle.loads(bytesObject)

class FlinkRunnerContext(RunnerContext):
    def __init__(self, j_runner_context: Any):
        self._j_runner_context = j_runner_context

    @override
    def send_event(self, event: Event):
        try:
            if isinstance(event, OutputEvent):
                self._j_runner_context.sendEvent(
                    f"{type(event).__module__}.{type(event).__name__}",
                    cloudpickle.dumps(event.output),
                )
            else:
                self._j_runner_context.sendEvent(f"{type(event).__module__}.{type(event).__name__}", cloudpickle.dumps(event))
        except Exception as e:
            print(f"Error adding event: {e}")