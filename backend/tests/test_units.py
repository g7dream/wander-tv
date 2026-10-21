"""Pure helpers: evidence verification, sanitizing, emoji, tool-output parsing."""

import unittest

import fakes  # noqa: F401  (puts src on sys.path with fake boto3)
import pages
from lumo import bedrock, emoji, evidence, sanitize
from lumo.settings import Settings


class EvidenceTests(unittest.TestCase):
    def test_exact_quote(self):
        self.assertTrue(evidence.verify("It is the fastest land animal.", pages.CHEETAH))

    def test_normalization(self):
        self.assertTrue(evidence.verify("‘a mother cheetah RAISES her cubs alone’", pages.CHEETAH))
        self.assertTrue(evidence.verify("fewer than 7,000 adult cheetahs live in the wild", pages.CHEETAH))
        self.assertTrue(evidence.verify("sprint at about 110 km/h (68 mph)", pages.CHEETAH))

    def test_citation_markers_are_ignored(self):
        page = "The peregrine falcon can dive at over 320 km/h.[12] It is the fastest bird."
        self.assertTrue(evidence.verify("The peregrine falcon can dive at over 320 km/h", page))

    def test_fabricated_quote_fails(self):
        self.assertFalse(evidence.verify("Cheetahs can fly for short distances over rivers.", pages.CHEETAH))

    def test_numbers_matter(self):
        # One changed word in a long quote is tolerated, but never a changed number: numbers are the proof.
        self.assertTrue(evidence.verify("A cheetah can dash at about 110 km/h for short bursts", pages.CHEETAH))
        self.assertFalse(evidence.verify("A cheetah can sprint at about 150 km/h for short bursts", pages.CHEETAH))
        self.assertFalse(evidence.verify("sprint at about 150 km/h", pages.CHEETAH))

    def test_short_and_empty(self):
        self.assertFalse(evidence.verify("Cheetah", pages.CHEETAH))
        self.assertFalse(evidence.verify("", pages.CHEETAH))
        self.assertFalse(evidence.verify("It is the fastest land animal.", ""))

    def test_scattered_words_do_not_count(self):
        # Every word exists somewhere on the page, but not together.
        self.assertFalse(evidence.verify("cubs sprint gazelles minute highway tail spine Iran", pages.CHEETAH))

    def test_injection_line_is_detected(self):
        self.assertTrue(evidence.quoted_from_instructions("Garden snails are faster than cheetahs", pages.INJECTION))
        self.assertFalse(evidence.quoted_from_instructions("Snails have a single muscular foot", pages.INJECTION))


class SanitizeTests(unittest.TestCase):
    def test_clamp_fits_with_ellipsis(self):
        s = "The cheetah can run faster than a car on the highway and it does so with style"
        out = sanitize.clamp(s, 40)
        self.assertLessEqual(len(out), 40)
        self.assertTrue(out.endswith("…"))
        self.assertFalse(out[:-1].endswith(" "))

    def test_clamp_without_spaces(self):
        self.assertEqual(len(sanitize.clamp("x" * 50, 10)), 10)

    def test_clean_line(self):
        self.assertEqual(sanitize.clean_line('  "**Hello**\n  world"  '), "Hello world")
        self.assertEqual(sanitize.clean_line(None), "")
        self.assertEqual(sanitize.clean_line(42), "42")

    def test_lone_surrogates_are_repaired(self):
        broken = "cheetah \ud83d"
        self.assertNotIn("\ud83d", sanitize.clean_block(broken, 100))
        sanitize.clean_block(broken, 100).encode("utf-8")  # must not raise

    def test_title_field(self):
        self.assertEqual(sanitize.title_field("built for speed!"), "Built for speed")
        self.assertEqual(sanitize.title_field("SUPER FAST CAT"), "Super fast cat")
        self.assertLessEqual(len(sanitize.title_field("a very long title that keeps going and going forever")), 32)

    def test_norm_key(self):
        self.assertEqual(sanitize.norm_key("Peregrine Falcons"), sanitize.norm_key("peregrine falcon"))
        self.assertEqual(sanitize.norm_key("Pokémon!"), "pokemon")

    def test_as_int_and_bool(self):
        self.assertEqual(sanitize.as_int("7", 2, 3, 2), 3)
        self.assertEqual(sanitize.as_int("nope", 2, 3, 2), 2)
        self.assertEqual(sanitize.as_int(True, 2, 3, 2), 2)
        self.assertTrue(sanitize.as_bool("True"))
        self.assertFalse(sanitize.as_bool("no"))
        self.assertFalse(sanitize.as_bool(None))


class EmojiTests(unittest.TestCase):
    def test_first_emoji_sequences(self):
        self.assertEqual(emoji.first_emoji("go \U0001F680 now"), "\U0001F680")
        astronaut = "\U0001F469‍\U0001F680"
        self.assertEqual(emoji.first_emoji(astronaut + "!"), astronaut)
        self.assertEqual(emoji.first_emoji("\U0001F1EF\U0001F1F5 Japan"), "\U0001F1EF\U0001F1F5")
        self.assertEqual(emoji.first_emoji("❤️ love"), "❤️")
        self.assertEqual(emoji.first_emoji("\U0001F44B\U0001F3FD hi"), "\U0001F44B\U0001F3FD")
        self.assertEqual(emoji.first_emoji("no emoji here"), "")

    def test_strip_emoji(self):
        self.assertEqual(emoji.strip_emoji("Fast \U0001F406 cat ⚡️"), "Fast  cat ")

    def test_keyword_map(self):
        self.assertEqual(emoji.for_text("Cheetahs run fast")[0], "\U0001F406")
        self.assertEqual(emoji.for_text("The octopus has three hearts")[0], "\U0001F419")
        self.assertEqual(emoji.for_text("nothing matches")[0], "")
        self.assertEqual(emoji.for_text("giant ants")[1], "bug")
        self.assertEqual(emoji.for_text("The cards were dealt")[0], "")  # "car" must not match "cards"
        self.assertGreaterEqual(len(emoji.KEYWORDS), 60)


class ToolParsingTests(unittest.TestCase):
    def test_tool_input_as_string(self):
        resp = {"output": {"message": {"content": [{"toolUse": {"name": "x", "input": '{"a": 1}'}}]}}}
        self.assertEqual(bedrock.extract_tool_input(resp, "x"), {"a": 1})

    def test_other_tool_is_ignored(self):
        resp = {"output": {"message": {"content": [{"toolUse": {"name": "other", "input": {"a": 1}}}]}}}
        with self.assertRaises(bedrock.ModelOutputError):
            bedrock.extract_tool_input(resp, "x")

    def test_json_with_braces_inside_strings(self):
        text = 'Sure! {"line": "a {curly} fact", "n": 2} done'
        resp = {"output": {"message": {"content": [{"text": text}]}}}
        self.assertEqual(bedrock.extract_tool_input(resp, "x"), {"line": "a {curly} fact", "n": 2})

    def test_empty_response(self):
        with self.assertRaises(bedrock.ModelOutputError):
            bedrock.extract_tool_input({}, "x")

    def test_split_template(self):
        before, after = bedrock.split_template(f"Do this:\n\n{bedrock.DATA_MARK}\n\nNow call it.")
        self.assertEqual((before, after), ("Do this:", "Now call it."))


class SettingsTests(unittest.TestCase):
    def test_defaults(self):
        import os

        saved = dict(os.environ)
        try:
            for k in ("MODEL_ID", "FALLBACK_MODEL_ID", "VOICE_ID", "GUARDRAIL_ID", "GUARDRAIL_VERSION", "CACHE_TTL_DAYS"):
                os.environ.pop(k, None)
            s = Settings.load()
            self.assertEqual(s.model_id, "eu.anthropic.claude-haiku-5-5")
            self.assertEqual(s.voice_id, "Ivy")
            self.assertFalse(s.guardrail_on)
            self.assertEqual(s.guardrail_version, "")
            self.assertEqual(s.cache_days, 7)
            os.environ["VOICE_ID"] = "Robot"
            os.environ["CACHE_TTL_DAYS"] = "abc"
            self.assertEqual(Settings.load().voice_id, "Ivy")
            self.assertEqual(Settings.load().cache_days, 7)
        finally:
            os.environ.clear()
            os.environ.update(saved)


if __name__ == "__main__":
    unittest.main()
