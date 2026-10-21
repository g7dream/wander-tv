"""The six endpoints. Each returns (status, body) and never trusts what the model wrote without clamping it."""

import base64
import hashlib

from . import VERSION, aws, bedrock, cache, evidence, fallbacks, prompts, sanitize, schemas, speech
from .emoji import DEFAULT_EMOJI, ICON_EMOJI, for_text
from .settings import NEURAL_VOICES

MAX_TEXT = 6000
MIN_CARD_TEXT = 120
TITLE_MAX = 32
LINE_MAX = 110
REASON_MAX = 120
EVIDENCE_MAX = 140

DOWNGRADE_REASON = "I couldn't find proof of that on this page. Try a page that says it plainly!"
NO_ITEM_REASON = "Hmm, I'm not sure which one counts here. Try a page about just one!"
DEFAULT_FAIL_REASON = "Not quite yet. Try a page that shows the quest fact clearly!"
TOO_SHORT_REASON = "This page is too short for me to check. Try a full article!"
MOODS = {"curious", "happy", "sleepy", "concerned"}


class BadRequest(Exception):
    def __init__(self, field: str):
        super().__init__(field)
        self.field = field


def _fill(template: str, **values) -> str:
    return template.format(data=bedrock.DATA_MARK, **values)


# ---------------------------------------------------------------- /health

def health(body, settings, log):
    return 200, {
        "ok": True,
        "model": settings.model_id,
        "fallbackModel": settings.fallback_model_id or None,
        "region": settings.region,
        "guardrail": settings.guardrail_on,
        "cache": bool(settings.table_name),
        "voice": settings.voice_id,
        "version": VERSION,
    }


# ---------------------------------------------------------------- /cards

def clamp_cards(raw, page_title: str = "") -> list:
    """3-5 clean cards from whatever the model (or the cache) produced; drops broken and duplicate cards."""
    if not isinstance(raw, list):
        return []
    out = []
    titles = set()
    for c in raw:
        if not isinstance(c, dict):
            continue
        title = sanitize.title_field(c.get("title"), TITLE_MAX)
        line = sanitize.sentence_field(c.get("line"), LINE_MAX)
        if not title or len(line) < 12:
            continue
        if title.lower() in titles or any(sanitize.similar(line, o["line"]) for o in out):
            continue
        icon = sanitize.one_word(c.get("icon"))
        found_emoji, found_icon = for_text(line, title)
        chosen = sanitize.one_emoji(c.get("emoji"), default="")
        if not chosen:
            chosen = ICON_EMOJI.get(icon) or found_emoji or for_text(page_title)[0] or DEFAULT_EMOJI
        out.append({"title": title, "line": line, "emoji": chosen, "icon": icon or found_icon or "spark"})
        titles.add(title.lower())
        if len(out) == 5:
            break
    return out


def cards(body, settings, log):
    raw_text = body.get("text")
    if not isinstance(raw_text, str):
        raise BadRequest("text")
    url = sanitize.clamp(sanitize.clean_line(body.get("url")), 2048, ellipsis=False)
    title = sanitize.text_field(body.get("title"), 200, ellipsis=False, allow_emoji=True)
    text = sanitize.clean_block(raw_text, MAX_TEXT)
    log.set(text_chars=len(text))
    if len(text) < MIN_CARD_TEXT:
        return 200, {"cards": [dict(fallbacks.NOT_MUCH_CARD)], "cached": False}

    key = cache.card_key(url, text)
    hit = clamp_cards(cache.get_cards(settings, key), title)
    if hit:
        log.set(cached=True)
        return 200, {"cards": hit, "cached": True}

    result = bedrock.run_tool(settings, schemas.MAKE_CARDS, _fill(prompts.CARDS), prompts.page_block(title, url, text), log)
    log.set(model=result.model, fallback_model=result.fallback_used)
    if result.blocked:
        log.set(blocked=True)
        return 200, {"blocked": True, "cards": [dict(c) for c in fallbacks.BLOCKED_CARDS], "cached": False}

    deck = clamp_cards(result.data.get("cards"), title)
    if len(deck) < 3:
        deck += fallbacks.filler_cards(text, deck, 3 - len(deck))
    if not deck:
        deck = [dict(fallbacks.NOT_MUCH_CARD)]
    if bedrock.output_blocked(settings, [f"{c['title']}. {c['line']}" for c in deck], log):
        log.set(blocked=True)
        return 200, {"blocked": True, "cards": [dict(c) for c in fallbacks.BLOCKED_CARDS], "cached": False}
    cache.put_cards(settings, key, deck, result.model)
    return 200, {"cards": deck, "cached": False}


# ---------------------------------------------------------------- /quest/check

def _strip_quote_marks(s: str) -> str:
    return s.strip().strip("\"'“”‘’").strip()


def quest_check(body, settings, log):
    quest = body.get("quest")
    page = body.get("page")
    if not isinstance(quest, dict):
        raise BadRequest("quest")
    if not isinstance(page, dict):
        raise BadRequest("page")
    q_title = sanitize.text_field(quest.get("title"), 60, ellipsis=False)
    q_prompt = sanitize.text_field(quest.get("prompt"), 240)
    q_rubric = sanitize.text_field(quest.get("rubric"), 800)
    if not q_prompt and not q_title:
        raise BadRequest("quest.prompt")
    goal = sanitize.as_int(quest.get("goal"), 1, 10, 3)
    p_text = sanitize.clean_block(page.get("text"), MAX_TEXT)
    p_title = sanitize.text_field(page.get("title"), 200, ellipsis=False, allow_emoji=True)
    p_url = sanitize.clamp(sanitize.clean_line(page.get("url")), 2048, ellipsis=False)
    found = sanitize.string_list(body.get("found"), 20, 60)
    log.set(text_chars=len(p_text), quest=sanitize.clamp(sanitize.clean_line(quest.get("id")), 40, ellipsis=False))

    if len(p_text) + len(p_title) < 20:
        return 200, {"pass": False, "item": None, "reason": TOO_SHORT_REASON, "evidence": None}

    prompt = _fill(
        prompts.QUEST_CHECK,
        title=q_title or "Family quest",
        prompt=f"{q_prompt} (goal: {goal} different items)",
        rubric=q_rubric or q_prompt,
        found=", ".join(found) if found else "nothing yet",
    )
    result = bedrock.run_tool(settings, schemas.JUDGE_QUEST, prompt, prompts.page_block(p_title, p_url, p_text), log)
    log.set(model=result.model, fallback_model=result.fallback_used)
    if result.blocked:
        log.set(blocked=True)
        return 200, {"blocked": True, **fallbacks.BLOCKED_VERDICT}

    d = result.data
    passed = sanitize.as_bool(d.get("pass"))
    item = sanitize.title_field(d.get("item"), 40)
    reason = sanitize.sentence_field(d.get("reason"), REASON_MAX)
    quote = _strip_quote_marks(sanitize.clean_line(d.get("evidence")))
    evidence_out = None
    if passed:
        if not item:
            passed, reason = False, NO_ITEM_REASON
        elif (not evidence.verify(quote, f"{p_title}\n{p_text}")
              or evidence.quoted_from_instructions(quote, p_text)):
            # Anti-hallucination: a pass needs a real quote from this page, and not from text that talks to the AI.
            passed, reason = False, DOWNGRADE_REASON
            log.set(evidence_rejected=True)
        else:
            evidence_out = sanitize.clamp(quote, EVIDENCE_MAX)
            if not reason:
                reason = sanitize.sentence_field(f"Yes! {item} counts!", REASON_MAX)
    if not passed:
        item = None
        reason = reason or DEFAULT_FAIL_REASON
    if bedrock.output_blocked(settings, [reason, item or "", evidence_out or ""], log):
        log.set(blocked=True)
        return 200, {"blocked": True, **fallbacks.BLOCKED_VERDICT}
    return 200, {"pass": passed, "item": item, "reason": reason, "evidence": evidence_out}


# ---------------------------------------------------------------- /quest/new

def quest_id(title: str, prompt: str) -> str:
    return "lumo-" + hashlib.sha256(f"{title}\n{prompt}".encode("utf-8")).hexdigest()[:8]


def quest_new(body, settings, log):
    recent = [sanitize.text_field(t, 120, ellipsis=False) for t in sanitize.string_list(body.get("recent"), 12, 160)]
    recent = [t for t in recent if t]
    log.set(titles=len(recent))
    result = bedrock.run_tool(settings, schemas.NEW_QUEST, _fill(prompts.QUEST_NEW), prompts.titles_block(recent), log)
    log.set(model=result.model, fallback_model=result.fallback_used)
    if result.blocked:
        log.set(blocked=True)
        return 200, {"blocked": True, "quest": dict(fallbacks.FALLBACK_QUEST)}

    d = result.data
    title = sanitize.title_field(d.get("title"), TITLE_MAX)
    prompt = sanitize.sentence_field(d.get("prompt"), 140)
    rubric = sanitize.text_field(d.get("rubric"), 400)
    if not title or len(prompt) < 12:
        return 200, {"quest": dict(fallbacks.FALLBACK_QUEST)}
    quest = {
        "id": quest_id(title, prompt),
        "title": title,
        "prompt": prompt,
        "goal": sanitize.as_int(d.get("goal"), 2, 3, 2),
        "emoji": sanitize.one_emoji(d.get("emoji"), title, prompt),
        "rubric": rubric or prompt,
    }
    if bedrock.output_blocked(settings, [title, prompt, rubric], log):
        log.set(blocked=True)
        return 200, {"blocked": True, "quest": dict(fallbacks.FALLBACK_QUEST)}
    return 200, {"quest": quest}


# ---------------------------------------------------------------- /companion

_STAT_FIELDS = ("sessionMinutes", "pagesLast5Min", "avgDwellSec", "scrollSecondsNoClick", "sameSiteRepeats",
                "localHour", "pagesTotal")
_REASON_WORDS = {
    "RAPID_HOPPING": "rapid hopping between pages",
    "ENDLESS_SCROLL": "endless scrolling without clicking anything",
    "SITE_LOOP": "going back to the same site again and again",
    "LATE_NIGHT": "browsing late at night",
}


def companion(body, settings, log):
    signal = body.get("signal")
    if not isinstance(signal, dict):
        raise BadRequest("signal")
    reason = sanitize.clean_line(signal.get("reason")).upper()
    if reason not in fallbacks.COMPANION_BY_REASON:
        raise BadRequest("signal.reason")
    stats_in = body.get("stats") if isinstance(body.get("stats"), dict) else {}
    s = {k: sanitize.as_int(stats_in.get(k), 0, 100000, 0) for k in _STAT_FIELDS}
    s["localHour"] = min(23, s["localHour"])
    detail = sanitize.text_field(signal.get("detail"), 120) or "no details"
    session = (f"{s['sessionMinutes']} min, {s['pagesTotal']} pages in total, {s['pagesLast5Min']} pages in the last "
               f"5 min, about {s['avgDwellSec']} s per page, {s['scrollSecondsNoClick']} s of scrolling without a "
               f"click, same site opened {s['sameSiteRepeats']} times, local time {s['localHour']}:00")
    log.set(signal=reason)
    prompt = _fill(prompts.COMPANION, quests=prompts.menu(prompts.QUEST_MENU), detours=prompts.menu(prompts.DETOUR_MENU))
    result = bedrock.run_tool(settings, schemas.LUMO_REACT, prompt,
                              prompts.signal_block(_REASON_WORDS[reason], detail, session), log)
    log.set(model=result.model, fallback_model=result.fallback_used)
    safe = fallbacks.companion_for(reason)
    if result.blocked:
        log.set(blocked=True)
        return 200, {"blocked": True, **safe}

    d = result.data
    mood = sanitize.clean_line(d.get("mood")).lower()
    line = sanitize.sentence_field(d.get("line"), 120)
    kind = sanitize.clean_line(d.get("suggestion_kind")).lower()
    key = sanitize.clean_line(d.get("quest_id") if kind == "quest" else d.get("detour")).lower()
    suggestion = fallbacks.suggestion(
        kind, key,
        label=sanitize.text_field(d.get("label"), 40, ellipsis=False),
        detail=sanitize.text_field(d.get("detail"), 80),
    )
    # Lumo always offers something better; an invalid or missing pick becomes the curated one.
    answer = {
        "mood": mood if mood in MOODS else safe["mood"],
        "line": line or safe["line"],
        "suggestion": suggestion or safe["suggestion"],
    }
    shown = [answer["line"], answer["suggestion"]["label"], answer["suggestion"]["detail"]]
    if bedrock.output_blocked(settings, shown, log):
        log.set(blocked=True)
        return 200, {"blocked": True, **safe}
    return 200, answer


# ---------------------------------------------------------------- /speak

class SpeechFailure(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def speak(body, settings, log):
    text = speech.prepare(body.get("text"))
    if not text:
        raise BadRequest("text")
    requested = sanitize.clean_line(body.get("voice"))
    voice = requested if requested in NEURAL_VOICES else settings.voice_id
    log.set(text_chars=len(text), voice=voice)
    try:
        audio, cached = speech.synthesize(settings, text, voice)
    except Exception as exc:
        raise SpeechFailure(aws.error_code(exc)) from exc
    if not audio:
        raise SpeechFailure("EmptyAudio")
    log.set(cached=cached, model="polly-neural")
    return 200, {"audio": base64.b64encode(audio).decode("ascii"), "voice": voice, "cached": cached}
