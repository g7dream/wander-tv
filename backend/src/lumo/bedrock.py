"""Amazon Bedrock: Converse with forced tool use + Guardrails, with graceful degradation.

Pipeline for one structured answer:
  1. Converse(modelId, system=Lumo persona, toolChoice={"tool": <the one tool>}, guardrailConfig) where the
     untrusted text travels in a guardContent block. The guardrail screens it (content filters + prompt attack).
  2. stopReason "guardrail_intervened" -> blocked; otherwise read toolUse.input (the JSON-schema answer).
  3. If the model rejects forced tool choice (some models only allow "auto"), retry with "any", then "auto".
  4. If the model is throttled, unavailable, not enabled or returns junk, retry once on FALLBACK_MODEL_ID.
  5. Callers sanitize the answer, then output_blocked() runs ApplyGuardrail on the final user-visible strings,
     because Converse guardrails do not evaluate toolUse arguments.
"""

import json
import re
import time
from dataclasses import dataclass, field

from . import aws, obs
from .prompts import SYSTEM
from .schemas import Tool

DATA_MARK = "\u0000DATA\u0000"
FALLBACK_BUDGET_MS = 4000
BLOCK_STOPS = {"guardrail_intervened", "content_filtered", "refusal"}
_TOOL_CHOICE_HINT = re.compile(r"tool.?choice|tool_choice|forced tool|toolChoice", re.IGNORECASE)


class ModelOutputError(Exception):
    """The model answered, but not with a usable tool call."""


class BedrockFailure(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message

    @property
    def throttled(self) -> bool:
        return self.code in aws.THROTTLED


@dataclass
class ToolResult:
    data: dict
    model: str
    blocked: bool = False
    fallback_used: bool = False
    stop_reason: str = ""
    usage: dict = field(default_factory=dict)


def is_anthropic(model_id: str) -> bool:
    return "anthropic." in model_id


def inference_config(model_id: str, tool: Tool) -> dict:
    cfg = {"maxTokens": tool.max_tokens}
    # Claude 4.6+ rejects non-default sampling parameters; Nova calls tools most reliably near-greedy.
    if "amazon.nova" in model_id:
        cfg["temperature"] = 0.0
    return cfg


def split_template(filled: str) -> tuple:
    """A filled prompt template contains DATA_MARK where the untrusted block goes."""
    if DATA_MARK in filled:
        before, after = filled.split(DATA_MARK, 1)
        return before.rstrip(), after.lstrip()
    return filled, ""


def build_request(settings, model_id: str, tool: Tool, before: str, data: str, after: str, mode: str) -> dict:
    content = []
    if before.strip():
        content.append({"text": before})
    if data:
        if settings.guardrail_on:
            content.append({"guardContent": {"text": {"text": data}}})
        else:
            content.append({"text": data})
    if after.strip():
        content.append({"text": after})
    tool_config = {"tools": [tool.spec()]}
    if mode == "tool":
        tool_config["toolChoice"] = {"tool": {"name": tool.name}}
    elif mode == "any":
        tool_config["toolChoice"] = {"any": {}}
    request = {
        "modelId": model_id,
        "system": [{"text": SYSTEM}],
        "messages": [{"role": "user", "content": content}],
        "inferenceConfig": inference_config(model_id, tool),
        "toolConfig": tool_config,
    }
    if settings.guardrail_on:
        request["guardrailConfig"] = {
            "guardrailIdentifier": settings.guardrail_id,
            "guardrailVersion": settings.guardrail_version,
            "trace": "disabled",
        }
    return request


def _json_object_in(text: str):
    start = text.find("{")
    while start != -1:
        depth = 0
        in_str = False
        esc = False
        for i in range(start, len(text)):
            ch = text[i]
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
            elif ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    try:
                        value = json.loads(text[start:i + 1])
                    except ValueError:
                        break
                    if isinstance(value, dict):
                        return value
                    break
        start = text.find("{", start + 1)
    return None


def extract_tool_input(response: dict, tool_name: str) -> dict:
    message = ((response or {}).get("output") or {}).get("message") or {}
    blocks = message.get("content") or []
    for block in blocks:
        use = block.get("toolUse") if isinstance(block, dict) else None
        if not use:
            continue
        if use.get("name") not in (tool_name, None, ""):
            continue
        value = use.get("input")
        if isinstance(value, str):
            try:
                value = json.loads(value)
            except ValueError:
                value = None
        if isinstance(value, dict):
            return value
    # Some models answer in text despite the tool; accept a JSON object if one is there.
    for block in blocks:
        text = block.get("text") if isinstance(block, dict) else None
        if isinstance(text, str):
            value = _json_object_in(text)
            if value is not None:
                return value
    raise ModelOutputError("no tool call in the model response")


def run_tool(settings, tool: Tool, prompt: str, data: str, log: obs.RequestLog) -> ToolResult:
    """Runs one forced-tool-use Converse call (with fallbacks). Raises BedrockFailure if nothing worked."""
    before, after = split_template(prompt)
    models = [settings.model_id]
    if settings.fallback_model_id and settings.fallback_model_id != settings.model_id:
        models.append(settings.fallback_model_id)
    client = aws.client("bedrock-runtime")
    started = time.monotonic()
    failure = BedrockFailure("NoModel", "no model configured")
    for index, model in enumerate(models):
        if index > 0 and (time.monotonic() - started) * 1000 > FALLBACK_BUDGET_MS:
            break  # the TV is about to give up anyway; keep the original error
        modes = ["tool", "any", "auto"]
        mode_index = 0
        while mode_index < len(modes):
            request = build_request(settings, model, tool, before, data, after, modes[mode_index])
            t0 = time.monotonic()
            try:
                response = client.converse(**request)
            except Exception as exc:  # botocore ClientError, timeouts, connection errors
                log.add_model_ms(int((time.monotonic() - t0) * 1000))
                code, message = aws.error_code(exc), aws.error_message(exc)
                if code == "ValidationException" and _TOOL_CHOICE_HINT.search(message) and mode_index < 2:
                    mode_index += 1
                    continue
                obs.warn("bedrock_error", model=model, code=code, tool=tool.name)
                failure = BedrockFailure(code, message)
                break
            log.add_model_ms(int((time.monotonic() - t0) * 1000))
            stop = str(response.get("stopReason") or "")
            usage = response.get("usage") or {}
            if stop in BLOCK_STOPS:
                return ToolResult({}, model, blocked=True, fallback_used=index > 0, stop_reason=stop, usage=usage)
            try:
                data_out = extract_tool_input(response, tool.name)
            except ModelOutputError as exc:
                obs.warn("bedrock_bad_output", model=model, tool=tool.name, stop=stop)
                failure = BedrockFailure("ModelOutputError", str(exc))
                break
            return ToolResult(data_out, model, blocked=False, fallback_used=index > 0, stop_reason=stop, usage=usage)
    raise failure


def output_blocked(settings, texts, log: obs.RequestLog) -> bool:
    """ApplyGuardrail on the final strings the family will see. Fails open (logged) if the check itself errors."""
    if not settings.guardrail_on:
        return False
    joined = "\n".join(t for t in texts if isinstance(t, str) and t.strip())
    if not joined:
        return False
    t0 = time.monotonic()
    try:
        response = aws.client("bedrock-runtime").apply_guardrail(
            guardrailIdentifier=settings.guardrail_id,
            guardrailVersion=settings.guardrail_version,
            source="OUTPUT",
            content=[{"text": {"text": joined[:6000]}}],
        )
    except Exception as exc:
        obs.warn("output_guardrail_error", code=aws.error_code(exc))
        return False
    finally:
        log.set(guardrail_ms=int((time.monotonic() - t0) * 1000))
    return str(response.get("action") or "") == "GUARDRAIL_INTERVENED"
