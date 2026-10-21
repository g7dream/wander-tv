"""DynamoDB cache (one table, TTL attribute "ttl").

  cards#<sha256(url + sha256(text))>  -> the validated card list (JSON), so a page the family already turned into
                                         cards comes back instantly and costs no Bedrock tokens.
  speak#<sha256(voice + text)>        -> Polly MP3 bytes, so re-reading a card costs no Polly characters.

The cache is an optimization: every read/write failure is logged and swallowed, never surfaced to the TV.
"""

import hashlib
import json
import time

from . import aws, obs

DAY = 86400
MAX_AUDIO_BYTES = 350_000  # DynamoDB items are capped at 400 KB


def sha256(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8", "replace")).hexdigest()


def card_key(url: str, text: str) -> str:
    return "cards#" + sha256(url + sha256(text))


def speech_key(voice: str, text: str) -> str:
    return "speak#" + sha256(voice + "\n" + text)


def _get(settings, key: str):
    if not settings.table_name:
        return None
    try:
        response = aws.client("dynamodb").get_item(
            TableName=settings.table_name, Key={"pk": {"S": key}}, ConsistentRead=False
        )
    except Exception as exc:
        obs.warn("cache_get_failed", code=aws.error_code(exc))
        return None
    item = (response or {}).get("Item")
    if not item:
        return None
    try:
        if int(item.get("ttl", {}).get("N", "0")) < int(time.time()):
            return None  # TTL deletion can lag by hours; treat as a miss
    except (TypeError, ValueError):
        return None
    return item


def _put(settings, item: dict):
    if not settings.table_name:
        return
    item["ttl"] = {"N": str(int(time.time()) + settings.cache_days * DAY)}
    item["created"] = {"N": str(int(time.time()))}
    try:
        aws.client("dynamodb").put_item(TableName=settings.table_name, Item=item)
    except Exception as exc:
        obs.warn("cache_put_failed", code=aws.error_code(exc))


def get_cards(settings, key: str):
    item = _get(settings, key)
    if not item:
        return None
    try:
        cards = json.loads(item["cards"]["S"])
    except (KeyError, TypeError, ValueError):
        return None
    if not isinstance(cards, list) or not cards or not all(isinstance(c, dict) for c in cards):
        return None
    return cards


def put_cards(settings, key: str, cards: list, model: str):
    _put(settings, {
        "pk": {"S": key},
        "cards": {"S": json.dumps(cards, ensure_ascii=False)},
        "model": {"S": model or "unknown"},
    })


def get_audio(settings, key: str):
    item = _get(settings, key)
    if not item:
        return None
    value = (item.get("audio") or {}).get("B")
    if isinstance(value, (bytes, bytearray)) and value:
        return bytes(value)
    return None


def put_audio(settings, key: str, audio: bytes, voice: str):
    if not audio or len(audio) > MAX_AUDIO_BYTES:
        return
    _put(settings, {"pk": {"S": key}, "audio": {"B": audio}, "voice": {"S": voice}})
