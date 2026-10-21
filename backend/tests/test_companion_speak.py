"""POST /companion and POST /speak."""

import base64
import os
import unittest

from fakes import FakeClientError, FakePolly, LambdaCase, blocked_response, tool_response
from lumo import fallbacks

HOPPING = {
    "signal": {"reason": "RAPID_HOPPING", "headline": "We've hopped 7 pages in 4 minutes. I know that dizzy feeling.",
               "detail": "7 pages · 4 min · ~12 s each"},
    "stats": {"sessionMinutes": 14, "pagesLast5Min": 7, "avgDwellSec": 12, "scrollSecondsNoClick": 20,
              "sameSiteRepeats": 1, "localHour": 19, "pagesTotal": 22},
}


def react(mood="concerned", line="Whoa, seven pages in four minutes! My wings are spinning.", kind="quest",
          quest_id="speed-safari", detour="", label="Start Speed Safari", detail="Race to find speedy animals"):
    return tool_response("lumo_react", {"mood": mood, "line": line, "suggestion_kind": kind, "quest_id": quest_id,
                                        "detour": detour, "label": label, "detail": detail})


class CompanionTests(LambdaCase):
    def test_quest_suggestion(self):
        self.bedrock.push(react())
        status, out = self.call("POST", "/companion", HOPPING)
        self.assertEqual(status, 200)
        self.assertEqual(out["mood"], "concerned")
        self.assertEqual(out["line"], "Whoa, seven pages in four minutes! My wings are spinning.")
        self.assertEqual(out["suggestion"], {"kind": "quest", "label": "Start Speed Safari",
                                             "detail": "Race to find speedy animals", "questId": "speed-safari"})

    def test_detour_url_comes_from_the_curated_list(self):
        self.bedrock.push(react(mood="sleepy", kind="detour", quest_id="", detour="aurora", label="", detail=""))
        _, out = self.call("POST", "/companion", HOPPING)
        self.assertEqual(out["suggestion"]["kind"], "detour")
        self.assertEqual(out["suggestion"]["url"], "https://simple.wikipedia.org/wiki/Aurora")
        self.assertTrue(out["suggestion"]["label"])  # curated label when the model left it empty
        self.assertNotIn("questId", out["suggestion"])

    def test_invalid_picks_become_the_curated_suggestion(self):
        self.bedrock.push(react(mood="furious", kind="detour", detour="https://evil.example/", quest_id=""))
        _, out = self.call("POST", "/companion", HOPPING)
        self.assertEqual(out["mood"], "concerned")  # curated mood for hopping
        self.assertEqual(out["suggestion"], fallbacks.companion_for("RAPID_HOPPING")["suggestion"])
        self.assertIn(out["suggestion"].get("url") or out["suggestion"].get("questId"),
                      fallbacks.DETOUR_URLS | set(fallbacks.QUEST_IDS))

    def test_none_still_offers_something(self):
        self.bedrock.push(react(kind="none", quest_id="", label="", detail=""))
        _, out = self.call("POST", "/companion", HOPPING)
        self.assertIsNotNone(out["suggestion"])

    def test_only_timing_stats_reach_the_model(self):
        payload = dict(HOPPING)
        payload["stats"] = dict(HOPPING["stats"], url="https://secret.example/page")
        self.bedrock.push(react())
        self.call("POST", "/companion", payload)
        sent = repr(self.bedrock.calls)
        self.assertNotIn("secret.example", sent)
        self.assertIn("7 pages in the last 5 min", self.bedrock.guarded_text())
        self.assertEqual(self.bedrock.calls[0]["toolConfig"]["toolChoice"], {"tool": {"name": "lumo_react"}})

    def test_long_line_is_clamped(self):
        self.bedrock.push(react(line="Buzz " * 80))
        _, out = self.call("POST", "/companion", HOPPING)
        self.assertLessEqual(len(out["line"]), 120)

    def test_unknown_reason_is_400(self):
        status, out = self.call("POST", "/companion", {"signal": {"reason": "BORED"}, "stats": {}})
        self.assertEqual(status, 400)
        self.assertEqual(out["field"], "signal.reason")

    def test_blocked_returns_curated_reaction(self):
        self.bedrock.push(blocked_response())
        _, out = self.call("POST", "/companion", {"signal": {"reason": "LATE_NIGHT"}, "stats": {"localHour": 23}})
        self.assertTrue(out["blocked"])
        self.assertEqual(out["mood"], "sleepy")
        self.assertEqual(out["suggestion"]["url"], "https://simple.wikipedia.org/wiki/Aurora")


class SpeakTests(LambdaCase):
    def test_returns_base64_mp3(self):
        status, out = self.call("POST", "/speak", {"text": "The cheetah is the fastest land animal."})
        self.assertEqual(status, 200)
        self.assertEqual(base64.b64decode(out["audio"]), FakePolly.AUDIO)
        self.assertEqual(out["voice"], "Ivy")
        self.assertFalse(out["cached"])
        call = self.polly.calls[0]
        self.assertEqual(call["Engine"], "neural")
        self.assertEqual(call["OutputFormat"], "mp3")
        self.assertEqual(call["VoiceId"], "Ivy")
        self.assertEqual(call["TextType"], "ssml")
        self.assertIn("The cheetah is the fastest land animal.", call["Text"])

    def test_text_is_escaped_capped_and_emoji_free(self):
        text = "Tom & Jerry <3 cheetahs \U0001F406 " + "word " * 300
        self.call("POST", "/speak", {"text": text})
        ssml = self.polly.calls[0]["Text"]
        self.assertIn("Tom &amp; Jerry &lt;3", ssml)
        self.assertNotIn("\U0001F406", ssml)
        inner = ssml.split(">", 2)[2].rsplit("</prosody>", 1)[0]
        self.assertLessEqual(len(inner.replace("&amp;", "&").replace("&lt;", "<")), 600)

    def test_voice_choice_is_validated(self):
        self.call("POST", "/speak", {"text": "Hello there!", "voice": "Kevin"})
        self.assertEqual(self.polly.calls[-1]["VoiceId"], "Kevin")
        self.call("POST", "/speak", {"text": "Hello again!", "voice": "NotAVoice"})
        self.assertEqual(self.polly.calls[-1]["VoiceId"], "Ivy")

    def test_env_voice_is_used(self):
        os.environ["VOICE_ID"] = "Justin"
        _, out = self.call("POST", "/speak", {"text": "Hi!"})
        self.assertEqual(out["voice"], "Justin")

    def test_second_read_comes_from_the_cache(self):
        self.call("POST", "/speak", {"text": "Moths navigate by the moon."})
        _, out = self.call("POST", "/speak", {"text": "Moths navigate by the moon."})
        self.assertTrue(out["cached"])
        self.assertEqual(len(self.polly.calls), 1)
        self.assertEqual(base64.b64decode(out["audio"]), FakePolly.AUDIO)

    def test_invalid_ssml_retries_as_plain_text(self):
        self.polly.errors.append(FakeClientError("InvalidSsmlException", "bad ssml", "SynthesizeSpeech"))
        status, _ = self.call("POST", "/speak", {"text": "Hello!"})
        self.assertEqual(status, 200)
        self.assertEqual(self.polly.calls[1]["TextType"], "text")

    def test_throttled_polly_is_503(self):
        self.polly.errors.append(FakeClientError("ThrottlingException", "slow", "SynthesizeSpeech"))
        status, out = self.call("POST", "/speak", {"text": "Hello!"})
        self.assertEqual(status, 503)
        self.assertEqual(out["error"], "busy")

    def test_other_polly_errors_are_502(self):
        self.polly.errors.append(FakeClientError("ValidationException", "voice not supported", "SynthesizeSpeech"))
        status, out = self.call("POST", "/speak", {"text": "Hello!"})
        self.assertEqual(status, 502)
        self.assertEqual(out["error"], "speech_unavailable")

    def test_empty_text_is_400(self):
        status, out = self.call("POST", "/speak", {"text": "   \U0001F406  "})
        self.assertEqual(status, 400)
        self.assertEqual(out["field"], "text")


if __name__ == "__main__":
    unittest.main()
