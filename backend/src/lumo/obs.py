"""Observability: one structured JSON line per request.

The line doubles as a CloudWatch Embedded Metric Format (EMF) record, so CloudWatch turns it into custom
metrics (namespace WanderTV) with no extra API calls or IAM permissions. Page text, titles and spoken text are
never logged: only sizes, timings and outcomes.
"""

import json
import sys
import time

NAMESPACE = "WanderTV"

# Two directives keep the custom-metric count (and its monthly cost) small: traffic and latency per route,
# outcomes as stack-wide totals. 2 x 6 routes + 6 = 18 metrics.
_DIRECTIVES = [
    {"Namespace": NAMESPACE, "Dimensions": [["route"]], "Metrics": [
        {"Name": "Requests", "Unit": "Count"},
        {"Name": "Latency", "Unit": "Milliseconds"},
    ]},
    {"Namespace": NAMESPACE, "Dimensions": [[]], "Metrics": [
        {"Name": "ModelLatency", "Unit": "Milliseconds"},
        {"Name": "CacheHit", "Unit": "Count"},
        {"Name": "Blocked", "Unit": "Count"},
        {"Name": "FallbackModel", "Unit": "Count"},
        {"Name": "EvidenceRejected", "Unit": "Count"},
        {"Name": "Errors", "Unit": "Count"},
    ]},
]


class RequestLog:
    """Collects facts about one request; emit() prints them once."""

    def __init__(self, request_id: str = ""):
        self.started = time.monotonic()
        self.fields = {
            "request_id": request_id,
            "route": "unknown",
            "status": 0,
            "model": None,
            "cached": False,
            "blocked": False,
            "fallback_model": False,
            "evidence_rejected": False,
            "model_ms": 0,
        }
        self._emitted = False

    def set(self, **kwargs):
        self.fields.update(kwargs)

    def add_model_ms(self, ms: int):
        self.fields["model_ms"] = int(self.fields.get("model_ms") or 0) + int(ms)

    def elapsed_ms(self) -> int:
        return int((time.monotonic() - self.started) * 1000)

    def emit(self):
        if self._emitted:
            return
        self._emitted = True
        f = self.fields
        latency = self.elapsed_ms()
        record = {
            "_aws": {
                "Timestamp": int(time.time() * 1000),
                "CloudWatchMetrics": _DIRECTIVES,
            },
            "level": "ERROR" if int(f.get("status") or 0) >= 500 else "INFO",
            **f,
            "latency_ms": latency,
            # EMF metric values (names must match _METRICS)
            "Requests": 1,
            "Latency": latency,
            "ModelLatency": int(f.get("model_ms") or 0),
            "CacheHit": 1 if f.get("cached") else 0,
            "Blocked": 1 if f.get("blocked") else 0,
            "FallbackModel": 1 if f.get("fallback_model") else 0,
            "EvidenceRejected": 1 if f.get("evidence_rejected") else 0,
            "Errors": 1 if int(f.get("status") or 0) >= 500 else 0,
        }
        try:
            sys.stdout.write(json.dumps(record, separators=(",", ":"), default=str) + "\n")
            sys.stdout.flush()
        except Exception:  # logging must never break a request
            pass


def warn(event: str, **fields):
    """A one-off structured warning (cache failure, guardrail check error...)."""
    try:
        sys.stdout.write(json.dumps({"level": "WARN", "event": event, **fields}, default=str) + "\n")
        sys.stdout.flush()
    except Exception:
        pass
