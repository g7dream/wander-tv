"""A deterministic stand-in for Bedrock, DynamoDB and Polly, used only by dev_server.py --fake.

It lets the TV app and the smoke-test scripts run end to end on a laptop with no AWS account. Answers are
simple heuristics over the page text (clearly not AI); the real Lambda never imports this module.
"""

import io
import re
import struct

_SENT = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9])")
_SPEED = re.compile(r"(\d{2,3})\s?(km/h|kilometers per hour|kilometres per hour|mph)", re.IGNORECASE)


def _page_text(request: dict) -> str:
    parts = []
    for block in request["messages"][0]["content"]:
        if "guardContent" in block:
            parts.append(block["guardContent"]["text"]["text"])
        elif "text" in block:
            parts.append(block["text"])
    joined = "\n".join(parts)
    m = re.search(r"<(page|titles|signal)>\n(.*?)\n</\1>", joined, re.DOTALL)
    return m.group(2) if m else joined


def _sentences(text: str) -> list:
    out = []
    for line in text.split("\n"):
        for s in _SENT.split(line.strip()):
            if len(s) >= 40 and len(s.split()) >= 6:
                out.append(s.strip())
    return out


def _tool_use(name: str, data: dict) -> dict:
    return {
        "output": {"message": {"role": "assistant", "content": [{"toolUse": {"toolUseId": "dev", "name": name,
                                                                              "input": data}}]}},
        "stopReason": "tool_use",
        "usage": {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0},
    }


def _cards(text: str) -> dict:
    title = ""
    m = re.match(r"Title: (.*)\n", text)
    if m:
        title = re.split(r" [-|–—] ", m.group(1))[0].strip()
    body = text.split("\n\n", 1)[1] if "\n\n" in text else text
    sentences = _sentences(body)
    heads = ["Meet the " + title.lower() if title else "What this page is about", "Did you know?",
             "Here's a fun one", "One more thing", "Lumo noticed"]
    cards = []
    for head, s in zip(heads, sentences[:5]):
        cards.append({"title": head, "line": s, "emoji": "", "icon": ""})
    return {"cards": cards}


def _judge(request: dict, text: str) -> dict:
    instructions = "\n".join(b.get("text", "") for b in request["messages"][0]["content"])
    body = text.split("\n\n", 1)[1] if "\n\n" in text else text
    title_line = re.match(r"Title: (.*)\n", text)
    item = re.split(r" [-|–—] ", title_line.group(1))[0].strip() if title_line else "This animal"
    if "km/h" in instructions or "mph" in instructions:
        for s in _sentences(body):
            for value, unit in _SPEED.findall(s):
                kmh = int(value) * (1.609 if unit.lower() == "mph" else 1)
                if kmh > 100:
                    return {"pass": True, "item": item, "evidence": s[:140],
                            "reason": f"Yes! The {item.lower()} goes over 100 km/h. Vroom!"}
        return {"pass": False, "item": "", "evidence": "",
                "reason": "I can't see a speed over 100 km/h on this page. Try a speedier animal!"}
    return {"pass": False, "item": "", "evidence": "",
            "reason": "The dev server can only judge Speed Safari. Deploy the real backend for the rest!"}


def _new_quest(text: str) -> dict:
    low = text.lower()
    if any(w in low for w in ("saturn", "jupiter", "planet", "moon", "space", "neptune")):
        return {"title": "Ring Spotters", "prompt": "Find two planets that have rings around them.", "goal": 2,
                "emoji": "\U0001F52D", "rubric": "Counts: a planet the page says has rings (Saturn, Jupiter, Uranus, "
                                                 "Neptune). Moons and dwarf planets do not count."}
    return {"title": "Record Breakers", "prompt": "Find two animals that hold a world record.", "goal": 2,
            "emoji": "\U0001F3C6", "rubric": "Counts: an animal the page says is the fastest, tallest, largest or "
                                             "longest-lived. The page must state the record."}


def _react(text: str) -> dict:
    m = re.search(r"Signal: (.*)", text)
    text = m.group(1) if m else text
    if "late at night" in text:
        return {"mood": "sleepy", "line": "It's late, and even moths need rest. One calm wonder before bed?",
                "suggestion_kind": "detour", "quest_id": "", "detour": "aurora", "label": "", "detail": ""}
    if "scrolling" in text:
        return {"mood": "sleepy", "line": "All this scrolling makes my antennae droop. Fancy something that glows?",
                "suggestion_kind": "detour", "quest_id": "", "detour": "bioluminescence", "label": "", "detail": ""}
    if "same site" in text and "again and again" in text:
        return {"mood": "curious", "line": "We keep circling the same light. Trust me, I'm a moth: let's try a new one.",
                "suggestion_kind": "detour", "quest_id": "", "detour": "moth", "label": "", "detail": ""}
    return {"mood": "concerned", "line": "So many pages, so fast! My wings are dizzy. Want a quest with a finish line?",
            "suggestion_kind": "quest", "quest_id": "speed-safari", "detour": "", "label": "", "detail": ""}


class FakeBedrockRuntime:
    def converse(self, **request):
        name = request["toolConfig"]["tools"][0]["toolSpec"]["name"]
        text = _page_text(request)
        if name == "make_cards":
            return _tool_use(name, _cards(text))
        if name == "judge_quest":
            return _tool_use(name, _judge(request, text))
        if name == "new_quest":
            return _tool_use(name, _new_quest(text))
        return _tool_use(name, _react(text))

    def apply_guardrail(self, **_):
        return {"action": "NONE", "outputs": []}


class FakeDynamo:
    def __init__(self):
        self.items = {}

    def get_item(self, TableName, Key, ConsistentRead=False):
        item = self.items.get(Key["pk"]["S"])
        return {"Item": dict(item)} if item else {}

    def put_item(self, TableName, Item):
        self.items[Item["pk"]["S"]] = dict(Item)
        return {}


def silent_mp3(seconds: float = 0.6) -> bytes:
    """A valid MPEG-1 Layer III stream of silence (128 kbps, 44.1 kHz, mono) so TV media players accept it."""
    header = struct.pack(">I", 0xFFFB90C4)  # sync, MPEG-1, Layer III, no CRC, 128 kbps, 44.1 kHz, mono
    frame = header + bytes(417 - 4)
    return frame * max(1, int(seconds * 44100 / 1152))


class FakePolly:
    def synthesize_speech(self, **_):
        return {"AudioStream": io.BytesIO(silent_mp3()), "ContentType": "audio/mpeg"}


def clients() -> dict:
    return {"bedrock-runtime": FakeBedrockRuntime(), "dynamodb": FakeDynamo(), "polly": FakePolly()}
