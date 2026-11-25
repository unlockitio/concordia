import unittest
from credential_validity import validity_seconds


class CredentialValidityTest(unittest.TestCase):
    def test_default(self):
        self.assertEqual(validity_seconds(), 30 * 86400)

    def test_custom_two_days(self):
        self.assertEqual(validity_seconds("2d"), 2 * 86400)
        self.assertEqual(validity_seconds("48h"), 2 * 86400)

    def test_bounds(self):
        self.assertEqual(validity_seconds("1h"), 3600)
        self.assertEqual(validity_seconds("365d"), 365 * 86400)
        self.assertEqual(validity_seconds("8760h"), 365 * 86400)

    def test_invalid(self):
        for value in ("", "0h", "0d", "366d", "8761h", "-1d", "1.5h", "2D", " 2d", "2d ", "01d", "3600", "1h;echo secret"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validity_seconds(value)


if __name__ == "__main__":
    unittest.main()
