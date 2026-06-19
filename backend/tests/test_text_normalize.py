import unittest

from app.services.text_normalize import fix_spanish_llm_text


class FixSpanishLlmTextTests(unittest.TestCase):
    def test_acute_before_vowel(self) -> None:
        self.assertEqual(fix_spanish_llm_text("´estos"), "éstos")
        self.assertEqual(fix_spanish_llm_text("´Estos"), "Éstos")

    def test_tilde_in_word(self) -> None:
        self.assertEqual(fix_spanish_llm_text("entra˜ nables"), "entrañables")
        self.assertEqual(fix_spanish_llm_text("Entra˜nables"), "Entrañables")

    def test_preserves_valid_text(self) -> None:
        self.assertEqual(fix_spanish_llm_text("Entrañables éstos"), "Entrañables éstos")


if __name__ == "__main__":
    unittest.main()
