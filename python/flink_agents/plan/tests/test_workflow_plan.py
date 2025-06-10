from python.flink_agents.api.event import InputEvent, OutputEvent
from python.flink_agents.plan.action import Action
from python.flink_agents.plan.function import Function
from python.flink_agents.plan.workflow_plan import WorkflowPlan


def increment(event: InputEvent) -> OutputEvent: # noqa: D103
    value = event.input
    value = int(value) + 1
    return OutputEvent(isLegal=True, result=str(value))


def test_simplest_workflow_plan() -> None: # noqa: D103
    INCREMENT_ACTION = Action(
        name="increment",
        exec=Function.from_callable(increment),
        listen_event_types=[InputEvent],
    )
    actions = {InputEvent: [INCREMENT_ACTION]}
    workflow_plan = WorkflowPlan(actions=actions)

    input_event = InputEvent(input="1")
    input_event_triggered_actions = workflow_plan.get_action(type(input_event))
    for action in input_event_triggered_actions:
        result_event = action.exec(input_event)
        if isinstance(result_event, OutputEvent):
            assert result_event.isLegal
            print(result_event.result)
            assert result_event.result == "2"


if __name__ == "__main__":
    test_simplest_workflow_plan()
