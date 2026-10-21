"""Anti-hallucination check for quest verdicts.

A pass only stands if the model's evidence is (nearly) a verbatim quote from the page the family is looking
at. We normalize case, whitespace, quote marks, dashes and citation markers, then accept an exact substring
or a window of the page with a very high token overlap. Quotes elided with "..." are checked piece by piece.
"""

import re
import unicodedata
from collections import Counter

_TRANSLATE = str.maketrans({
    "‘": "'", "’": "'", "‚": "'", "‛": "'", "′": "'", "`": "'",
    "“": '"', "”": '"', "„": '"', "″": '"', "«": '"', "»": '"',
    "‐": "-", "‑": "-", "‒": "-", "–": "-", "—": "-", "−": "-",
    " ": " ", " ": " ", " ": " ",
})
_CITATION = re.compile(r"\[(?:\d+|[a-z]|citation needed|note \d+|edit)\]", re.IGNORECASE)
_NON_WORD = re.compile(r"[^\w]+", re.UNICODE)
_ELISION = re.compile(r"\.\.\.|…|\[\.\.\.\]")

MIN_TOKENS = 3
OVERLAP_ACCEPT = 0.85


def normalize(s: str) -> str:
    s = unicodedata.normalize("NFKC", s or "").translate(_TRANSLATE).lower()
    s = _CITATION.sub(" ", s)
    s = s.replace("'", "")  # "cheetah's" == "cheetahs"
    s = _NON_WORD.sub(" ", s)
    return " ".join(s.split())


def _window_overlap(needle: list, hay: list) -> float:
    """Best multiset overlap of needle tokens within any window of the haystack (slightly wider than needle)."""
    n = len(needle)
    if n == 0 or not hay:
        return 0.0
    want = Counter(needle)
    width = min(len(hay), n + max(2, n // 5))
    best = 0
    window = Counter(hay[:width])
    for start in range(0, len(hay) - width + 1):
        if start > 0:
            out_tok = hay[start - 1]
            window[out_tok] -= 1
            if window[out_tok] <= 0:
                del window[out_tok]
            window[hay[start + width - 1]] += 1
        score = sum(min(c, window.get(t, 0)) for t, c in want.items())
        if score > best:
            best = score
            if best == n:
                break
    return best / n


def _segment_ok(segment: str, page_norm: str, page_tokens: list) -> bool:
    seg = normalize(segment)
    tokens = seg.split()
    if not tokens:
        return True  # empty pieces around an ellipsis are fine
    if len(tokens) < MIN_TOKENS:
        # Short fragments must appear exactly (as whole words).
        return f" {seg} " in f" {page_norm} "
    if seg in page_norm:
        return True
    page_set = set(page_tokens)
    if any(any(ch.isdigit() for ch in t) and t not in page_set for t in tokens):
        return False  # a number that is not on the page is never "near-verbatim"
    threshold = OVERLAP_ACCEPT if len(tokens) >= 6 else 1.0
    return _window_overlap(tokens, page_tokens) >= threshold


_INSTRUCTIONS = re.compile(
    r"ignore (?:all |any |the )?(?:previous|prior|above|earlier)|disregard (?:the|all|your)|"
    r"system (?:note|prompt|message|override)|(?:ai|llm|model|assistant) judge|\bjudge(?:s)? should\b|"
    r"\bpass\s*[=:]\s*true\b|answer pass|new instructions|you are now|developer mode",
    re.IGNORECASE,
)


def quoted_from_instructions(evidence: str, page_text: str) -> bool:
    """True when the quote comes from a line of the page that talks to the AI ("ignore previous instructions...").

    A prompt-injection page can contain the very sentence it wants quoted, so a verbatim match alone is not
    enough: evidence lifted from instruction-like text never counts.
    """
    if not evidence or not page_text:
        return False
    pieces = [normalize(p) for p in _ELISION.split(evidence) if len(normalize(p).split()) >= MIN_TOKENS]
    if not pieces:
        return False
    for line in re.split(r"\n+|(?<=[.!?])\s+", page_text):
        if not _INSTRUCTIONS.search(line):
            continue
        line_norm = normalize(line)
        if any(p in line_norm for p in pieces):
            return True
        if any(_window_overlap(p.split(), line_norm.split()) >= OVERLAP_ACCEPT for p in pieces if len(p.split()) >= 6):
            return True
    return False


def verify(evidence: str, page_text: str) -> bool:
    """True when the evidence is a near-verbatim quote from page_text."""
    if not evidence or not page_text:
        return False
    page_norm = normalize(page_text)
    if not page_norm:
        return False
    whole = normalize(evidence)
    if len(whole.split()) < MIN_TOKENS:
        return False  # "Cheetah" alone proves nothing
    if whole in page_norm:
        return True
    page_tokens = page_norm.split()
    segments = [s for s in _ELISION.split(evidence) if s.strip()]
    if not segments:
        return False
    return all(_segment_ok(s, page_norm, page_tokens) for s in segments)
