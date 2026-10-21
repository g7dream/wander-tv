"""Lumo's brain for Wander TV.

API Gateway (HTTP API) -> Lambda (this package) -> Bedrock Converse (forced tool use) + Bedrock Guardrails
-> DynamoDB (card + speech cache) -> Amazon Polly -> CloudWatch (structured logs + EMF metrics).

Only boto3 (bundled with the Lambda Python runtime) is required.
"""

VERSION = "1.0.0"
