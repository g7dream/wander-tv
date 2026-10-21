"""Amazon Polly: Lumo's voice (neural engine, MP3), with a DynamoDB audio cache."""

import re

from . import aws, cache, emoji, sanitize

MAX_CHARS = 600
_SSML_ESCAPES = {"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;"}


def prepare(text) -> str:
    """Plain speakable text: no emoji, no markup, at most 600 characters cut at a word boundary."""
    s = sanitize.clean_line(text)
    s = emoji.strip_emoji(s)
    s = re.sub(r"https?://\S+", "", s)
    s = re.sub(r"\s+", " ", s).strip()
    if len(s) > MAX_CHARS:
        s = sanitize.clamp(s, MAX_CHARS, ellipsis=False)
    return s


def to_ssml(text: str) -> str:
    escaped = "".join(_SSML_ESCAPES.get(ch, ch) for ch in text)
    # A touch slower than default: Lumo talks to a room, often to kids.
    return f'<speak><prosody rate="95%">{escaped}</prosody></speak>'


def synthesize(settings, text: str, voice: str) -> tuple:
    """Returns (mp3_bytes, cached). Raises on Polly errors other than SSML trouble."""
    key = cache.speech_key(voice, text)
    hit = cache.get_audio(settings, key)
    if hit:
        return hit, True
    polly = aws.client("polly")
    try:
        response = polly.synthesize_speech(
            Engine="neural", OutputFormat="mp3", VoiceId=voice, TextType="ssml", Text=to_ssml(text)
        )
    except Exception as exc:
        if aws.error_code(exc) not in ("InvalidSsmlException", "SsmlMarksNotSupportedForTextTypeException"):
            raise
        response = polly.synthesize_speech(Engine="neural", OutputFormat="mp3", VoiceId=voice, TextType="text", Text=text)
    stream = response.get("AudioStream")
    audio = b""
    if stream is not None:
        try:
            audio = stream.read()
        finally:
            close = getattr(stream, "close", None)
            if callable(close):
                close()
    if audio:
        cache.put_audio(settings, key, audio, voice)
    return audio, False
