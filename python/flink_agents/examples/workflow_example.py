from typing import Any

from flink_agents.api.decorators import action
from flink_agents.api.event import Event, InputEvent, OutputEvent
from flink_agents.api.workflow import Workflow
from flink_agents.api.workflow_runner_context import WorkflowRunnerContext


class MyEvent(Event): #noqa D101
    value: Any

class MyWorkflow(Workflow): #noqa D101
    @action(InputEvent)
    @staticmethod
    def first_action(event: Event, ctx: WorkflowRunnerContext): #noqa D102
        event.input += " first_action"
        ctx.send_event(MyEvent(value=event.input))
        ctx.send_event(OutputEvent(output=event.input))

    @action(MyEvent)
    @staticmethod
    def second_action(event: Event, ctx: WorkflowRunnerContext): #noqa D102
        event.value += " second_action"
        ctx.send_event(OutputEvent(output=event.value))


if __name__ == "__main__":
    workflow = MyWorkflow()
    session_id = workflow.run(input="input", runner='LocalRunner')
    for output in workflow.get_outputs(session_id):
        print(output)
