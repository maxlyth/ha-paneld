"""Probe the public F-Droid origin without sending deployment credentials."""

from __future__ import annotations

import hashlib
import http.client
import json
import socket
import ssl
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol, TextIO

HOST = "fdroid.ha-paneld.com"
PATH = "/fdroid/repo/index-v1.json"
CONNECT_TIMEOUT_SECONDS = 8.0
ATTEMPT_TIMEOUT_SECONDS = 15.0
RETRY_DELAY_SECONDS = 2.0
MAX_ATTEMPTS = 3
MAX_HEADER_BYTES = 32 * 1024
MAX_BODY_BYTES = 1024 * 1024
READ_CHUNK_BYTES = 64 * 1024
MAX_CONTENT_LENGTH_DIGITS = len(str(MAX_BODY_BYTES))
MAX_DIAGNOSTIC_HEADER_VALUE_CHARS = 256

REQUEST_HEADERS = {
    "Accept": "application/json",
    "Connection": "close",
    "User-Agent": "ha-paneld-fdroid-origin-preflight/1",
}
DIAGNOSTIC_HEADERS = (
    "content-type",
    "content-length",
    "server",
    "cf-cache-status",
    "cf-mitigated",
    "cf-ray",
    "retry-after",
)
RETRYABLE_STATUSES = frozenset({408, 425, 429, 500, 502, 503, 504})


class Response(Protocol):
    status: int

    def getheaders(self) -> list[tuple[str, str]]: ...

    def read(self, amount: int | None = None) -> bytes: ...


class Connection(Protocol):
    sock: socket.socket | None

    def connect(self) -> None: ...

    def request(
        self,
        method: str,
        url: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> None: ...

    def getresponse(self) -> Response: ...

    def close(self) -> None: ...


ConnectionFactory = Callable[..., Connection]


@dataclass(frozen=True)
class ProbeResult:
    status: int | None
    headers: tuple[tuple[str, str], ...] = ()
    body_length: int = 0
    body_sha256: str = hashlib.sha256(b"").hexdigest()
    error_code: str | None = None

    @property
    def successful(self) -> bool:
        return self.status == 200 and self.error_code is None


def _remaining_timeout(deadline: float, monotonic: Callable[[], float]) -> float:
    remaining = deadline - monotonic()
    if remaining <= 0:
        raise TimeoutError
    return remaining


def _set_socket_timeout(
    connection: Connection, deadline: float, monotonic: Callable[[], float]
) -> None:
    if connection.sock is not None:
        connection.sock.settimeout(_remaining_timeout(deadline, monotonic))


def _header_size(headers: list[tuple[str, str]]) -> int:
    return sum(
        len(name.encode("utf-8", "replace")) + len(value.encode("utf-8", "replace")) + 4
        for name, value in headers
    )


def _declared_body_error(headers: list[tuple[str, str]]) -> str | None:
    values = [
        value.strip() for name, value in headers if name.lower() == "content-length"
    ]
    if not values:
        return None
    if any(
        not value.isascii()
        or not value.isdigit()
        or len(value) > MAX_CONTENT_LENGTH_DIGITS
        for value in values
    ):
        return "invalid_content_length"
    lengths = {int(value) for value in values}
    if len(lengths) != 1:
        return "invalid_content_length"
    if next(iter(lengths)) > MAX_BODY_BYTES:
        return "body_too_large"
    return None


def _read_bounded_body(
    response: Response,
    connection: Connection,
    deadline: float,
    monotonic: Callable[[], float],
) -> tuple[bytes, bool]:
    body = bytearray()
    while len(body) <= MAX_BODY_BYTES:
        _set_socket_timeout(connection, deadline, monotonic)
        remaining = MAX_BODY_BYTES + 1 - len(body)
        chunk = response.read(min(READ_CHUNK_BYTES, remaining))
        if not chunk:
            break
        body.extend(chunk)
    return bytes(body), len(body) > MAX_BODY_BYTES


def _body_identity(body: bytes) -> tuple[int, str]:
    return len(body), hashlib.sha256(body).hexdigest()


def probe_once(
    connection_factory: ConnectionFactory = http.client.HTTPSConnection,
    monotonic: Callable[[], float] = time.monotonic,
) -> ProbeResult:
    """Perform one bounded anonymous request and return non-sensitive diagnostics."""
    deadline = monotonic() + ATTEMPT_TIMEOUT_SECONDS
    connection = connection_factory(
        HOST,
        443,
        timeout=min(CONNECT_TIMEOUT_SECONDS, _remaining_timeout(deadline, monotonic)),
    )
    try:
        connection.connect()
        _set_socket_timeout(connection, deadline, monotonic)
        connection.request("GET", PATH, body=None, headers=REQUEST_HEADERS)
        _set_socket_timeout(connection, deadline, monotonic)
        response = connection.getresponse()
        headers = response.getheaders()
        status = response.status

        if _header_size(headers) > MAX_HEADER_BYTES:
            return ProbeResult(
                status=status, headers=tuple(headers), error_code="headers_too_large"
            )
        if body_error := _declared_body_error(headers):
            return ProbeResult(
                status=status, headers=tuple(headers), error_code=body_error
            )

        body, excessive = _read_bounded_body(response, connection, deadline, monotonic)
        body_length, body_sha256 = _body_identity(body)
        if excessive:
            return ProbeResult(
                status=status,
                headers=tuple(headers),
                body_length=body_length,
                body_sha256=body_sha256,
                error_code="body_too_large",
            )
        if status != 200:
            return ProbeResult(
                status=status,
                headers=tuple(headers),
                body_length=body_length,
                body_sha256=body_sha256,
                error_code="http_status",
            )

        content_types = [
            value.split(";", 1)[0].strip().lower()
            for name, value in headers
            if name.lower() == "content-type"
        ]
        if content_types != ["application/json"]:
            return ProbeResult(
                status=status,
                headers=tuple(headers),
                body_length=body_length,
                body_sha256=body_sha256,
                error_code="invalid_content_type",
            )
        try:
            document = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, RecursionError, ValueError):
            return ProbeResult(
                status=status,
                headers=tuple(headers),
                body_length=body_length,
                body_sha256=body_sha256,
                error_code="invalid_json",
            )
        if not isinstance(document, dict):
            return ProbeResult(
                status=status,
                headers=tuple(headers),
                body_length=body_length,
                body_sha256=body_sha256,
                error_code="invalid_json_shape",
            )
        return ProbeResult(
            status=status,
            headers=tuple(headers),
            body_length=body_length,
            body_sha256=body_sha256,
        )
    finally:
        connection.close()


def _safe_header_value(value: str) -> str:
    normalized = " ".join(value[:MAX_DIAGNOSTIC_HEADER_VALUE_CHARS].split())
    printable = "".join(
        character if character.isprintable() else "?" for character in normalized
    )
    return printable


def _write_diagnostics(result: ProbeResult, output: TextIO) -> None:
    status = str(result.status) if result.status is not None else "transport_error"
    print(f"F-Droid public origin preflight: status={status}", file=output)
    lower_headers = [(name.lower(), value) for name, value in result.headers]
    for wanted in DIAGNOSTIC_HEADERS:
        for name, value in lower_headers:
            if name == wanted:
                print(
                    f"F-Droid public origin header: {wanted}={_safe_header_value(value)}",
                    file=output,
                )
    if result.error_code is not None:
        print(f"F-Droid public origin error: code={result.error_code}", file=output)
        if result.status is not None:
            print(
                "F-Droid public origin error: "
                f"body_bytes={result.body_length} body_sha256={result.body_sha256}",
                file=output,
            )


def run_preflight(
    connection_factory: ConnectionFactory = http.client.HTTPSConnection,
    sleep: Callable[[float], None] = time.sleep,
    monotonic: Callable[[], float] = time.monotonic,
    output: TextIO = sys.stdout,
) -> int:
    """Retry transient failures, then emit only bounded allowlisted diagnostics."""
    result: ProbeResult | None = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            result = probe_once(connection_factory, monotonic)
        except (
            http.client.HTTPException,
            OSError,
            TimeoutError,
            ssl.SSLError,
        ) as error:
            if isinstance(error, (TimeoutError, socket.timeout)):
                code = "timeout"
            else:
                code = "transport_error"
            result = ProbeResult(status=None, error_code=code)

        retryable = result.status in RETRYABLE_STATUSES or result.status is None
        if result.successful or not retryable or attempt == MAX_ATTEMPTS:
            break
        sleep(RETRY_DELAY_SECONDS)

    assert result is not None
    _write_diagnostics(result, output)
    return 0 if result.successful else 1


if __name__ == "__main__":
    raise SystemExit(run_preflight())
