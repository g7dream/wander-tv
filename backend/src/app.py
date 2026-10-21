"""AWS Lambda entry point (Handler: app.handler). The real work lives in the lumo package."""

import os

from lumo import aws
from lumo.handler import handler  # noqa: F401  (re-exported for Lambda)

# Create the boto3 clients during the Lambda init phase (full CPU, not billed as request latency), so the
# first family request after a cold start does not pay for it. Skipped outside Lambda (tests, dev server).
if os.environ.get("AWS_LAMBDA_FUNCTION_NAME"):
    for _name in ("bedrock-runtime", "dynamodb", "polly"):
        try:
            aws.client(_name)
        except Exception:  # never let warm-up break the cold start; the request path retries lazily
            pass
