"""Fake boto3/botocore, installed into sys.modules before the Lambda code is imported.

No AWS account, network or real boto3 is needed: every client is an in-memory fake that records its calls.
"""

import base64
import contextlib
import io
import json
import os
import sys
import types
import unittest

SRC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))

REGISTRY = {}


class FakeClientError(Exception):
    """Shaped like botocore.exceptions.ClientError (the code reads exc.response['Error'])."""

    def __init__(self, code: str, message: str = "", operation: str = "Converse"):
        super().__init__(f"An error occurred ({code}) when calling the {operation} operation: {message}")
        self.response = {"Error": {"Code": code, "Message": message}}


class FakeConfig:
    def __init__(self, **kwargs):
        self.kwargs = kwargs


def install():
    boto3 = types.ModuleType("boto3")

    def client(name, **kwargs):
        if name not in REGISTRY:
            raise AssertionError(f"unexpected boto3 client: {name}")
        REGISTRY[name].client_kwargs = kwargs
        return REGISTRY[name]

    boto3.client = client
    botocore = types.ModuleType("botocore")
    botocore_config = types.ModuleType("botocore.config")
    botocore_config.Config = FakeConfig
    botocore_exceptions = types.ModuleType("botocore.exceptions")
    botocore_exceptions.ClientError = FakeClientError
    botocore.config = botocore_config
    botocore.exceptions = botocore_exceptions
    sys.modules["boto3"] = boto3
    sys.modules["botocore"] = botocore
    sys.modules["botocore.config"] = botocore_config
    sys.modules["botocore.exceptions"] = botocore_exceptions
    if SRC not in sys.path:
        sys.path.insert(0, SRC)


install()

import app  # noqa: E402  (must come after install())
from lumo import aws  # noqa: E402


def tool_response(name: str, data: dict, stop: str = "tool_use") -> dict:
    return {
        "output": {"message": {"role": "assistant", "content": [
            {"toolUse": {"toolUseId": "tooluse_1", "name": name, "input": data}},
        ]}},
        "stopReason": stop,
        "usage": {"inputTokens": 1500, "outputTokens": 200, "totalTokens": 1700},
        "metrics": {"latencyMs": 900},
    }


def text_response(text: str, stop: str = "end_turn") -> dict:
    return {
        "output": {"message": {"role": "assistant", "content": [{"text": text}]}},
        "stopReason": stop,
        "usage": {"inputTokens": 10, "outputTokens": 10, "totalTokens": 20},
    }


def blocked_response() -> dict:
    return text_response("Lumo can't help with that page.", stop="guardrail_intervened")


class FakeBedrock:
    def __init__(self):
        self.calls = []
        self.queue = []
        self.guardrail_calls = []
        self.guardrail_action = "NONE"

    def push(self, *items):
        self.queue.extend(items)

    def converse(self, **kwargs):
        self.calls.append(kwargs)
        if not self.queue:
            raise AssertionError("FakeBedrock.converse called with no queued response")
        item = self.queue.pop(0)
        if callable(item):
            item = item(kwargs)
        if isinstance(item, BaseException):
            raise item
        return item

    def apply_guardrail(self, **kwargs):
        self.guardrail_calls.append(kwargs)
        if isinstance(self.guardrail_action, BaseException):
            raise self.guardrail_action
        return {"action": self.guardrail_action, "outputs": [], "assessments": []}

    # helpers for assertions
    def user_blocks(self, index: int = -1) -> list:
        return self.calls[index]["messages"][0]["content"]

    def guarded_text(self, index: int = -1) -> str:
        for block in self.user_blocks(index):
            if "guardContent" in block:
                return block["guardContent"]["text"]["text"]
        return ""

    def instruction_text(self, index: int = -1) -> str:
        return "\n".join(b["text"] for b in self.user_blocks(index) if "text" in b)


class FakeDynamo:
    def __init__(self):
        self.items = {}
        self.gets = 0
        self.puts = []
        self.fail_get = False
        self.fail_put = False

    def get_item(self, TableName, Key, ConsistentRead=False):
        self.gets += 1
        if self.fail_get:
            raise FakeClientError("ProvisionedThroughputExceededException", "slow down", "GetItem")
        item = self.items.get(Key["pk"]["S"])
        return {"Item": dict(item)} if item else {}

    def put_item(self, TableName, Item):
        if self.fail_put:
            raise FakeClientError("InternalServerError", "boom", "PutItem")
        self.puts.append((TableName, Item))
        self.items[Item["pk"]["S"]] = dict(Item)
        return {}


class FakeStream(io.BytesIO):
    pass


class FakePolly:
    AUDIO = b"ID3\x04\x00fake-mp3-frames-for-lumo"

    def __init__(self):
        self.calls = []
        self.errors = []

    def synthesize_speech(self, **kwargs):
        self.calls.append(kwargs)
        if self.errors:
            raise self.errors.pop(0)
        return {"AudioStream": FakeStream(self.AUDIO), "ContentType": "audio/mpeg", "RequestCharacters": 42}


ENV = {
    "API_KEY": "test-key-0123456789",
    "MODEL_ID": "eu.anthropic.claude-haiku-5-5",
    "FALLBACK_MODEL_ID": "eu.amazon.nova-2-lite-v1:0",
    "TABLE_NAME": "wander-test",
    "GUARDRAIL_ID": "gr-test",
    "GUARDRAIL_VERSION": "1",
    "VOICE_ID": "Ivy",
    "AWS_REGION": "eu-central-1",
}


class Ctx:
    aws_request_id = "req-test-1"


class LambdaCase(unittest.TestCase):
    """Fresh fakes + env for every test; call() runs the real Lambda handler on an HTTP API v2.0 event."""

    def setUp(self):
        self._saved_env = {k: os.environ.get(k) for k in list(ENV) + ["CACHE_TTL_DAYS"]}
        os.environ.update(ENV)
        os.environ.pop("CACHE_TTL_DAYS", None)
        self.bedrock = FakeBedrock()
        self.dynamo = FakeDynamo()
        self.polly = FakePolly()
        REGISTRY.clear()
        REGISTRY.update({"bedrock-runtime": self.bedrock, "dynamodb": self.dynamo, "polly": self.polly})
        aws.reset()
        self.logs = []

    def tearDown(self):
        for k, v in self._saved_env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        aws.reset()

    def event(self, method: str, path: str, body=None, key=ENV["API_KEY"], raw=None, b64=False, headers=None):
        hdrs = {"content-type": "application/json"}
        if key is not None:
            hdrs["x-wander-key"] = key
        hdrs.update(headers or {})
        payload = raw if raw is not None else (json.dumps(body) if body is not None else None)
        if b64 and payload is not None:
            payload = base64.b64encode(payload.encode("utf-8")).decode("ascii")
        return {
            "version": "2.0",
            "routeKey": f"{method} {path}",
            "rawPath": path,
            "rawQueryString": "",
            "headers": hdrs,
            "requestContext": {"http": {"method": method, "path": path}, "stage": "$default", "requestId": "abc"},
            "body": payload,
            "isBase64Encoded": b64,
        }

    def invoke(self, event):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            result = app.handler(event, Ctx())
        self.logs = [json.loads(line) for line in out.getvalue().splitlines() if line.strip()]
        return result

    def call(self, method: str, path: str, body=None, **kwargs):
        result = self.invoke(self.event(method, path, body, **kwargs))
        self.assertEqual(result["headers"]["content-type"], "application/json; charset=utf-8")
        return result["statusCode"], json.loads(result["body"])

    def request_log(self) -> dict:
        for record in self.logs:
            if "_aws" in record:
                return record
        self.fail("no request log line was emitted")
