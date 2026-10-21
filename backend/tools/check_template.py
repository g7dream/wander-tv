"""Offline structural check of backend/template.yaml (no AWS credentials needed).

  python backend/tools/check_template.py

Parses the template with CloudFormation short-form tags, then checks what the deploy depends on: every
!Ref / !GetAtt / ${...} target exists, the function's runtime/arch/memory/timeout/env, the table's TTL, the
guardrail filters, least-privilege IAM actions, the HTTP routes, valid dashboard JSON and the ApiUrl output.
Exits 1 with a list of problems, 0 when the template looks deployable.
"""

import json
import os
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    print("PyYAML is needed for this check: pip install pyyaml")
    sys.exit(2)

TEMPLATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "template.yaml")
PSEUDO = {"AWS::Region", "AWS::AccountId", "AWS::Partition", "AWS::StackName", "AWS::URLSuffix", "AWS::NoValue",
          "AWS::StackId", "AWS::NotificationARNs"}
TAGS = ["Ref", "GetAtt", "Sub", "If", "Equals", "Not", "And", "Or", "Join", "Select", "Split", "FindInMap",
        "ImportValue", "Condition", "Base64", "GetAZs", "Cidr", "Transform"]


class Tagged:
    def __init__(self, tag, value):
        self.tag = tag
        self.value = value

    def __repr__(self):
        return f"!{self.tag} {self.value!r}"


class CfnLoader(yaml.SafeLoader):
    pass


def _make(tag):
    def construct(loader, node):
        if isinstance(node, yaml.ScalarNode):
            value = loader.construct_scalar(node)
            if tag == "GetAtt" and isinstance(value, str):
                value = value.split(".", 1)
        elif isinstance(node, yaml.SequenceNode):
            value = loader.construct_sequence(node, deep=True)
        else:
            value = loader.construct_mapping(node, deep=True)
        return Tagged(tag, value)
    return construct


for _t in TAGS:
    CfnLoader.add_constructor("!" + _t, _make(_t))


def load(path=TEMPLATE):
    with open(path, encoding="utf-8") as f:
        return yaml.load(f, Loader=CfnLoader)


def walk(node, visit):
    visit(node)
    if isinstance(node, Tagged):
        walk(node.value, visit)
    elif isinstance(node, dict):
        for v in node.values():
            walk(v, visit)
    elif isinstance(node, list):
        for v in node:
            walk(v, visit)


def check(doc) -> list:
    problems = []
    need = lambda cond, msg: None if cond else problems.append(msg)  # noqa: E731

    need(doc.get("Transform") == "AWS::Serverless-2016-10-31", "Transform must be AWS::Serverless-2016-10-31")
    resources = doc.get("Resources") or {}
    params = doc.get("Parameters") or {}
    conditions = doc.get("Conditions") or {}
    names = set(resources) | set(params) | PSEUDO

    def visit(node):
        if not isinstance(node, Tagged):
            return
        if node.tag == "Ref":
            need(node.value in names, f"!Ref to unknown name: {node.value}")
        elif node.tag == "GetAtt":
            target = node.value[0] if isinstance(node.value, list) else str(node.value).split(".")[0]
            need(target in resources, f"!GetAtt to unknown resource: {target}")
        elif node.tag == "Sub":
            template = node.value if isinstance(node.value, str) else node.value[0]
            local = set(node.value[1]) if isinstance(node.value, list) and len(node.value) > 1 else set()
            for ref in re.findall(r"\$\{([^}!]+)\}", template):
                base = ref.split(".")[0]
                need(ref in names or base in resources or ref in local, f"!Sub references unknown ${{{ref}}}")
        elif node.tag == "If":
            need(node.value[0] in conditions, f"!If uses unknown condition {node.value[0]}")

    walk(resources, visit)
    walk(doc.get("Outputs") or {}, visit)

    by_type = {}
    for name, res in resources.items():
        by_type.setdefault(res.get("Type"), []).append((name, res.get("Properties") or {}))

    fns = by_type.get("AWS::Serverless::Function", [])
    need(len(fns) == 1, "expected exactly one AWS::Serverless::Function")
    if fns:
        _, fn = fns[0]
        need(fn.get("Runtime") == "python3.12", "function runtime must be python3.12")
        need(fn.get("Architectures") == ["arm64"], "function must be arm64")
        need(fn.get("MemorySize") == 512, "function memory must be 512 MB")
        need(fn.get("Timeout") == 20, "function timeout must be 20 s")
        need(fn.get("Handler") == "app.handler", "handler must be app.handler")
        code_uri = fn.get("CodeUri")
        need(isinstance(code_uri, str) and os.path.isfile(os.path.join(os.path.dirname(TEMPLATE), code_uri, "app.py")),
             "CodeUri must point at the folder that contains app.py")
        env = (fn.get("Environment") or {}).get("Variables") or {}
        for var in ("API_KEY", "MODEL_ID", "FALLBACK_MODEL_ID", "VOICE_ID", "TABLE_NAME", "GUARDRAIL_ID",
                    "GUARDRAIL_VERSION"):
            need(var in env, f"function env var {var} missing")
        routes = set()
        for ev in (fn.get("Events") or {}).values():
            if ev.get("Type") == "HttpApi":
                p = ev.get("Properties") or {}
                routes.add((p.get("Method"), p.get("Path")))
        for route in [("GET", "/health"), ("POST", "/cards"), ("POST", "/quest/check"), ("POST", "/quest/new"),
                      ("POST", "/companion"), ("POST", "/speak")]:
            need(route in routes, f"missing HttpApi route {route[0]} {route[1]}")
        actions = set()
        star_actions = set()
        for policy in fn.get("Policies") or []:
            if not isinstance(policy, dict):
                continue
            for st in policy.get("Statement") or []:
                acts = st.get("Action")
                acts = acts if isinstance(acts, list) else [acts]
                actions.update(acts)
                if st.get("Resource") == "*":
                    star_actions.update(acts)
        for action in ("bedrock:InvokeModel", "bedrock:ApplyGuardrail", "polly:SynthesizeSpeech",
                       "dynamodb:GetItem", "dynamodb:PutItem"):
            need(action in actions, f"IAM action {action} missing")
        need(not any(a.endswith(":*") or a == "*" for a in actions), "IAM actions must not use wildcards")
        need(star_actions <= {"polly:SynthesizeSpeech"}, f"only Polly may use Resource '*': {star_actions}")

    tables = by_type.get("AWS::DynamoDB::Table", [])
    need(len(tables) == 1, "expected one DynamoDB table")
    if tables:
        _, t = tables[0]
        need(t.get("BillingMode") == "PAY_PER_REQUEST", "table must be PAY_PER_REQUEST")
        ttl = t.get("TimeToLiveSpecification") or {}
        need(ttl.get("Enabled") is True and ttl.get("AttributeName") == "ttl", "table TTL on attribute 'ttl'")

    guards = by_type.get("AWS::Bedrock::Guardrail", [])
    need(len(guards) == 1, "expected one Bedrock guardrail")
    if guards:
        _, g = guards[0]
        need(bool(g.get("BlockedInputMessaging")) and bool(g.get("BlockedOutputsMessaging")), "blocked messages")
        filters = {f.get("Type"): f for f in (g.get("ContentPolicyConfig") or {}).get("FiltersConfig") or []}
        for kind in ("SEXUAL", "VIOLENCE", "HATE", "INSULTS", "MISCONDUCT"):
            need(kind in filters and filters[kind].get("OutputStrength") == "HIGH", f"{kind} filter at HIGH")
        pa = filters.get("PROMPT_ATTACK") or {}
        need(pa.get("InputStrength") == "HIGH" and pa.get("OutputStrength") == "NONE",
             "PROMPT_ATTACK must be input HIGH / output NONE")
    need(len(by_type.get("AWS::Bedrock::GuardrailVersion", [])) == 1, "expected one GuardrailVersion")
    need(len(by_type.get("AWS::Serverless::HttpApi", [])) == 1, "expected one HttpApi")

    for _, dash in by_type.get("AWS::CloudWatch::Dashboard", []):
        body = dash.get("DashboardBody")
        text = body.value if isinstance(body, Tagged) else body
        try:
            json.loads(re.sub(r"\$\{[^}]+\}", "x", text))
        except (TypeError, ValueError) as exc:
            problems.append(f"dashboard body is not valid JSON: {exc}")

    need("ApiUrl" in (doc.get("Outputs") or {}), "Outputs.ApiUrl missing")
    api_key = params.get("ApiKey") or {}
    need(api_key.get("NoEcho") is True, "ApiKey parameter must be NoEcho")
    for p in ("ModelId", "VoiceId"):
        need(p in params, f"parameter {p} missing")
    return problems


def main() -> int:
    problems = check(load())
    if problems:
        print("template.yaml problems:")
        for p in problems:
            print("  -", p)
        return 1
    print("template.yaml OK: references resolve; function, table, guardrail, IAM, routes, dashboard and outputs check out.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
