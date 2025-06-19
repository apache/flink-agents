
from flink_agents.api.decorators import action
from flink_agents.api.event import Event, InputEvent, OutputEvent
from flink_agents.api.workflow_runner_context import WorkflowRunnerContext


def test_action_decorator() -> None: #noqa D103
    @action(InputEvent)
    def forward_action(event: Event, ctx: WorkflowRunnerContext) -> None:
        input = event.input
        ctx.send_event(OutputEvent(output=input))

    assert hasattr(forward_action, '_listen_events')
    listen_events = forward_action._listen_events
    assert len(listen_events) == 1
    assert listen_events[0] == InputEvent
