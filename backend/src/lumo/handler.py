"""API Gateway HTTP API (payload format 2.0) -> route -> JSON response.

Auth: every request (including /health) must carry x-wander-key, compared in constant time with API_KEY.
Errors are always JSON: 400 bad input, 401 wrong key, 404/405 unknown route, 413 too large,
502 model or speech unavailable, 503 throttled/busy (the TV then uses its on-device fallback).
"""

import base64
import binascii
import hmac
import json

from . import aws, bedrock, obs, routes
from .settings import Settings

MAX_BODY_BYTES = 64 * 1024

ROUTES = {
    ("GET", "/health"): routes.health,
    ("POST", "/cards"): routes.cards,
    ("POST", "/quest/check"): routes.quest_check,
    ("POST", "/quest/new"): routes.quest_new,
    ("POST", "/companion"): routes.companion,
    ("POST", "/speak"): routes.speak,
}
_PATHS = {path for _, path in ROUTES}

BUSY = {"error": "busy", "message": "Lumo is a little busy. Try again in a moment."}


def response(status: int, body: dict) -> dict:
    return {
        "statusCode": status,
        "headers": {
            "content-type": "application/json; charset=utf-8",
            "cache-control": "no-store",
            "x-content-type-options": "nosniff",
        },
        "body": json.dumps(body, ensure_ascii=False, separators=(",", ":")),
    }


def route_of(event: dict) -> tuple:
    route_key = event.get("routeKey")
    if isinstance(route_key, str) and " " in route_key and "{" not in route_key:
        method, path = route_key.split(" ", 1)
        return method.upper(), "/" + path.strip("/")
    context = event.get("requestContext") or {}
    http = context.get("http") or {}
    method = str(http.get("method") or event.get("httpMethod") or "GET").upper()
    path = str(event.get("rawPath") or http.get("path") or event.get("path") or "/")
    stage = context.get("stage")
    if isinstance(stage, str) and stage and stage != "$default" and path.startswith(f"/{stage}/"):
        path = path[len(stage) + 1:]
    return method, "/" + path.strip("/")


def authorized(provided, expected: str) -> bool:
    if not expected or not isinstance(provided, str):
        return False
    return hmac.compare_digest(provided.strip().encode("utf-8"), expected.encode("utf-8"))


def _body(event: dict):
    raw = event.get("body")
    if raw is None or raw == "":
        return None, (400, {"error": "bad_json", "message": "Send a JSON object."})
    if not isinstance(raw, str):
        return None, (400, {"error": "bad_json"})
    if event.get("isBase64Encoded"):
        try:
            data = base64.b64decode(raw, validate=False)
        except (binascii.Error, ValueError):
            return None, (400, {"error": "bad_body"})
    else:
        data = raw.encode("utf-8", "replace")
    if len(data) > MAX_BODY_BYTES:
        return None, (413, {"error": "too_large", "limit": MAX_BODY_BYTES})
    try:
        body = json.loads(data.decode("utf-8", "replace"))
    except ValueError:
        return None, (400, {"error": "bad_json"})
    if not isinstance(body, dict):
        return None, (400, {"error": "bad_json", "message": "Send a JSON object."})
    return body, None


def dispatch(event, log: obs.RequestLog) -> tuple:
    if not isinstance(event, dict):
        return 400, {"error": "bad_event"}
    settings = Settings.load()
    method, path = route_of(event)
    log.set(route=path, method=method)
    headers = {str(k).lower(): v for k, v in (event.get("headers") or {}).items()}
    if not authorized(headers.get("x-wander-key"), settings.api_key):
        return 401, {"error": "unauthorized"}
    fn = ROUTES.get((method, path))
    if fn is None:
        if path in _PATHS:
            return 405, {"error": "method_not_allowed"}
        return 404, {"error": "not_found"}
    body = {}
    if method == "POST":
        body, problem = _body(event)
        if problem:
            return problem
    try:
        return fn(body, settings, log)
    except routes.BadRequest as exc:
        return 400, {"error": "missing_field", "field": exc.field}
    except bedrock.BedrockFailure as exc:
        log.set(error=exc.code)
        if exc.throttled:
            return 503, BUSY
        return 502, {"error": "model_unavailable", "message": "Lumo's cloud brain is napping. The TV will use its own."}
    except routes.SpeechFailure as exc:
        log.set(error=exc.code)
        if exc.code in aws.THROTTLED:
            return 503, BUSY
        return 502, {"error": "speech_unavailable"}


def handler(event, context):
    log = obs.RequestLog(str(getattr(context, "aws_request_id", "") or ""))
    try:
        status, body = dispatch(event, log)
    except Exception as exc:  # last line of defence: always answer JSON
        obs.warn("unhandled_error", error=type(exc).__name__, code=aws.error_code(exc))
        status, body = 500, {"error": "internal", "message": "Lumo tripped over a lamp cord. Please try again."}
    log.set(status=status)
    log.emit()
    return response(status, body)
