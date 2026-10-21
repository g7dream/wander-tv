"""Emoji helpers: find the first emoji in a string, strip emoji from text, and map keywords to emoji.

We only use emoji that existed by Emoji 5.0 (2017) in our own maps, because older Fire OS builds ship
older color-emoji fonts.
"""

import re

_ZWJ = 0x200D
_VS16 = 0xFE0F
_KEYCAP = 0x20E3


def _is_emoji_cp(cp: int) -> bool:
    return (
        0x1F000 <= cp <= 0x1FAFF
        or 0x2600 <= cp <= 0x27BF
        or 0x2300 <= cp <= 0x23FF
        or cp in (0x2B50, 0x2B55, 0x2B1B, 0x2B1C, 0x2B05, 0x2B06, 0x2B07, 0x2934, 0x2935, 0x3030, 0x303D)
        or cp in (0x203C, 0x2049, 0x2139, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25C0, 0x25FB, 0x25FC, 0x25FD, 0x25FE)
    )


def _is_modifier(cp: int) -> bool:
    return cp in (_VS16, _KEYCAP) or 0x1F3FB <= cp <= 0x1F3FF or 0xE0020 <= cp <= 0xE007F


def first_emoji(text) -> str:
    """Returns the first complete emoji (including ZWJ sequences, skin tones and flags) or ''."""
    if not isinstance(text, str) or not text:
        return ""
    cps = [ord(c) for c in text]
    n = len(cps)
    i = 0
    while i < n:
        cp = cps[i]
        if 0x1F1E6 <= cp <= 0x1F1FF:  # regional indicator: flags come in pairs
            if i + 1 < n and 0x1F1E6 <= cps[i + 1] <= 0x1F1FF:
                return chr(cp) + chr(cps[i + 1])
            i += 1
            continue
        if _is_emoji_cp(cp):
            out = [cp]
            j = i + 1
            while j < n and len(out) < 12:
                if _is_modifier(cps[j]):
                    out.append(cps[j])
                    j += 1
                elif cps[j] == _ZWJ and j + 1 < n and _is_emoji_cp(cps[j + 1]):
                    out.extend((cps[j], cps[j + 1]))
                    j += 2
                else:
                    break
            return "".join(chr(c) for c in out)
        i += 1
    return ""


def strip_emoji(text) -> str:
    """Removes emoji (and their joiners/modifiers) so titles and lines stay plain text."""
    if not isinstance(text, str):
        return ""
    out = []
    for ch in text:
        cp = ord(ch)
        if _is_emoji_cp(cp) or _is_modifier(cp) or cp == _ZWJ or 0x1F1E6 <= cp <= 0x1F1FF:
            continue
        out.append(ch)
    return "".join(out)


# keyword -> (emoji, icon). Ordered from specific to generic; the first match wins.
KEYWORDS = [
    ("cheetah", "\U0001F406", "speed"), ("leopard", "\U0001F406", "animal"), ("jaguar", "\U0001F406", "animal"),
    ("lion", "\U0001F981", "animal"), ("tiger", "\U0001F405", "animal"), ("wolf", "\U0001F43A", "animal"),
    ("fox", "\U0001F98A", "animal"), ("panda", "\U0001F43C", "animal"), ("koala", "\U0001F428", "animal"),
    ("bear", "\U0001F43B", "animal"), ("gorilla", "\U0001F98D", "animal"), ("monkey", "\U0001F412", "animal"),
    ("elephant", "\U0001F418", "animal"), ("giraffe", "\U0001F992", "animal"), ("zebra", "\U0001F993", "animal"),
    ("horse", "\U0001F40E", "animal"), ("rhino", "\U0001F98F", "animal"), ("rabbit", "\U0001F407", "animal"),
    ("bat", "\U0001F987", "animal"), ("deer", "\U0001F98C", "animal"), ("camel", "\U0001F42B", "animal"),
    ("frog", "\U0001F438", "animal"), ("turtle", "\U0001F422", "animal"), ("tortoise", "\U0001F422", "animal"),
    ("snake", "\U0001F40D", "animal"), ("lizard", "\U0001F98E", "animal"), ("crocodile", "\U0001F40A", "animal"),
    ("falcon", "\U0001F985", "bird"), ("eagle", "\U0001F985", "bird"), ("hawk", "\U0001F985", "bird"),
    ("owl", "\U0001F989", "bird"), ("penguin", "\U0001F427", "bird"), ("duck", "\U0001F986", "bird"),
    ("bird", "\U0001F426", "bird"),
    ("whale", "\U0001F40B", "ocean"), ("dolphin", "\U0001F42C", "ocean"), ("shark", "\U0001F988", "ocean"),
    ("octopus", "\U0001F419", "ocean"), ("squid", "\U0001F991", "ocean"), ("crab", "\U0001F980", "ocean"),
    ("shrimp", "\U0001F990", "ocean"), ("coral", "\U0001F420", "ocean"), ("fish", "\U0001F41F", "ocean"),
    ("ant", "\U0001F41C", "bug"), ("bee", "\U0001F41D", "bug"), ("butterfly", "\U0001F98B", "bug"),
    ("moth", "\U0001F98B", "bug"), ("beetle", "\U0001F41E", "bug"), ("spider", "\U0001F577", "bug"),
    ("snail", "\U0001F40C", "bug"), ("insect", "\U0001F41B", "bug"),
    ("tyrannosaurus", "\U0001F996", "dinosaur"), ("dinosaur", "\U0001F995", "dinosaur"),
    ("fossil", "\U0001F995", "dinosaur"), ("cretaceous", "\U0001F995", "dinosaur"),
    ("astronaut", "\U0001F680", "space"), ("rocket", "\U0001F680", "space"), ("planet", "\U0001F30D", "planet"),
    ("moon", "\U0001F315", "moon"), ("sun", "☀️", "sun"), ("star", "⭐", "star"),
    ("galaxy", "\U0001F30C", "space"), ("comet", "☄️", "space"), ("telescope", "\U0001F52D", "space"),
    ("space", "\U0001F680", "space"),
    ("volcano", "\U0001F30B", "nature"), ("tornado", "\U0001F32A", "weather"), ("hurricane", "\U0001F300", "weather"),
    ("storm", "⛈", "weather"), ("lightning", "⚡", "weather"), ("snow", "❄️", "weather"),
    ("rain", "\U0001F327", "weather"), ("rainbow", "\U0001F308", "weather"), ("cloud", "☁️", "weather"),
    ("desert", "\U0001F3DC", "nature"), ("mountain", "⛰", "nature"), ("forest", "\U0001F332", "nature"),
    ("tree", "\U0001F333", "plant"), ("flower", "\U0001F338", "plant"), ("plant", "\U0001F331", "plant"),
    ("mushroom", "\U0001F344", "plant"),
    ("brain", "\U0001F9E0", "body"), ("heart", "❤️", "body"), ("muscle", "\U0001F4AA", "body"),
    ("eye", "\U0001F441", "body"), ("skeleton", "\U0001F480", "body"),
    ("chocolate", "\U0001F36B", "food"), ("pizza", "\U0001F355", "food"), ("bread", "\U0001F35E", "food"),
    ("cheese", "\U0001F9C0", "food"), ("honey", "\U0001F36F", "food"), ("apple", "\U0001F34E", "food"),
    ("fruit", "\U0001F34E", "food"), ("food", "\U0001F37D", "food"),
    ("guitar", "\U0001F3B8", "music"), ("piano", "\U0001F3B9", "music"), ("violin", "\U0001F3BB", "music"),
    ("drum", "\U0001F941", "music"), ("music", "\U0001F3B5", "music"), ("song", "\U0001F3B5", "music"),
    ("soccer", "⚽", "sport"), ("football", "⚽", "sport"), ("basketball", "\U0001F3C0", "sport"),
    ("tennis", "\U0001F3BE", "sport"), ("olympic", "\U0001F3C5", "sport"), ("sport", "\U0001F3C5", "sport"),
    ("pyramid", "\U0001F3DB", "history"), ("castle", "\U0001F3F0", "history"), ("king", "\U0001F451", "history"),
    ("queen", "\U0001F451", "history"), ("ancient", "\U0001F3DB", "history"), ("history", "\U0001F4DC", "history"),
    ("robot", "\U0001F916", "science"), ("computer", "\U0001F4BB", "science"), ("invention", "\U0001F4A1", "science"),
    ("science", "\U0001F52C", "science"), ("electricity", "⚡", "science"),
    ("train", "\U0001F686", "travel"), ("airplane", "✈️", "travel"), ("ship", "\U0001F6A2", "travel"),
    ("car", "\U0001F697", "travel"),
    ("ocean", "\U0001F30A", "ocean"), ("sea", "\U0001F30A", "ocean"), ("water", "\U0001F4A7", "water"),
    ("fire", "\U0001F525", "fire"), ("glow", "✨", "light"), ("light", "\U0001F4A1", "light"),
    ("speed", "⚡", "speed"), ("fast", "⚡", "speed"), ("record", "\U0001F3C6", "record"),
    ("map", "\U0001F5FA", "map"), ("book", "\U0001F4DA", "book"), ("art", "\U0001F3A8", "art"),
]

# icon keyword -> emoji, for when the model gives an icon but no usable emoji
ICON_EMOJI = {
    "speed": "⚡", "animal": "\U0001F43E", "bird": "\U0001F426", "ocean": "\U0001F30A", "bug": "\U0001F41B",
    "dinosaur": "\U0001F995", "space": "\U0001F680", "planet": "\U0001F30D", "moon": "\U0001F315",
    "sun": "☀️", "star": "⭐", "weather": "⛅", "nature": "\U0001F33F", "plant": "\U0001F331",
    "body": "\U0001F4AA", "food": "\U0001F37D", "music": "\U0001F3B5", "sport": "\U0001F3C5",
    "history": "\U0001F4DC", "science": "\U0001F52C", "travel": "\U0001F30D", "water": "\U0001F4A7",
    "fire": "\U0001F525", "light": "\U0001F4A1", "record": "\U0001F3C6", "map": "\U0001F5FA", "book": "\U0001F4DA",
    "art": "\U0001F3A8", "home": "\U0001F3E1", "size": "\U0001F4CF", "time": "⏰", "spark": "✨",
}

DEFAULT_EMOJI = "✨"

_PATTERNS = [(re.compile(r"\b" + re.escape(k) + r"(?:s|es)?\b"), e, i) for k, e, i in KEYWORDS]


def for_text(*texts) -> tuple:
    """(emoji, icon) for the first keyword found in the given texts, checked in order; ('', '') if none."""
    for t in texts:
        if not t:
            continue
        low = t.lower()
        for pattern, emoji, icon in _PATTERNS:
            if pattern.search(low):
                return emoji, icon
    return "", ""
