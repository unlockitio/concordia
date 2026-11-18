import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("readback", Path(__file__).with_name("publish-readback.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReadbackTest(unittest.TestCase):
    def test_publishes_five_fields_and_rejects_unverified_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = dict(member="member", governor="cid", userName="babycdf-agent", verifiedRights=["right"])
            (root / "bootstrap.json").write_text(json.dumps(state))
            (root / "pkg.json").write_text(json.dumps(dict(main_package_id="a" * 64)))
            module.publish(root, "41010", root / "pkg.json")
            expected = "41010 member " + "a" * 64 + " cid babycdf-agent\n"
            self.assertEqual(expected, (root / "readback").read_text())
            self.assertEqual(0o644, (root / "readback").stat().st_mode & 0o777)
            state["verifiedRights"] = []
            (root / "bootstrap.json").write_text(json.dumps(state))
            with self.assertRaises(ValueError):
                module.publish(root, "41010", root / "pkg.json")
            self.assertEqual(expected, (root / "readback").read_text())
            self.assertEqual(0o644, (root / "readback").stat().st_mode & 0o777)


    def test_invalid_inputs_never_publish(self):
        for port, package, state in [
            ("41010", "a" * 64, dict(member="   ", governor="c", userName="u", verifiedRights=[1])),
            ("41010", "a" * 64, dict(member=12, governor="c", userName="u", verifiedRights=[1])),
            ("041010", "a" * 64, dict(member="m", governor="c", userName="u", verifiedRights=[1])),
            ("0", "a" * 64, dict(member="m", governor="c", userName="u", verifiedRights=[1])),
            ("65536", "a" * 64, dict(member="m", governor="c", userName="u", verifiedRights=[1])),
            ("41010", "bad", dict(member="m", governor="c", userName="u", verifiedRights=[1])),
            ("41010", "a" * 64, dict(governor="c", userName="u", verifiedRights=[1])),
        ]:
            with self.subTest(port=port, package=package), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "bootstrap.json").write_text(json.dumps(state))
                (root / "pkg.json").write_text(json.dumps(dict(main_package_id=package)))
                with self.assertRaises(ValueError):
                    module.publish(root, port, root / "pkg.json")
                self.assertFalse((root / "readback").exists())


if __name__ == "__main__":
    unittest.main()
