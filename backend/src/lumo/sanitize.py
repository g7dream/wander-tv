"""Server-side validation and clamping. Nothing the model writes reaches the TV without passing through here."""

import re
import unicodedata

from . import emoji

ELLIPSIS = "…"

_CONTROL = re.compile(r"[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f​  ﻿]")
_WS = re.compile(r"[ \t\r\f\v  -   　]+")
_MULTI_NL = re.compile(r"\n{3,}")
_MARKDOWN = re.compile(r"(\*\*|__|`+|^#+\s*|^\s*[-*•]\s+)", re.MULTILINE)
_TRAILING_CUT = " ,;:-–—([{\"'"
_WRAP_QUOTES = "\"'“”‘’«»"


def fix_unicode(s: str) -> str:
    """Replaces lone surrogates (e.g. from text cut in the middle of an emoji) so UTF-8 encoding never fails."""
    return s.encode("utf-8", "replace").decode("utf-8")


def as_str(value) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return str(value)
    return ""


def clean_block(value, max_len: int) -> str:
    """Untrusted page text: keeps paragraphs, drops control chars, caps the length."""
    s = fix_unicode(as_str(value))
    s = _CONTROL.sub("", s).replace("\r\n", "\n").replace("\r", "\n")
    s = "\n".join(_WS.sub(" ", line).strip() for line in s.split("\n"))
    s = _MULTI_NL.sub("\n\n", s).strip()
    if len(s) > max_len:
        s = s[:max_len]
        if s and 0xD800 <= ord(s[-1]) <= 0xDFFF:
            s = s[:-1]
    return s


def clean_line(value) -> str:
    """One display line: no newlines, no markdown, no wrapping quotes, single spaces."""
    s = fix_unicode(as_str(value))
    s = _CONTROL.sub("", s)
    s = _MARKDOWN.sub("", s)
    s = s.replace("\n", " ")
    s = _WS.sub(" ", s).strip()
    while len(s) >= 2 and s[0] in _WRAP_QUOTES and s[-1] in _WRAP_QUOTES:
        s = s[1:-1].strip()
    return s


def clamp(s: str, max_len: int, ellipsis: bool = True) -> str:
    """Cuts at a word boundary so the result (including the ellipsis) fits in max_len characters."""
    if len(s) <= max_len:
        return s
    limit = max_len - 1 if ellipsis else max_len
    cut = s[:limit + 1]
    space = cut.rfind(" ")
    if space >= max(8, limit // 2):
        cut = cut[:space]
    else:
        cut = s[:limit]
    cut = cut.rstrip(_TRAILING_CUT)
    if not cut:
        cut = s[:limit]
    return cut + ELLIPSIS if ellipsis else cut


def text_field(value, max_len: int, ellipsis: bool = True, allow_emoji: bool = False) -> str:
    s = clean_line(value)
    if not allow_emoji:
        s = _WS.sub(" ", emoji.strip_emoji(s)).strip()
    return clamp(s, max_len, ellipsis)


def title_field(value, max_len: int = 32) -> str:
    s = text_field(value, max_len + 40, ellipsis=False)
    s = s.rstrip(".!:;,")
    if len(s) > max_len:
        s = clamp(s, max_len, ellipsis=False)
    # Field-guide headings: capitalize the first letter only, never SHOUT.
    if s.isupper() and len(s) > 3:
        s = s.capitalize()
    if s and s[0].islower():
        s = s[0].upper() + s[1:]
    return s


def sentence_field(value, max_len: int) -> str:
    """A sentence for the screen: capitalized, ends with punctuation when it fits."""
    s = text_field(value, max_len)
    if not s:
        return s
    if s[0].islower():
        s = s[0].upper() + s[1:]
    if s[-1] not in ".!?" + ELLIPSIS and len(s) < max_len:
        s += "."
    return s


def one_word(value, max_len: int = 16) -> str:
    s = unicodedata.normalize("NFKD", as_str(value)).encode("ascii", "ignore").decode("ascii").lower()
    m = re.search(r"[a-z]+", s)
    return m.group(0)[:max_len] if m else ""


def one_emoji(value, *context, default: str = emoji.DEFAULT_EMOJI) -> str:
    e = emoji.first_emoji(as_str(value))
    if e:
        return e
    found, _ = emoji.for_text(*context)
    return found or default


def as_bool(value) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        return value.strip().lower() in ("true", "yes", "1", "pass", "passed")
    return False


def as_int(value, lo: int, hi: int, default: int) -> int:
    try:
        if isinstance(value, bool):
            return default
        n = int(float(value))
    except (TypeError, ValueError, OverflowError):
        return default
    return max(lo, min(hi, n))


def norm_key(s: str) -> str:
    """Normalization for comparing item names: 'Peregrine Falcons' == 'peregrine falcon'."""
    s = unicodedata.normalize("NFKD", as_str(s)).encode("ascii", "ignore").decode("ascii").lower()
    s = re.sub(r"[^a-z0-9 ]+", " ", s)
    words = [w[:-1] if len(w) > 3 and w.endswith("s") and not w.endswith("ss") else w for w in s.split()]
    return " ".join(words)


def string_list(value, max_items: int, max_len: int) -> list:
    if not isinstance(value, list):
        return []
    out = []
    for v in value:
        s = clamp(clean_line(v), max_len, ellipsis=False)
        if s:
            out.append(s)
        if len(out) >= max_items:
            break
    return out


def similar(a: str, b: str) -> bool:
    """Rough duplicate check between two card lines (word-set Jaccard)."""
    wa = set(re.findall(r"[a-z0-9]+", a.lower()))
    wb = set(re.findall(r"[a-z0-9]+", b.lower()))
    if not wa or not wb:
        return False
    return len(wa & wb) / len(wa | wb) > 0.6
