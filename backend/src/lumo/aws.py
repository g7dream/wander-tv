"""boto3 clients, created lazily and reused across warm Lambda invocations.

Timeouts are tuned to the TV: it waits 8 s for cards/quests, so Bedrock gets no automatic retries (a
fallback model is tried instead) and DynamoDB gets short timeouts because the cache is optional.
"""

_clients = {}

_CONFIGS = {
    "bedrock-runtime": {"connect_timeout": 3, "read_timeout": 12, "retries": {"max_attempts": 1, "mode": "standard"}},
    "dynamodb": {"connect_timeout": 1, "read_timeout": 2, "retries": {"max_attempts": 2, "mode": "standard"}},
    "polly": {"connect_timeout": 2, "read_timeout": 8, "retries": {"max_attempts": 2, "mode": "standard"}},
}


def client(name: str):
    c = _clients.get(name)
    if c is None:
        import boto3  # bundled with the Lambda runtime

        kwargs = {}
        try:
            from botocore.config import Config

            kwargs["config"] = Config(**_CONFIGS.get(name, {}))
        except Exception:  # pragma: no cover - botocore always ships with boto3
            pass
        c = boto3.client(name, **kwargs)
        _clients[name] = c
    return c


def reset():
    """Drops cached clients (tests swap the fake boto3 between cases)."""
    _clients.clear()


def error_code(exc: BaseException) -> str:
    """The AWS error code of a botocore ClientError ('ThrottlingException'), or the exception class name."""
    response = getattr(exc, "response", None)
    if isinstance(response, dict):
        code = (response.get("Error") or {}).get("Code")
        if code:
            return str(code)
    return type(exc).__name__


def error_message(exc: BaseException) -> str:
    response = getattr(exc, "response", None)
    if isinstance(response, dict):
        msg = (response.get("Error") or {}).get("Message")
        if msg:
            return str(msg)
    return str(exc)


THROTTLED = {
    "ThrottlingException", "TooManyRequestsException", "ServiceQuotaExceededException",
    "ProvisionedThroughputExceededException", "RequestLimitExceeded", "ServiceUnavailableException",
    "ServiceUnavailable", "ModelNotReadyException", "ModelTimeoutException", "InternalServerException",
    "ModelErrorException", "ReadTimeoutError", "ConnectTimeoutError", "EndpointConnectionError",
    "ConnectionClosedError",
}
