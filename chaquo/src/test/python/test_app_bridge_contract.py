"""The chaquo bridge calls app.getDependence(ru) unconditionally during Spider.init().

A single-file Python spider that does not inherit the bundled base/spider.py has no
getDependence attribute.  Before this guard the AttributeError escaped
Spider.init(), PyLoader caught it and degraded the site to an all-empty SpiderNull,
so home/category/search silently rendered nothing.

These tests pin the two properties that make the fix safe:
  * an absent getDependence is treated as "no dependencies";
  * a real getDependence that raises is NOT swallowed (the getattr form, not
    try/except AttributeError).
"""

import importlib.util
import sys
import types
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[2] / "main/python"

# app.py imports requests at module scope; the bridge does not need it for
# getDependence, and the CI host does not install it.
if "requests" not in sys.modules:
    sys.modules["requests"] = types.ModuleType("requests")

spec = importlib.util.spec_from_file_location("webhtv_app_bridge", SOURCE / "app.py")
assert spec is not None, "cannot build a module spec for app.py"
assert spec.loader is not None, "app.py module spec has no loader"
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class RaisesFromBody(Exception):
    pass


class SpiderWithoutGetDependence:
    """Mirrors a single-file spider that ignores the base class entirely."""

    def __init__(self, extend=""):
        self.extend = extend


class SpiderWithGetDependence:
    def getDependence(self):
        return ["base", "helpers"]


class SpiderWithBrokenGetDependence:
    def getDependence(self):
        raise RaisesFromBody("broken dependency list")


class GetDependenceBridgeTest(unittest.TestCase):

    def test_missing_get_dependence_is_treated_as_no_dependencies(self):
        result = bridge.getDependence(SpiderWithoutGetDependence())
        self.assertEqual([], result)

    def test_missing_get_dependence_does_not_raise_attribute_error(self):
        # The exact regression: Spider.init() calls this before init(), so an
        # AttributeError here blanks the whole site.
        try:
            bridge.getDependence(SpiderWithoutGetDependence())
        except AttributeError as error:
            self.fail(f"missing getDependence must not raise: {error}")

    def test_base_class_default_is_used_when_present(self):
        self.assertEqual(["base", "helpers"], bridge.getDependence(SpiderWithGetDependence()))

    def test_real_get_dependence_errors_are_not_swallowed(self):
        # getattr (not try/except AttributeError) keeps genuine failures visible.
        with self.assertRaises(RaisesFromBody):
            bridge.getDependence(SpiderWithBrokenGetDependence())

    def test_falsy_return_value_is_preserved(self):
        class ReturnsNone:
            def getDependence(self):
                return None

        class ReturnsEmptyTuple:
            def getDependence(self):
                return ()

        # The Java side guards with `if (dependence != null)`, so None must survive.
        self.assertIsNone(bridge.getDependence(ReturnsNone()))
        self.assertEqual((), bridge.getDependence(ReturnsEmptyTuple()))

    def test_non_callable_attribute_is_not_treated_as_a_getter(self):
        # A spider that happens to expose getDependence = [] as data must not be
        # invoked; returning the value keeps it JSON-able for the Java side.
        class DataAttribute:
            getDependence = []

        self.assertEqual([], bridge.getDependence(DataAttribute()))

    def test_bridge_keeps_the_tvbox_method_surface(self):
        # Guard the neighbouring members so a future edit cannot drop one while
        # touching this function.
        for name in ("init", "homeContent", "categoryContent", "detailContent",
                     "searchContent", "playerContent", "liveContent", "action"):
            self.assertTrue(callable(getattr(bridge, name, None)), name)


if __name__ == "__main__":
    unittest.main()
