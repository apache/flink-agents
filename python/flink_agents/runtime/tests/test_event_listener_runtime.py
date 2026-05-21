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
#  limitations under the License.
#################################################################################
import sys
import unittest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.event_context import EventContext
from flink_agents.api.events.event import Event, InputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.listener.event_listener import EventListener
from flink_agents.api.runner_context import RunnerContext
from flink_agents.runtime.python_java_utils import instantiate_python_event_listener


class MockListener(EventListener):
    def __init__(self) -> None:
        self.called_events = []

    def on_event_processed(self, context: EventContext, event: Event) -> None:
        self.called_events.append(event)


class SimpleAgent(Agent):
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process(event: Event, ctx: RunnerContext) -> None:
        pass


class TestEventListenerRuntime(unittest.TestCase):
    def test_instantiate_main_module(self):
        """Test instantiation of a listener from the __main__ module."""
        # Add MockListener to sys.modules['__main__'] for testing purposes
        main_mod = sys.modules['__main__']
        main_mod.MockListener = MockListener

        target = "__main__:MockListener.on_event_processed"
        listener = instantiate_python_event_listener(target)
        assert isinstance(listener, EventListener)
        assert listener.__class__.__name__ == "MockListener"

    def test_instantiate_invalid_format(self):
        """Test that invalid format raises ValueError."""
        import pytest
        with pytest.raises(ValueError, match="Invalid format"):
            instantiate_python_event_listener("InvalidFormat")

    def test_instantiate_module_not_found(self):
        """Test that non-existent module raises ImportError."""
        import pytest
        with pytest.raises(ImportError):
            instantiate_python_event_listener("non_existent_module:MyListener.on_event_processed")

    def test_instantiate_class_not_found(self):
        """Test that non-existent class raises AttributeError."""
        import pytest
        with pytest.raises(AttributeError):
            instantiate_python_event_listener("flink_agents.api.listener.event_listener:NonExistent.on_event_processed")

    def test_listener_integration_with_local_runner(self):
        """Test listener integration with LocalRunner.
        Note: Currently LocalRunner might not support event listeners directly
        as they are often handled on the Java side in a real Flink job.
        But we can test if the configuration is correctly passed.
        """
        from flink_agents.api.core_options import AgentConfigOptions

        env = AgentsExecutionEnvironment.get_execution_environment()
        config = env.get_config()

        # Define a listener in this module
        global MyTestListener

        class MyTestListener(EventListener):
            call_count = 0

            def on_event_processed(self, context: EventContext, event: Event) -> None:
                MyTestListener.call_count += 1

        listener_str = f"{__name__}:MyTestListener.on_event_processed"
        config.set(AgentConfigOptions.EVENT_LISTENERS, [listener_str])

        input_list = [{"key": "k1", "value": "v1"}]
        agent = SimpleAgent()
        env.from_list(input_list).apply(agent)
        env.execute()

        # Now LocalRunner supports listeners, we can assert call_count
        assert MyTestListener.call_count > 0
