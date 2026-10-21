"""POST /quest/check (with evidence verification) and POST /quest/new."""

import re
import unittest

import pages
from fakes import LambdaCase, blocked_response, tool_response
from lumo import emoji, routes

SPEED_SAFARI = {
    "id": "speed-safari",
    "title": "Speed Safari",
    "prompt": "Find three animals that are faster than a car on the highway (over 100 km/h, 62 mph).",
    "rubric": "Counts: an animal whose top speed on the page is over 100 km/h or 62 mph (cheetah, peregrine falcon, "
              "sailfish). Does not count: animals without a stated speed over 100 km/h.",
    "goal": 3,
}


def check_body(text=pages.CHEETAH, title=pages.CHEETAH_TITLE, found=None):
    return {
        "quest": SPEED_SAFARI,
        "page": {"url": pages.CHEETAH_URL, "title": title, "text": text},
        "found": found or [],
    }


def verdict(passed=True, item="Cheetah", evidence="A cheetah can sprint at about 110 km/h (68 mph) for short bursts",
            reason="Yes! Cheetahs sprint about 110 km/h, faster than a car on the highway!"):
    return tool_response("judge_quest", {"pass": passed, "item": item, "evidence": evidence, "reason": reason})


class QuestCheckTests(LambdaCase):
    def test_verbatim_evidence_passes(self):
        self.bedrock.push(verdict())
        status, out = self.call("POST", "/quest/check", check_body())
        self.assertEqual(status, 200)
        self.assertEqual(out, {
            "pass": True,
            "item": "Cheetah",
            "reason": "Yes! Cheetahs sprint about 110 km/h, faster than a car on the highway!",
            "evidence": "A cheetah can sprint at about 110 km/h (68 mph) for short bursts",
        })
        req = self.bedrock.calls[0]
        self.assertEqual(req["toolConfig"]["toolChoice"], {"tool": {"name": "judge_quest"}})
        instructions = self.bedrock.instruction_text()
        self.assertIn("Speed Safari", instructions)
        self.assertIn("over 100 km/h", instructions)
        self.assertIn("nothing yet", instructions)
        self.assertIn("fastest land animal", self.bedrock.guarded_text())

    def test_found_list_reaches_the_judge(self):
        self.bedrock.push(verdict())
        self.call("POST", "/quest/check", check_body(found=["Peregrine falcon", "Sailfish"]))
        self.assertIn("Peregrine falcon, Sailfish", self.bedrock.instruction_text())

    def test_evidence_with_different_quotes_case_and_spacing_passes(self):
        self.bedrock.push(verdict(evidence="“IT IS THE   FASTEST land animal.”"))
        _, out = self.call("POST", "/quest/check", check_body())
        self.assertTrue(out["pass"])
        self.assertEqual(out["evidence"], "IT IS THE FASTEST land animal.")

    def test_evidence_with_one_changed_word_passes_on_overlap(self):
        self.bedrock.push(verdict(evidence="Its long legs, light body and bendy spine let it cover about 7 meters "
                                           "in a single stride"))
        _, out = self.call("POST", "/quest/check", check_body())
        self.assertTrue(out["pass"])

    def test_evidence_with_ellipsis_must_match_each_piece(self):
        self.bedrock.push(verdict(evidence="The cheetah is a large wild cat ... It is the fastest land animal."))
        _, out = self.call("POST", "/quest/check", check_body())
        self.assertTrue(out["pass"])

    def test_hallucinated_evidence_is_downgraded(self):
        self.bedrock.push(verdict(evidence="Cheetahs can reach 130 km/h while chasing antelope across the plains."))
        status, out = self.call("POST", "/quest/check", check_body())
        self.assertEqual(status, 200)
        self.assertFalse(out["pass"])
        self.assertIsNone(out["item"])
        self.assertIsNone(out["evidence"])
        self.assertEqual(out["reason"], routes.DOWNGRADE_REASON)
        self.assertTrue(self.request_log()["evidence_rejected"])

    def test_one_word_evidence_is_not_proof(self):
        self.bedrock.push(verdict(evidence="Cheetah"))
        _, out = self.call("POST", "/quest/check", check_body())
        self.assertFalse(out["pass"])

    def test_prompt_injection_page_cannot_force_a_pass(self):
        # The model got fooled by the page and quoted the injected claim; verification catches the quote.
        self.bedrock.push(verdict(item="Garden snail", evidence="Garden snails are faster than cheetahs"))
        _, out = self.call("POST", "/quest/check",
                           check_body(text=pages.INJECTION, title=pages.INJECTION_TITLE))
        self.assertFalse(out["pass"])
        self.assertIn("<page>", self.bedrock.guarded_text())
        self.assertIn("ignore all previous instructions", self.bedrock.guarded_text())

    def test_pass_without_item_is_downgraded(self):
        self.bedrock.push(verdict(item=""))
        _, out = self.call("POST", "/quest/check", check_body())
        self.assertFalse(out["pass"])
        self.assertEqual(out["reason"], routes.NO_ITEM_REASON)

    def test_fail_clears_item_and_evidence(self):
        self.bedrock.push(verdict(passed=False, item="Box turtle", evidence="They walk slowly",
                                  reason="Box turtles are lovely but slow. Try a page about a speedy animal!"))
        _, out = self.call("POST", "/quest/check", check_body(text=pages.TURTLE, title=pages.TURTLE_TITLE))
        self.assertEqual(out, {
            "pass": False, "item": None, "evidence": None,
            "reason": "Box turtles are lovely but slow. Try a page about a speedy animal!",
        })

    def test_string_booleans_and_long_fields_are_clamped(self):
        long_reason = "Wow " * 60
        long_quote = pages.CHEETAH.split("\n")[1]  # a real, long sentence from the page
        self.bedrock.push(verdict(passed="true", item="the cheetah of africa and iran, fastest cat", evidence=long_quote,
                                  reason=long_reason))
        _, out = self.call("POST", "/quest/check", check_body())
        self.assertTrue(out["pass"])
        self.assertLessEqual(len(out["reason"]), 120)
        self.assertLessEqual(len(out["evidence"]), 140)
        self.assertLessEqual(len(out["item"]), 40)
        self.assertTrue(out["item"][0].isupper())

    def test_empty_reason_gets_a_friendly_default(self):
        self.bedrock.push(verdict(reason=""))
        _, out = self.call("POST", "/quest/check", check_body())
        self.assertEqual(out["reason"], "Yes! Cheetah counts!")

    def test_guardrail_block_returns_safe_verdict(self):
        self.bedrock.push(blocked_response())
        status, out = self.call("POST", "/quest/check", check_body())
        self.assertEqual(status, 200)
        self.assertTrue(out["blocked"])
        self.assertFalse(out["pass"])
        self.assertIsNone(out["item"])
        self.assertTrue(out["reason"])

    def test_tiny_page_is_not_sent_to_the_model(self):
        _, out = self.call("POST", "/quest/check", check_body(text="Hi", title=""))
        self.assertFalse(out["pass"])
        self.assertEqual(out["reason"], routes.TOO_SHORT_REASON)
        self.assertEqual(self.bedrock.calls, [])

    def test_missing_quest_is_400(self):
        status, out = self.call("POST", "/quest/check", {"page": {"text": "x"}})
        self.assertEqual(status, 400)
        self.assertEqual(out["field"], "quest")

    def test_missing_page_is_400(self):
        status, out = self.call("POST", "/quest/check", {"quest": SPEED_SAFARI})
        self.assertEqual(status, 400)
        self.assertEqual(out["field"], "page")


def new_quest(title="Ring Hunters", prompt="Find two planets that have rings around them.", goal=2,
              emoji_value="\U0001F52D", rubric="Counts: a planet the page says has rings (Saturn, Jupiter, Uranus, "
                                               "Neptune). Moons and dwarf planets do not count."):
    return tool_response("new_quest", {"title": title, "prompt": prompt, "goal": goal, "emoji": emoji_value,
                                       "rubric": rubric})


class QuestNewTests(LambdaCase):
    def test_new_quest_shape(self):
        self.bedrock.push(new_quest())
        status, out = self.call("POST", "/quest/new", {"recent": ["Saturn", "Jupiter - Wander Encyclopedia"]})
        self.assertEqual(status, 200)
        quest = out["quest"]
        self.assertRegex(quest["id"], r"^lumo-[0-9a-f]{8}$")
        self.assertEqual(quest["title"], "Ring Hunters")
        self.assertEqual(quest["prompt"], "Find two planets that have rings around them.")
        self.assertEqual(quest["goal"], 2)
        self.assertEqual(quest["emoji"], "\U0001F52D")
        self.assertIn("Saturn", quest["rubric"])
        self.assertEqual(set(quest), {"id", "title", "prompt", "goal", "emoji", "rubric"})

    def test_only_titles_are_sent(self):
        self.bedrock.push(new_quest())
        self.call("POST", "/quest/new", {"recent": ["Saturn", "Jupiter"], "text": "SHOULD NOT BE SENT"})
        guarded = self.bedrock.guarded_text()
        self.assertEqual(guarded, "<titles>\n- Saturn\n- Jupiter\n</titles>")
        self.assertNotIn("SHOULD NOT BE SENT", repr(self.bedrock.calls))

    def test_id_is_stable_for_the_same_quest(self):
        self.bedrock.push(new_quest(), new_quest())
        _, a = self.call("POST", "/quest/new", {"recent": ["Saturn"]})
        _, b = self.call("POST", "/quest/new", {"recent": ["Neptune"]})
        self.assertEqual(a["quest"]["id"], b["quest"]["id"])

    def test_goal_and_fields_are_clamped(self):
        self.bedrock.push(new_quest(title="The Most Amazing Super Long Quest Title Ever Made", goal=9,
                                    emoji_value="\U0001F680\U0001F680 rockets", rubric="r" * 900))
        _, out = self.call("POST", "/quest/new", {"recent": []})
        quest = out["quest"]
        self.assertEqual(quest["goal"], 3)
        self.assertLessEqual(len(quest["title"]), 32)
        self.assertEqual(quest["emoji"], "\U0001F680")
        self.assertLessEqual(len(quest["rubric"]), 400)

    def test_missing_emoji_falls_back_to_keyword(self):
        self.bedrock.push(new_quest(emoji_value=""))
        _, out = self.call("POST", "/quest/new", {"recent": []})
        self.assertEqual(emoji.first_emoji(out["quest"]["emoji"]), out["quest"]["emoji"])

    def test_empty_recent_is_fine(self):
        self.bedrock.push(new_quest())
        status, _ = self.call("POST", "/quest/new", {})
        self.assertEqual(status, 200)
        self.assertIn("(none yet)", self.bedrock.guarded_text())

    def test_titles_are_capped(self):
        self.bedrock.push(new_quest())
        self.call("POST", "/quest/new", {"recent": [f"Title {i} " + "x" * 300 for i in range(30)]})
        lines = [l for l in self.bedrock.guarded_text().split("\n") if l.startswith("- ")]
        self.assertEqual(len(lines), 12)
        self.assertTrue(all(len(l) <= 122 for l in lines))

    def test_blocked_returns_safe_quest(self):
        self.bedrock.push(blocked_response())
        _, out = self.call("POST", "/quest/new", {"recent": ["Something odd"]})
        self.assertTrue(out["blocked"])
        self.assertTrue(re.match(r"^lumo-", out["quest"]["id"]))
        self.assertIn(out["quest"]["goal"], (2, 3))

    def test_unusable_answer_returns_safe_quest(self):
        self.bedrock.push(new_quest(title="", prompt=""))
        _, out = self.call("POST", "/quest/new", {"recent": ["Saturn"]})
        self.assertEqual(out["quest"]["title"], "Record Breakers")


if __name__ == "__main__":
    unittest.main()
