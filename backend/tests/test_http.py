"""Auth, routing, body parsing, logging."""

import json
import os
import unittest

from fakes import ENV, LambdaCase


class AuthTests(LambdaCase):
    def test_missing_key_is_401(self):
        status, body = self.call("GET", "/health", key=None)
        self.assertEqual(status, 401)
        self.assertEqual(body["error"], "unauthorized")

    def test_wrong_key_is_401(self):
        status, _ = self.call("GET", "/health", key="nope")
        self.assertEqual(status, 401)

    def test_almost_right_key_is_401(self):
        status, _ = self.call("GET", "/health", key=ENV["API_KEY"] + "x")
        self.assertEqual(status, 401)

    def test_unconfigured_server_key_rejects_everyone(self):
        os.environ["API_KEY"] = ""
        status, _ = self.call("GET", "/health", key="")
        self.assertEqual(status, 401)

    def test_header_name_is_case_insensitive(self):
        status, body = self.call("GET", "/health", key=None, headers={"X-Wander-Key": ENV["API_KEY"]})
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])

    def test_unknown_route_without_key_does_not_leak_routes(self):
        status, _ = self.call("GET", "/admin", key=None)
        self.assertEqual(status, 401)


class HealthTests(LambdaCase):
    def test_health_reports_model_and_region(self):
        status, body = self.call("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(body["ok"], True)
        self.assertEqual(body["model"], "eu.anthropic.claude-haiku-5-5")
        self.assertEqual(body["region"], "eu-central-1")
        self.assertEqual(body["fallbackModel"], "eu.amazon.nova-2-lite-v1:0")
        self.assertTrue(body["guardrail"])
        self.assertTrue(body["cache"])
        self.assertEqual(self.bedrock.calls, [])  # health never spends tokens


class RoutingTests(LambdaCase):
    def test_unknown_path_is_404(self):
        status, body = self.call("GET", "/nope")
        self.assertEqual(status, 404)
        self.assertEqual(body["error"], "not_found")

    def test_wrong_method_is_405(self):
        status, _ = self.call("GET", "/cards")
        self.assertEqual(status, 405)

    def test_raw_path_with_named_stage_is_routed(self):
        event = self.event("GET", "/health")
        event["routeKey"] = "$default"
        event["rawPath"] = "/prod/health"
        event["requestContext"]["stage"] = "prod"
        result = self.invoke(event)
        self.assertEqual(result["statusCode"], 200)

    def test_trailing_slash_is_tolerated(self):
        event = self.event("GET", "/health")
        event["routeKey"] = "$default"
        event["rawPath"] = "/health/"
        self.assertEqual(self.invoke(event)["statusCode"], 200)


class BodyTests(LambdaCase):
    def test_malformed_json_is_400(self):
        status, body = self.call("POST", "/cards", raw="{not json")
        self.assertEqual(status, 400)
        self.assertEqual(body["error"], "bad_json")

    def test_json_array_is_400(self):
        status, body = self.call("POST", "/cards", raw="[1,2,3]")
        self.assertEqual(status, 400)

    def test_empty_body_is_400(self):
        status, _ = self.call("POST", "/quest/new", raw="")
        self.assertEqual(status, 400)

    def test_missing_field_names_the_field(self):
        status, body = self.call("POST", "/cards", {"url": "https://x", "title": "x"})
        self.assertEqual(status, 400)
        self.assertEqual(body, {"error": "missing_field", "field": "text"})

    def test_base64_body_is_decoded(self):
        status, body = self.call("POST", "/cards", {"url": "u", "title": "t", "text": "short"}, b64=True)
        self.assertEqual(status, 200)
        self.assertEqual(body["cards"][0]["title"], "Not much to read here")

    def test_oversized_body_is_413(self):
        status, body = self.call("POST", "/cards", {"text": "x" * 70000})
        self.assertEqual(status, 413)

    def test_garbage_event_is_answered(self):
        result = self.invoke("not an event")
        self.assertEqual(result["statusCode"], 400)


class LoggingTests(LambdaCase):
    def test_structured_log_has_required_fields_and_no_page_text(self):
        from fakes import tool_response

        secret_text = "The secret family page text mentions zebras galloping. " * 5
        self.bedrock.push(tool_response("make_cards", {"cards": [
            {"title": "Striped runners", "line": "Zebras gallop across the grass in big herds.", "emoji": "\U0001F993", "icon": "animal"},
            {"title": "Stripe code", "line": "Every zebra has its own pattern of stripes.", "emoji": "\U0001F993", "icon": "animal"},
            {"title": "Team players", "line": "Zebras stay together to keep each other safe.", "emoji": "\U0001F993", "icon": "animal"},
        ]}))
        status, _ = self.call("POST", "/cards", {"url": "https://example.org/zebra", "title": "Zebra", "text": secret_text})
        self.assertEqual(status, 200)
        record = self.request_log()
        for field in ("route", "model", "latency_ms", "cached", "blocked", "status", "request_id"):
            self.assertIn(field, record)
        self.assertEqual(record["route"], "/cards")
        self.assertEqual(record["model"], "eu.anthropic.claude-haiku-5-5")
        self.assertEqual(record["_aws"]["CloudWatchMetrics"][0]["Namespace"], "WanderTV")
        # EMF validity: every declared metric and dimension is a top-level member of the record.
        for directive in record["_aws"]["CloudWatchMetrics"]:
            for metric in directive["Metrics"]:
                self.assertIsInstance(record[metric["Name"]], (int, float))
            for dims in directive["Dimensions"]:
                for dim in dims:
                    self.assertIsInstance(record[dim], str)
        dumped = json.dumps(self.logs)
        self.assertNotIn("zebras galloping", dumped)
        self.assertNotIn("Zebras gallop", dumped)

    def test_clients_are_warmed_during_lambda_init(self):
        import importlib

        import app
        from lumo import aws

        os.environ["AWS_LAMBDA_FUNCTION_NAME"] = "wander-tv-lumo"
        try:
            aws.reset()
            importlib.reload(app)
            self.assertEqual(set(aws._clients), {"bedrock-runtime", "dynamodb", "polly"})
        finally:
            os.environ.pop("AWS_LAMBDA_FUNCTION_NAME", None)
            aws.reset()

    def test_internal_crash_still_returns_json_500(self):
        from lumo import handler, routes

        original = handler.ROUTES[("GET", "/health")]
        handler.ROUTES[("GET", "/health")] = lambda body, settings, log: 1 / 0
        try:
            status, body = self.call("GET", "/health")
        finally:
            handler.ROUTES[("GET", "/health")] = original
        self.assertEqual(status, 500)
        self.assertEqual(body["error"], "internal")
        self.assertIs(routes.health, original)


if __name__ == "__main__":
    unittest.main()
