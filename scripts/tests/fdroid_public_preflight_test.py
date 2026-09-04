"""Focused contracts for the anonymous F-Droid origin preflight."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
PREFLIGHT_PATH = ROOT / "tools" / "fdroid" / "public_origin_preflight.py"
SPEC = importlib.util.spec_from_file_location(
    "fdroid_public_origin_preflight", PREFLIGHT_PATH
)
assert SPEC is not None and SPEC.loader is not None
preflight = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = preflight
SPEC.loader.exec_module(preflight)


class FakeSocket:
    def __init__(self) -> None:
        self.timeouts: list[float] = []

    def settimeout(self, timeout: float) -> None:
        self.timeouts.append(timeout)


class FakeResponse:
    def __init__(
        self, status: int, headers: list[tuple[str, str]], body: bytes
    ) -> None:
        self.status = status
        self._headers = headers
        self._body = body
        self._offset = 0
        self.read_calls = 0

    def getheaders(self) -> list[tuple[str, str]]:
        return self._headers

    def read(self, amount: int | None = None) -> bytes:
        self.read_calls += 1
        if amount is None:
            amount = len(self._body) - self._offset
        chunk = self._body[self._offset : self._offset + amount]
        self._offset += len(chunk)
        return chunk


class FakeConnection:
    def __init__(
        self,
        response: FakeResponse | None = None,
        connect_error: Exception | None = None,
    ):
        self.response = response
        self.connect_error = connect_error
        self.sock = FakeSocket()
        self.requests: list[tuple[str, str, bytes | None, dict[str, str]]] = []
        self.closed = False

    def connect(self) -> None:
        if self.connect_error is not None:
            raise self.connect_error

    def request(
        self,
        method: str,
        url: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        self.requests.append((method, url, body, dict(headers or {})))

    def getresponse(self) -> FakeResponse:
        assert self.response is not None
        return self.response

    def close(self) -> None:
        self.closed = True


class ConnectionQueue:
    def __init__(self, *connections: FakeConnection) -> None:
        self.connections = list(connections)
        self.calls: list[tuple[tuple[object, ...], dict[str, object]]] = []

    def __call__(self, *args: object, **kwargs: object) -> FakeConnection:
        self.calls.append((args, kwargs))
        return self.connections.pop(0)


class PublicOriginPreflightTest(unittest.TestCase):
    def run_probe(self, factory: ConnectionQueue) -> tuple[int, str]:
        output = io.StringIO()
        status = preflight.run_preflight(
            connection_factory=factory,
            sleep=lambda _: None,
            monotonic=lambda: 10.0,
            output=output,
        )
        return status, output.getvalue()

    def test_shared_user_agent_contract_is_one_bounded_honest_line(self) -> None:
        raw = preflight.PUBLIC_USER_AGENT_PATH.read_bytes()

        self.assertEqual(raw.count(b"\n"), 1)
        self.assertTrue(raw.endswith(b"\n"))
        self.assertLessEqual(len(raw), preflight.MAX_PUBLIC_USER_AGENT_BYTES + 1)
        self.assertEqual(raw[:-1].decode("ascii"), preflight.PUBLIC_USER_AGENT)
        self.assertEqual(
            preflight.PUBLIC_USER_AGENT,
            "ha-paneld-fdroid-public-verifier/1 "
            "(+https://github.com/maxlyth/ha-paneld)",
        )

    def test_user_agent_contract_rejects_header_injection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "public-user-agent.txt"
            path.write_bytes(
                b"ha-paneld-fdroid-public-verifier/1 "
                b"(+https://github.com/maxlyth/ha-paneld)\nInjected: value\n"
            )

            with self.assertRaises(RuntimeError):
                preflight._load_public_user_agent(path)

            path.write_bytes(
                b"ha-paneld-fdroid-public-verifier/1\0 "
                b"(+https://github.com/maxlyth/ha-paneld)\n"
            )
            with self.assertRaises(RuntimeError):
                preflight._load_public_user_agent(path)

    def test_200_accepts_bounded_json_and_logs_only_allowlisted_headers(self) -> None:
        body = b'{"repo":{"name":"ha-paneld"}}'
        response = FakeResponse(
            200,
            [
                ("Content-Type", "application/json"),
                ("Content-Length", str(len(body))),
                ("Server", "cloudflare"),
                ("CF-Cache-Status", "DYNAMIC"),
                ("CF-Ray", "abc-LHR"),
                ("Set-Cookie", "must-not-be-logged"),
            ],
            body,
        )
        connection = FakeConnection(response)
        status, output = self.run_probe(ConnectionQueue(connection))

        self.assertEqual(status, 0)
        self.assertIn("status=200", output)
        self.assertIn("cf-ray=abc-LHR", output)
        self.assertNotIn("must-not-be-logged", output)
        self.assertNotIn(body.decode(), output)
        self.assertTrue(connection.closed)

    def test_403_logs_a_body_identity_without_logging_the_body(self) -> None:
        body = b"<html><title>secret challenge detail</title></html>"
        response = FakeResponse(
            403,
            [
                ("Content-Type", "text/html"),
                ("CF-Mitigated", "challenge"),
                ("CF-Ray", "blocked-LHR"),
            ],
            body,
        )
        factory = ConnectionQueue(FakeConnection(response))
        status, output = self.run_probe(factory)

        self.assertEqual(status, 1)
        self.assertEqual(
            len(factory.calls), 1, "a non-transient 403 must not be retried"
        )
        self.assertIn("status=403", output)
        self.assertIn("cf-mitigated=challenge", output)
        self.assertIn("code=http_status", output)
        self.assertIn(f"body_bytes={len(body)}", output)
        self.assertIn(hashlib.sha256(body).hexdigest(), output)
        self.assertNotIn("secret challenge detail", output)

    def test_cloudflare_error_body_is_logged_only_as_a_sanitized_class(self) -> None:
        body = b"error code: 1010\n"
        response = FakeResponse(
            403,
            [
                ("Content-Type", "text/plain; charset=UTF-8"),
                ("Cache-Control", "private, no-store"),
                ("CF-Ray", "blocked-LHR"),
            ],
            body,
        )
        status, output = self.run_probe(ConnectionQueue(FakeConnection(response)))

        self.assertEqual(status, 1)
        self.assertIn("status=403", output)
        self.assertIn("cache-control=private, no-store", output)
        self.assertIn("body_class=cloudflare_error_1010", output)
        self.assertIn(f"body_bytes={len(body)}", output)
        self.assertIn(hashlib.sha256(body).hexdigest(), output)
        self.assertNotIn("error code: 1010", output)

    def test_allowlisted_header_values_are_restricted_to_printable_ascii(self) -> None:
        body = b"blocked"
        response = FakeResponse(
            403,
            [("Content-Type", "text/plain"), ("CF-Ray", "safe\x1b[31m\u202e-tail")],
            body,
        )
        status, output = self.run_probe(ConnectionQueue(FakeConnection(response)))

        self.assertEqual(status, 1)
        self.assertIn("cf-ray=safe?[31m?-tail", output)
        self.assertNotIn("\x1b", output)
        self.assertNotIn("\u202e", output)

    def test_excessive_declared_response_is_rejected_without_reading_its_body(
        self,
    ) -> None:
        response = FakeResponse(
            200,
            [
                ("Content-Type", "application/json"),
                ("Content-Length", str(preflight.MAX_BODY_BYTES + 1)),
            ],
            b"private oversized body",
        )
        status, output = self.run_probe(ConnectionQueue(FakeConnection(response)))

        self.assertEqual(status, 1)
        self.assertEqual(response.read_calls, 0)
        self.assertIn("code=body_too_large", output)
        self.assertNotIn("private oversized body", output)

    def test_excessive_content_length_digits_are_rejected_before_integer_conversion(
        self,
    ) -> None:
        enormous_length = "9" * 10_000
        response = FakeResponse(
            403,
            [("Content-Type", "text/html"), ("Content-Length", enormous_length)],
            b"must not be read",
        )
        status, output = self.run_probe(ConnectionQueue(FakeConnection(response)))

        self.assertEqual(status, 1)
        self.assertEqual(response.read_calls, 0)
        self.assertIn("code=invalid_content_length", output)
        self.assertNotIn("must not be read", output)

    def test_excessive_streamed_response_is_bounded_and_rejected(self) -> None:
        body = b"x" * (preflight.MAX_BODY_BYTES + 1)
        response = FakeResponse(200, [("Content-Type", "application/json")], body)
        status, output = self.run_probe(ConnectionQueue(FakeConnection(response)))

        self.assertEqual(status, 1)
        self.assertIn("code=body_too_large", output)
        self.assertIn(f"body_bytes={preflight.MAX_BODY_BYTES + 1}", output)
        self.assertEqual(response._offset, preflight.MAX_BODY_BYTES + 1)

    def test_allowlisted_header_is_sliced_before_normalization(self) -> None:
        hidden_suffix = "must-not-be-normalized"
        body = b"blocked"
        response = FakeResponse(
            403,
            [
                ("Content-Type", "text/html"),
                (
                    "CF-Ray",
                    " " * preflight.MAX_DIAGNOSTIC_HEADER_VALUE_CHARS + hidden_suffix,
                ),
            ],
            body,
        )
        status, output = self.run_probe(ConnectionQueue(FakeConnection(response)))

        self.assertEqual(status, 1)
        self.assertIn("cf-ray=", output)
        self.assertNotIn(hidden_suffix, output)

    def test_deeply_nested_json_is_reported_without_a_parser_traceback(self) -> None:
        body = b'{"value":' + b"[" * 10_000 + b"0" + b"]" * 10_000 + b"}"
        response = FakeResponse(200, [("Content-Type", "application/json")], body)
        status, output = self.run_probe(ConnectionQueue(FakeConnection(response)))

        self.assertEqual(status, 1)
        self.assertIn("code=invalid_json", output)
        self.assertIn(f"body_bytes={len(body)}", output)

    def test_json_integer_beyond_python_digit_limit_is_reported(self) -> None:
        body = b'{"value":' + b"9" * 10_000 + b"}"
        response = FakeResponse(200, [("Content-Type", "application/json")], body)
        status, output = self.run_probe(ConnectionQueue(FakeConnection(response)))

        self.assertEqual(status, 1)
        self.assertIn("code=invalid_json", output)
        self.assertIn(f"body_bytes={len(body)}", output)

    def test_environment_credentials_are_never_added_to_the_request(self) -> None:
        body = b'{"repo":{}}'
        connection = FakeConnection(
            FakeResponse(200, [("Content-Type", "application/json")], body)
        )
        factory = ConnectionQueue(connection)
        credentials = {
            "AWS_ACCESS_KEY_ID": "not-for-the-origin",
            "AWS_SECRET_ACCESS_KEY": "not-for-the-origin",
            "AWS_SESSION_TOKEN": "not-for-the-origin",
            "GH_TOKEN": "not-for-the-origin",
            "GITHUB_TOKEN": "not-for-the-origin",
        }
        with patch.dict(os.environ, credentials, clear=False):
            status, output = self.run_probe(factory)

        self.assertEqual(status, 0)
        self.assertNotIn("not-for-the-origin", output)
        self.assertEqual(len(connection.requests), 1)
        method, path, request_body, headers = connection.requests[0]
        self.assertEqual((method, path, request_body), ("GET", preflight.PATH, None))
        self.assertEqual(headers, preflight.REQUEST_HEADERS)
        self.assertEqual(headers["User-Agent"], preflight.PUBLIC_USER_AGENT)
        self.assertNotIn("Authorization", headers)
        self.assertNotIn("Cookie", headers)
        self.assertEqual(factory.calls[0][0][:2], (preflight.HOST, 443))

    def test_transient_transport_failure_is_retried(self) -> None:
        failed = FakeConnection(connect_error=TimeoutError())
        body = b'{"repo":{}}'
        succeeded = FakeConnection(
            FakeResponse(200, [("Content-Type", "application/json")], body)
        )
        factory = ConnectionQueue(failed, succeeded)
        delays: list[float] = []
        output = io.StringIO()

        status = preflight.run_preflight(
            connection_factory=factory,
            sleep=delays.append,
            monotonic=lambda: 10.0,
            output=output,
        )

        self.assertEqual(status, 0)
        self.assertEqual(delays, [preflight.RETRY_DELAY_SECONDS])
        self.assertTrue(failed.closed)
        self.assertEqual(len(factory.calls), 2)


if __name__ == "__main__":
    unittest.main()
