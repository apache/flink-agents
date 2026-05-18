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
import unittest

from flink_agents.api.event_context import EventContext
from flink_agents.api.events.event import Event
from flink_agents.api.listener.event_listener import EventListener


class GlobalListener(EventListener):
    def on_event_processed(self, context: EventContext, event: Event) -> None:
        pass


class MainListenerMock(EventListener):
    def on_event_processed(self, context: EventContext, event: Event) -> None:
        pass


class TestEventListener(unittest.TestCase):
    def test_global_listener_str(self):
        """Test the string representation of a global EventListener class."""
        module_name = GlobalListener.__module__
        expected = f"{module_name}:GlobalListener.on_event_processed"
        self.assertEqual(str(GlobalListener), expected)

    def test_top_level_nested_listener_str(self):
        """Test the string representation of a nested EventListener class defined at module level."""
        global TopOuter

        class TopOuter:
            class TopInner(EventListener):
                def on_event_processed(self, context: EventContext, event: Event) -> None:
                    pass

        module_name = TopOuter.TopInner.__module__
        expected = f"{module_name}:TopOuter.TopInner.on_event_processed"
        self.assertEqual(str(TopOuter.TopInner), expected)

    def test_local_listener_raises_error(self):
        """Test that defining an EventListener in a local scope raises a ValueError."""
        def some_function():
            class LocalListener(EventListener):
                def on_event_processed(self, context: EventContext, event: Event) -> None:
                    pass

            return LocalListener

        LocalListener = some_function()
        with self.assertRaisesRegex(ValueError, "Cannot instantiate local class"):
            str(LocalListener)

    def test_main_module_with_file_handling(self):
        """Test string representation when the module is '__main__' and has a '__file__' attribute."""
        from unittest.mock import patch, MagicMock

        mock_module = MagicMock()
        mock_module.__name__ = "__main__"
        mock_module.__file__ = "/path/to/my_script.py"

        with patch("inspect.getmodule", return_value=mock_module):
            self.assertEqual(str(MainListenerMock), "my_script:MainListenerMock.on_event_processed")

    def test_main_module_without_file_handling(self):
        """Test string representation when the module is '__main__' but lacks a '__file__' attribute."""
        from unittest.mock import patch, MagicMock

        mock_module = MagicMock()
        mock_module.__name__ = "__main__"
        # __file__ attribute is missing

        with patch("inspect.getmodule", return_value=mock_module):
            # Should fallback to "__main__" if __file__ is missing
            self.assertEqual(str(MainListenerMock), "__main__:MainListenerMock.on_event_processed")

    def test_inspect_getmodule_none_fallback(self):
        """Test fallback to '__module__' when 'inspect.getmodule' returns None."""
        from unittest.mock import patch

        with patch("inspect.getmodule", return_value=None):
            # Should fallback to cls.__module__
            module_name = MainListenerMock.__module__
            expected = f"{module_name}:MainListenerMock.on_event_processed"
            self.assertEqual(str(MainListenerMock), expected)
