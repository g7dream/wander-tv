"""POST /cards: forced tool use, clamping, cache, guardrails, fallbacks."""

import hashlib
import os
import time
import unittest

import pages
from fakes import FakeClientError, LambdaCase, blocked_response, text_response, tool_response
from lumo import emoji

GOOD_CARDS = [
    {"title": "Meet the cheetah", "line": "The cheetah is a big wild cat that lives mostly in Africa.", "emoji": "\U0001F406", "icon": "animal"},
    {"title": "Built for speed", "line": "It can sprint about 110 km/h, faster than a car on the highway!", "emoji": "⚡", "icon": "speed"},
    {"title": "Giant strides", "line": "One stride can cover about 7 meters, longer than a car.", "emoji": "\U0001F4CF", "icon": "size"},
    {"title": "Hide and seek cubs", "line": "Cubs have fluffy grey fur that helps them hide in tall grass.", "emoji": "\U0001F33E", "icon": "family"},
]


def body(text=pages.CHEETAH, url=pages.CHEETAH_URL, title=pages.CHEETAH_TITLE):
    return {"url": url, "title": title, "text": text}


class CardsTests(LambdaCase):
    def test_success_returns_clean_cards(self):
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS}))
        status, out = self.call("POST", "/cards", body())
        self.assertEqual(status, 200)
        self.assertFalse(out["cached"])
        self.assertNotIn("blocked", out)
        self.assertEqual([c["title"] for c in out["cards"]], [c["title"] for c in GOOD_CARDS])
        for card in out["cards"]:
            self.assertEqual(set(card), {"title", "line", "emoji", "icon"})
            self.assertLessEqual(len(card["title"]), 32)
            self.assertLessEqual(len(card["line"]), 110)
            self.assertEqual(emoji.first_emoji(card["emoji"]), card["emoji"])

    def test_request_forces_the_tool_and_guards_the_page(self):
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS}))
        self.call("POST", "/cards", body())
        req = self.bedrock.calls[0]
        self.assertEqual(req["modelId"], "eu.anthropic.claude-haiku-5-5")
        self.assertEqual(req["toolConfig"]["toolChoice"], {"tool": {"name": "make_cards"}})
        self.assertEqual(req["toolConfig"]["tools"][0]["toolSpec"]["name"], "make_cards")
        self.assertIn("json", req["toolConfig"]["tools"][0]["toolSpec"]["inputSchema"])
        self.assertEqual(req["guardrailConfig"]["guardrailIdentifier"], "gr-test")
        self.assertEqual(req["guardrailConfig"]["guardrailVersion"], "1")
        guarded = self.bedrock.guarded_text()
        self.assertTrue(guarded.startswith("<page>") and guarded.endswith("</page>"))
        self.assertIn("fastest land animal", guarded)
        self.assertNotIn("fastest land animal", self.bedrock.instruction_text())
        self.assertIn("UNTRUSTED", req["system"][0]["text"])
        self.assertNotIn("temperature", req["inferenceConfig"])  # Claude 5.x rejects custom sampling
        # The final strings were re-checked with ApplyGuardrail (Converse does not screen toolUse args).
        self.assertEqual(len(self.bedrock.guardrail_calls), 1)
        self.assertEqual(self.bedrock.guardrail_calls[0]["source"], "OUTPUT")

    def test_without_guardrail_page_goes_as_plain_text(self):
        os.environ["GUARDRAIL_ID"] = ""
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS}))
        self.call("POST", "/cards", body())
        req = self.bedrock.calls[0]
        self.assertNotIn("guardrailConfig", req)
        self.assertFalse(any("guardContent" in b for b in self.bedrock.user_blocks()))
        self.assertIn("<page>", self.bedrock.instruction_text())
        self.assertEqual(self.bedrock.guardrail_calls, [])

    def test_too_many_cards_are_capped_at_five(self):
        facts = ["Cheetahs live mostly in Africa.", "They sprint about 110 km/h in short bursts.",
                 "One stride covers about 7 meters.", "The tail steers like a rudder.",
                 "Mothers raise cubs alone for 18 months.", "Cubs hide in tall grass with fluffy fur.",
                 "They hunt gazelles during the day.", "Fewer than 7,000 live in the wild today."]
        many = [{"title": f"Fact number {i}", "line": f, "emoji": "\U0001F406", "icon": "speed"}
                for i, f in enumerate(facts, 1)]
        self.bedrock.push(tool_response("make_cards", {"cards": many}))
        _, out = self.call("POST", "/cards", body())
        self.assertEqual(len(out["cards"]), 5)

    def test_too_few_cards_are_padded_from_the_page(self):
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS[:1]}))
        _, out = self.call("POST", "/cards", body())
        self.assertEqual(len(out["cards"]), 3)
        self.assertEqual(out["cards"][0]["title"], "Meet the cheetah")
        for card in out["cards"][1:]:
            self.assertIn(card["title"], ("Did you know?", "Fun fact", "One more thing", "Lumo noticed"))
            self.assertLessEqual(len(card["line"]), 110)

    def test_long_and_messy_fields_are_clamped(self):
        messy = [
            {"title": "\U0001F406 **THE INCREDIBLY FAST AND FURIOUS SPOTTED CAT OF AFRICA**",
             "line": "\"" + "The cheetah runs so very fast " * 10 + "\"", "emoji": "\U0001F406\U0001F406⚡",
             "icon": "Speed Cat!"},
            {"title": "Built for speed", "line": "short", "emoji": "x", "icon": "speed"},  # line too short -> dropped
            {"title": "Built for speed", "line": "A duplicate title must not appear twice in a deck.", "emoji": "", "icon": ""},
            {"title": "Eyes on the prize", "line": "Cheetahs hunt by sight during the day", "emoji": "", "icon": "body"},
            {"title": "Tail rudder", "line": "Its long tail helps it steer like a rudder when it turns.", "emoji": "", "icon": ""},
        ]
        self.bedrock.push(tool_response("make_cards", {"cards": messy}))
        _, out = self.call("POST", "/cards", body())
        first = out["cards"][0]
        self.assertLessEqual(len(first["title"]), 32)
        self.assertNotIn("*", first["title"])
        self.assertEqual(emoji.strip_emoji(first["title"]), first["title"])
        self.assertFalse(first["title"].isupper())
        self.assertLessEqual(len(first["line"]), 110)
        self.assertTrue(first["line"].endswith("…"))
        self.assertFalse(first["line"].startswith('"'))
        self.assertEqual(first["emoji"], "\U0001F406")
        self.assertEqual(first["icon"], "speed")
        titles = [c["title"] for c in out["cards"]]
        self.assertEqual(len(titles), len(set(t.lower() for t in titles)))
        eyes = next(c for c in out["cards"] if c["title"] == "Eyes on the prize")
        self.assertEqual(eyes["emoji"], emoji.ICON_EMOJI["body"])  # no emoji from the model: icon map
        self.assertTrue(eyes["line"].endswith("."))
        tail = next(c for c in out["cards"] if c["title"] == "Tail rudder")
        self.assertTrue(tail["emoji"])  # keyword map or default, never empty
        self.assertEqual(tail["icon"], "spark")

    def test_cache_miss_then_hit(self):
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS}))
        _, first = self.call("POST", "/cards", body())
        self.assertFalse(first["cached"])
        self.assertEqual(len(self.dynamo.puts), 1)
        _, item = self.dynamo.puts[0]
        text_hash = hashlib.sha256(pages.CHEETAH.encode()).hexdigest()
        expected = "cards#" + hashlib.sha256((pages.CHEETAH_URL + text_hash).encode()).hexdigest()
        self.assertEqual(item["pk"]["S"], expected)
        ttl = int(item["ttl"]["N"])
        self.assertAlmostEqual(ttl - time.time(), 7 * 86400, delta=120)
        self.assertEqual(item["model"]["S"], "eu.anthropic.claude-haiku-5-5")

        _, second = self.call("POST", "/cards", body())
        self.assertTrue(second["cached"])
        self.assertEqual(second["cards"], first["cards"])
        self.assertEqual(len(self.bedrock.calls), 1)  # no second model call
        self.assertTrue(self.request_log()["cached"])

    def test_same_url_with_changed_text_is_a_miss(self):
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS}),
                          tool_response("make_cards", {"cards": GOOD_CARDS}))
        self.call("POST", "/cards", body())
        _, out = self.call("POST", "/cards", body(text=pages.CHEETAH + "\nA new paragraph appeared today."))
        self.assertFalse(out["cached"])
        self.assertEqual(len(self.bedrock.calls), 2)

    def test_expired_cache_entry_is_ignored(self):
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS}),
                          tool_response("make_cards", {"cards": GOOD_CARDS}))
        self.call("POST", "/cards", body())
        for item in self.dynamo.items.values():
            item["ttl"] = {"N": str(int(time.time()) - 10)}
        _, out = self.call("POST", "/cards", body())
        self.assertFalse(out["cached"])

    def test_cache_failures_never_break_cards(self):
        self.dynamo.fail_get = True
        self.dynamo.fail_put = True
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS}))
        status, out = self.call("POST", "/cards", body())
        self.assertEqual(status, 200)
        self.assertEqual(len(out["cards"]), 4)
        self.assertTrue(any(r.get("event") == "cache_get_failed" for r in self.logs))

    def test_no_table_means_no_cache(self):
        os.environ["TABLE_NAME"] = ""
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS}))
        status, _ = self.call("POST", "/cards", body())
        self.assertEqual(status, 200)
        self.assertEqual(self.dynamo.gets, 0)

    def test_short_text_skips_the_model(self):
        status, out = self.call("POST", "/cards", body(text="Just a menu. Home. About."))
        self.assertEqual(status, 200)
        self.assertEqual(out["cards"][0]["title"], "Not much to read here")
        self.assertEqual(self.bedrock.calls, [])

    def test_text_is_capped_at_6000_chars(self):
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS}))
        self.call("POST", "/cards", body(text=pages.CHEETAH * 20))
        self.assertLessEqual(len(self.bedrock.guarded_text()), 6000 + 200)
        self.assertEqual(self.request_log()["text_chars"], 6000)

    def test_guardrail_block_returns_safe_deck(self):
        self.bedrock.push(blocked_response())
        status, out = self.call("POST", "/cards", body())
        self.assertEqual(status, 200)
        self.assertTrue(out["blocked"])
        self.assertEqual(len(out["cards"]), 1)
        self.assertEqual(set(out["cards"][0]), {"title", "line", "emoji", "icon"})
        self.assertEqual(self.dynamo.puts, [])  # blocked results are never cached
        self.assertTrue(self.request_log()["blocked"])

    def test_output_guardrail_intervention_blocks(self):
        self.bedrock.guardrail_action = "GUARDRAIL_INTERVENED"
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS}))
        _, out = self.call("POST", "/cards", body())
        self.assertTrue(out["blocked"])
        self.assertEqual(self.dynamo.puts, [])

    def test_output_guardrail_error_fails_open(self):
        self.bedrock.guardrail_action = FakeClientError("ThrottlingException", "slow", "ApplyGuardrail")
        self.bedrock.push(tool_response("make_cards", {"cards": GOOD_CARDS}))
        status, out = self.call("POST", "/cards", body())
        self.assertEqual(status, 200)
        self.assertNotIn("blocked", out)

    def test_primary_throttled_uses_fallback_model(self):
        self.bedrock.push(FakeClientError("ThrottlingException", "Too many requests"),
                          tool_response("make_cards", {"cards": GOOD_CARDS}))
        status, out = self.call("POST", "/cards", body())
        self.assertEqual(status, 200)
        self.assertEqual(self.bedrock.calls[1]["modelId"], "eu.amazon.nova-2-lite-v1:0")
        self.assertEqual(self.bedrock.calls[1]["inferenceConfig"].get("temperature"), 0.0)
        log = self.request_log()
        self.assertTrue(log["fallback_model"])
        self.assertEqual(log["model"], "eu.amazon.nova-2-lite-v1:0")

    def test_model_access_denied_uses_fallback_model(self):
        self.bedrock.push(FakeClientError("AccessDeniedException", "You don't have access to the model"),
                          tool_response("make_cards", {"cards": GOOD_CARDS}))
        status, _ = self.call("POST", "/cards", body())
        self.assertEqual(status, 200)

    def test_both_models_throttled_is_503(self):
        self.bedrock.push(FakeClientError("ThrottlingException", "slow down"),
                          FakeClientError("ServiceUnavailableException", "busy"))
        status, out = self.call("POST", "/cards", body())
        self.assertEqual(status, 503)
        self.assertEqual(out["error"], "busy")

    def test_both_models_broken_is_502(self):
        self.bedrock.push(FakeClientError("ValidationException", "The provided model identifier is invalid."),
                          FakeClientError("ResourceNotFoundException", "Model not found"))
        status, out = self.call("POST", "/cards", body())
        self.assertEqual(status, 502)
        self.assertEqual(out["error"], "model_unavailable")

    def test_forced_tool_choice_rejected_retries_with_any_then_auto(self):
        self.bedrock.push(
            FakeClientError("ValidationException", "This model doesn't support the toolChoice.tool field."),
            FakeClientError("ValidationException", "toolChoice any is not supported for this model."),
            tool_response("make_cards", {"cards": GOOD_CARDS}),
        )
        status, _ = self.call("POST", "/cards", body())
        self.assertEqual(status, 200)
        self.assertEqual(self.bedrock.calls[1]["toolConfig"]["toolChoice"], {"any": {}})
        self.assertNotIn("toolChoice", self.bedrock.calls[2]["toolConfig"])
        self.assertEqual(self.bedrock.calls[2]["modelId"], "eu.anthropic.claude-haiku-5-5")

    def test_json_in_text_is_accepted_when_no_tool_call(self):
        self.bedrock.push(text_response('Here you go: {"cards": [{"title": "Built for speed", "line": "The cheetah '
                                        'sprints about 110 km/h for short bursts.", "emoji": "⚡", "icon": "speed"}]}'))
        status, out = self.call("POST", "/cards", body())
        self.assertEqual(status, 200)
        self.assertEqual(out["cards"][0]["title"], "Built for speed")
        self.assertEqual(len(out["cards"]), 3)  # padded

    def test_garbage_answer_tries_fallback_model(self):
        self.bedrock.push(text_response("I love cheetahs!"), tool_response("make_cards", {"cards": GOOD_CARDS}))
        status, _ = self.call("POST", "/cards", body())
        self.assertEqual(status, 200)
        self.assertEqual(len(self.bedrock.calls), 2)

    def test_no_fallback_configured(self):
        os.environ["FALLBACK_MODEL_ID"] = ""
        self.bedrock.push(FakeClientError("ThrottlingException", "slow"))
        status, _ = self.call("POST", "/cards", body())
        self.assertEqual(status, 503)
        self.assertEqual(len(self.bedrock.calls), 1)


if __name__ == "__main__":
    unittest.main()
