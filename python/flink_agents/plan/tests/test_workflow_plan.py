from flink_agents.api.decorators import action
from flink_agents.api.event import Event, InputEvent, OutputEvent
from flink_agents.api.workflow import Workflow
from flink_agents.api.workflow_runner_context import WorkflowRunnerContext
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.workflow_plan import WorkflowPlan


class TestWorkflow(Workflow): #noqa D101
    @action(InputEvent)
    @staticmethod
    def increment(event: Event, ctx: WorkflowRunnerContext) -> None: #noqa D102
        value = event.input
        value += 1
        ctx.send_event(OutputEvent(output=value))

def test_from_workflow(): #noqa D102
    workflow = TestWorkflow()
    workflow_plan = WorkflowPlan.from_workflow(workflow)
    actions = workflow_plan.get_actions(InputEvent)
    assert len(actions) == 1
    action = actions[0]
    assert action.name == "increment"
    func = action.exec
    assert isinstance(func, PythonFunction)
    assert func.module == "flink_agents.plan.tests.test_workflow_plan"
    assert func.qualname == "TestWorkflow.increment"
    assert action.listen_event_types == [InputEvent]
