"""Run the real Lambda handler on your laptop, for the TV app and the smoke tests.

  python backend/dev_server.py --fake                 # no AWS at all: heuristic stand-ins for Bedrock/Polly/DynamoDB
  python backend/dev_server.py                        # real Bedrock + Polly with your AWS credentials (needs boto3)

Then point the TV at it (same Wi-Fi):
  adb shell am start -n tv.wander/.MainActivity --es api_url http://<laptop-ip>:8787 --es api_key <key>
and/or run the smoke tests (-ExecutionPolicy Bypass because Windows disables scripts by default):
  powershell -NoProfile -ExecutionPolicy Bypass -File backend/smoke_test.ps1 -ApiUrl http://localhost:8787 -ApiKey <key>
  bash backend/smoke_test.sh http://localhost:8787 <key>

The server converts each HTTP request into an API Gateway HTTP API (payload v2.0) event, so routing, auth,
validation and logging are exactly the code that runs in Lambda.
"""

import argparse
import json
import os
import socket
import sys
import types
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "src"))
sys.path.insert(0, os.path.join(HERE, "devtools"))

DEFAULT_KEY = "dev-key-wander-tv-local"


def install_fakes():
    import fake_aws

    registry = fake_aws.clients()
    boto3 = types.ModuleType("boto3")
    boto3.client = lambda name, **kwargs: registry[name]
    sys.modules["boto3"] = boto3
    os.environ.setdefault("TABLE_NAME", "dev-cache")
    os.environ["MODEL_ID"] = "dev-fake-brain"
    os.environ["FALLBACK_MODEL_ID"] = ""


def lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


class Handler(BaseHTTPRequestHandler):
    server_version = "WanderDev/1.0"

    def _handle(self, method: str):
        from lumo.handler import handler

        path, _, query = self.path.partition("?")
        length = int(self.headers.get("content-length") or 0)
        raw = self.rfile.read(min(length, 1_000_000)).decode("utf-8", "replace") if length else None
        event = {
            "version": "2.0",
            "routeKey": "$default",
            "rawPath": path,
            "rawQueryString": query,
            "headers": {k.lower(): v for k, v in self.headers.items()},
            "requestContext": {"http": {"method": method, "path": path}, "stage": "$default"},
            "body": raw,
            "isBase64Encoded": False,
        }
        result = handler(event, None)
        body = result["body"].encode("utf-8")
        self.send_response(result["statusCode"])
        for k, v in result["headers"].items():
            self.send_header(k, v)
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--key", default=os.environ.get("API_KEY") or DEFAULT_KEY)
    parser.add_argument("--fake", action="store_true", help="use heuristic stand-ins instead of AWS")
    args = parser.parse_args()

    if args.fake:
        install_fakes()
    else:
        try:
            import boto3  # noqa: F401
        except ImportError:
            print("boto3 is not installed; starting with --fake. (pip install boto3 for real Bedrock calls.)")
            install_fakes()
            args.fake = True
    os.environ["API_KEY"] = args.key
    os.environ.setdefault("AWS_REGION", "eu-central-1")

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    mode = "FAKE brain (no AWS)" if args.fake else f"REAL Bedrock in {os.environ['AWS_REGION']}"
    print(json.dumps({"listening": f"http://{lan_ip()}:{args.port}", "mode": mode, "api_key": args.key}))
    print(f"TV:    adb shell am start -n tv.wander/.MainActivity --es api_url http://{lan_ip()}:{args.port} "
          f"--es api_key {args.key}")
    print(f"Smoke: powershell -NoProfile -ExecutionPolicy Bypass -File backend/smoke_test.ps1 "
          f"-ApiUrl http://localhost:{args.port} -ApiKey {args.key}")
    print(f"       bash backend/smoke_test.sh http://localhost:{args.port} {args.key}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
