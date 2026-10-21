"""Safe, hand-written content: guardrail-blocked bodies, curated detours, and an extractive card filler."""

import re

from . import emoji, sanitize

QUEST_IDS = ("speed-safari", "moon-collectors", "deep-dive", "dino-time", "tiny-titans")

QUEST_LABELS = {
    "speed-safari": ("Start Speed Safari", "Find three animals faster than a car on the highway"),
    "moon-collectors": ("Start Moon Collectors", "Find two planets with more than 20 moons"),
    "deep-dive": ("Start Deep Dive", "Find three creatures living deeper than 1,000 meters"),
    "dino-time": ("Start Time Travelers", "Find two dinosaurs from the Cretaceous period"),
    "tiny-titans": ("Start Tiny Titans", "Find three animals that lift many times their weight"),
}

# Curated detours on Simple English Wikipedia: short, readable, wondrous.
DETOURS = {
    "bioluminescence": ("https://simple.wikipedia.org/wiki/Bioluminescence", "See creatures that glow",
                        "Animals that make their own light, like tiny lamps"),
    "moth": ("https://simple.wikipedia.org/wiki/Moth", "Meet Lumo's cousins",
             "How moths find their way through the night"),
    "octopus": ("https://simple.wikipedia.org/wiki/Octopus", "Visit the octopus",
                "Eight arms, three hearts and blue blood"),
    "aurora": ("https://simple.wikipedia.org/wiki/Aurora", "Watch the night sky glow",
               "A calm one: curtains of light over the poles"),
    "tardigrade": ("https://simple.wikipedia.org/wiki/Tardigrade", "Meet the water bear",
                   "A tiny animal tough enough to survive space"),
}

DETOUR_URLS = {url for url, _, _ in DETOURS.values()}

COMPANION_BY_REASON = {
    "RAPID_HOPPING": ("concerned", "So many pages, so fast! My wings are dizzy. Want a quest with a finish line?",
                      ("quest", "speed-safari")),
    "ENDLESS_SCROLL": ("sleepy", "All this scrolling is making my antennae droop. Fancy something that glows?",
                       ("detour", "bioluminescence")),
    "SITE_LOOP": ("curious", "We keep circling the same light. Trust me, I'm a moth: let's try a new one.",
                  ("detour", "moth")),
    "LATE_NIGHT": ("sleepy", "It's getting late, and even moths need rest. One calm wonder before bed?",
                   ("detour", "aurora")),
}

BLOCKED_CARDS = [{
    "title": "Let's skip this page",
    "line": "Lumo can't make cards from this one. Let's wander somewhere brighter!",
    "emoji": "\U0001F319",
    "icon": "moon",
}]

BLOCKED_VERDICT = {
    "pass": False,
    "item": None,
    "reason": "Lumo can't judge this page. Let's try a different one!",
    "evidence": None,
}

FALLBACK_QUEST = {
    "id": "lumo-records",
    "title": "Record Breakers",
    "prompt": "Find two animals that hold a world record, like fastest, tallest or biggest.",
    "goal": 2,
    "emoji": "\U0001F3C6",
    "rubric": ("Counts: an animal the page says is the fastest, tallest, largest, loudest or longest-lived of its "
               "kind or of all animals (for example blue whale, cheetah, giraffe). The page must state the record."),
}

NOT_MUCH_CARD = {
    "title": "Not much to read here",
    "line": "This page is mostly links or pictures. Try a longer article for cards!",
    "emoji": "\U0001F50D",
    "icon": "search",
}


def suggestion(kind: str, key: str, label: str = "", detail: str = ""):
    """A validated suggestion dict for /companion, or None."""
    if kind == "quest" and key in QUEST_LABELS:
        default_label, default_detail = QUEST_LABELS[key]
        return {
            "kind": "quest",
            "label": label or default_label,
            "detail": detail or default_detail,
            "questId": key,
        }
    if kind == "detour" and key in DETOURS:
        url, default_label, default_detail = DETOURS[key]
        return {"kind": "detour", "label": label or default_label, "detail": detail or default_detail, "url": url}
    return None


def companion_for(reason: str) -> dict:
    mood, line, (kind, key) = COMPANION_BY_REASON.get(reason, COMPANION_BY_REASON["RAPID_HOPPING"])
    return {"mood": mood, "line": line, "suggestion": suggestion(kind, key)}


# ---- extractive filler: used only when the model returns fewer than 3 usable cards ----

_SENTENCE = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"“])")
_INTERESTING = re.compile(
    r"\b(\d[\d,.]*|fastest|largest|biggest|smallest|tallest|longest|oldest|deepest|heaviest|strongest|"
    r"first|only|record|most|known for|famous)\b",
    re.IGNORECASE,
)
_BOILERPLATE = re.compile(
    r"cookie|subscribe|sign in|log in|javascript|privacy|copyright|all rights|advertis|newsletter|click", re.I
)
FILLER_TITLES = ("Did you know?", "Fun fact", "One more thing", "Lumo noticed")


def filler_cards(text: str, existing: list, needed: int) -> list:
    if needed <= 0 or not text:
        return []
    sentences = []
    for paragraph in text.split("\n"):
        for s in _SENTENCE.split(paragraph.strip()):
            s = re.sub(r"\[\d+\]", "", s).strip()
            if 40 <= len(s) <= 220 and not _BOILERPLATE.search(s) and len(s.split()) >= 6:
                sentences.append(s)
    scored = sorted(
        enumerate(sentences),
        key=lambda p: (-len(_INTERESTING.findall(p[1])), p[0]),
    )
    used_lines = [c.get("line", "") for c in existing]
    used_titles = {c.get("title", "").lower() for c in existing}
    titles = [t for t in FILLER_TITLES if t.lower() not in used_titles]
    out = []
    for _, s in scored:
        if len(out) >= needed or not titles:
            break
        line = sanitize.sentence_field(s, 110)
        if any(sanitize.similar(line, u) for u in used_lines):
            continue
        found_emoji, icon = emoji.for_text(s)
        out.append({
            "title": titles.pop(0),
            "line": line,
            "emoji": found_emoji or emoji.DEFAULT_EMOJI,
            "icon": icon or "spark",
        })
        used_lines.append(line)
    return out
