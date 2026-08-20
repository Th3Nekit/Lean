from __future__ import annotations

import asyncio
import hashlib
import html
import ipaddress
import json
import logging
import math
import os
import re
import secrets
import sqlite3
import threading
import time
import unicodedata
from collections import deque
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass, field
from pathlib import Path
from typing import Annotated, Callable, Literal

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, RedirectResponse
from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from starlette.middleware import Middleware
from starlette.middleware.httpsredirect import HTTPSRedirectMiddleware
from starlette.middleware.sessions import SessionMiddleware
from starlette.middleware.trustedhost import TrustedHostMiddleware
from starlette.types import ASGIApp, Message, Receive, Scope, Send


SERVICE_VERSION = "1"
SCHEMA_VERSION = 1
SESSION_COOKIE = "lean_crash_session"
REDACTED = "[REDACTED]"
ERROR_LOGGER = logging.getLogger("lean_crash.errors")

LogLine = Annotated[str, StringConstraints(max_length=512, strict=True)]


class ConfigurationError(ValueError):
    pass


class CrashReport(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    schema_version: Literal[1]
    app_version: str = Field(min_length=1, max_length=32)
    exception_type: str = Field(min_length=1, max_length=120)
    message: str = Field(max_length=1_000)
    stack_trace: str = Field(max_length=12_000)
    log_tail: list[LogLine] = Field(default_factory=list, max_length=100)


def _environment_value(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise ConfigurationError(f"Required environment setting is absent: {name}")
    return value


def _environment_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError as error:
        raise ConfigurationError(f"Invalid integer environment setting: {name}") from error


def _environment_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    normalized = raw.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    raise ConfigurationError(f"Invalid boolean environment setting: {name}")


def _environment_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None:
        return default
    try:
        return float(raw)
    except ValueError as error:
        raise ConfigurationError(
            f"Invalid numeric environment setting: {name}"
        ) from error


@dataclass(frozen=True)
class ServerConfig:
    admin_password: str = field(repr=False)
    session_secret: str = field(repr=False)
    allowed_hosts: tuple[str, ...]
    database_path: Path
    production: bool = True
    body_max_bytes: int = 32 * 1024
    rate_limit_count: int = 20
    rate_limit_window_seconds: int = 60
    login_rate_limit_count: int = 5
    retention_count: int = 500
    retention_days: int = 14
    retention_cleanup_interval_seconds: float = 300.0
    panel_page_size: int = 25
    session_max_age_seconds: int = 900
    trust_forwarded_for: bool = False
    trusted_proxy_cidrs: tuple[str, ...] = ()
    clock: Callable[[], float] = field(default=time.time, repr=False, compare=False)
    rate_clock: Callable[[], float] = field(
        default=time.monotonic,
        repr=False,
        compare=False,
    )

    def __post_init__(self) -> None:
        object.__setattr__(self, "database_path", Path(self.database_path))
        object.__setattr__(self, "allowed_hosts", tuple(self.allowed_hosts))
        object.__setattr__(
            self,
            "trusted_proxy_cidrs",
            tuple(self.trusted_proxy_cidrs),
        )
        if len(self.admin_password) < 20 or len(set(self.admin_password)) < 10:
            raise ConfigurationError("Admin password is not strong enough")
        if len(self.session_secret) < 32 or len(set(self.session_secret)) < 16:
            raise ConfigurationError("Session signing secret is not strong enough")
        if not self.allowed_hosts:
            raise ConfigurationError("At least one allowed host is required")
        for host in self.allowed_hosts:
            if (
                not host
                or host == "*"
                or "://" in host
                or "/" in host
                or any(character.isspace() for character in host)
            ):
                raise ConfigurationError("Invalid allowed host")
        if self.trust_forwarded_for and not self.trusted_proxy_cidrs:
            raise ConfigurationError(
                "Forwarded-for mode requires trusted proxy CIDRs"
            )
        for network in self.trusted_proxy_cidrs:
            try:
                ipaddress.ip_network(network, strict=False)
            except ValueError as error:
                raise ConfigurationError("Invalid trusted proxy CIDR") from error
        positive_values = (
            self.body_max_bytes,
            self.rate_limit_count,
            self.rate_limit_window_seconds,
            self.login_rate_limit_count,
            self.retention_count,
            self.retention_days,
            self.retention_cleanup_interval_seconds,
            self.panel_page_size,
            self.session_max_age_seconds,
        )
        if any(
            not math.isfinite(value) or value <= 0
            for value in positive_values
        ):
            raise ConfigurationError("Numeric limits must be positive")
        upper_bounds = (
            (self.body_max_bytes, 1_048_576),
            (self.rate_limit_count, 1_000),
            (self.rate_limit_window_seconds, 3_600),
            (self.login_rate_limit_count, 100),
            (self.retention_count, 10_000),
            (self.retention_days, 365),
            (self.retention_cleanup_interval_seconds, 3_600),
            (self.session_max_age_seconds, 86_400),
        )
        if any(value > maximum for value, maximum in upper_bounds):
            raise ConfigurationError("Numeric limit exceeds safety maximum")
        if self.panel_page_size > 100:
            raise ConfigurationError("Panel page size must not exceed 100")

    @classmethod
    def from_environment(cls) -> "ServerConfig":
        environment = os.environ.get("LEAN_CRASH_ENV", "production").strip().lower()
        if environment not in {"production", "development"}:
            raise ConfigurationError("LEAN_CRASH_ENV must be production or development")
        allowed_hosts = tuple(
            item.strip()
            for item in _environment_value("LEAN_CRASH_ALLOWED_HOSTS").split(",")
            if item.strip()
        )
        database_path = Path(
            os.environ.get(
                "LEAN_CRASH_DATABASE_PATH",
                "/var/lib/lean-crash/crashes.sqlite3",
            )
        )
        return cls(
            admin_password=_environment_value("LEAN_CRASH_ADMIN_PASSWORD"),
            session_secret=_environment_value("LEAN_CRASH_SESSION_SECRET"),
            allowed_hosts=allowed_hosts,
            database_path=database_path,
            production=environment == "production",
            body_max_bytes=_environment_int("LEAN_CRASH_BODY_MAX_BYTES", 32 * 1024),
            rate_limit_count=_environment_int("LEAN_CRASH_RATE_LIMIT_COUNT", 20),
            rate_limit_window_seconds=_environment_int(
                "LEAN_CRASH_RATE_LIMIT_WINDOW_SECONDS", 60
            ),
            login_rate_limit_count=_environment_int(
                "LEAN_CRASH_LOGIN_RATE_LIMIT_COUNT", 5
            ),
            retention_count=_environment_int("LEAN_CRASH_RETENTION_COUNT", 500),
            retention_days=_environment_int("LEAN_CRASH_RETENTION_DAYS", 14),
            retention_cleanup_interval_seconds=_environment_float(
                "LEAN_CRASH_RETENTION_CLEANUP_INTERVAL_SECONDS",
                300.0,
            ),
            panel_page_size=_environment_int("LEAN_CRASH_PANEL_PAGE_SIZE", 25),
            session_max_age_seconds=_environment_int(
                "LEAN_CRASH_SESSION_MAX_AGE_SECONDS", 900
            ),
            trust_forwarded_for=_environment_bool(
                "LEAN_CRASH_TRUST_FORWARDED_FOR", False
            ),
            trusted_proxy_cidrs=tuple(
                item.strip()
                for item in os.environ.get(
                    "LEAN_CRASH_TRUSTED_PROXY_CIDRS",
                    "",
                ).split(",")
                if item.strip()
            ),
        )


_PEM_PRIVATE_KEY = re.compile(
    r"-----BEGIN [^-\r\n]*PRIVATE KEY-----.*?"
    r"(?:-----END [^-\r\n]*PRIVATE KEY-----|\Z)",
    re.IGNORECASE | re.DOTALL,
)
_SENSITIVE_HEADER = re.compile(
    r"(?im)^(?P<prefix>[ \t]*(?:authorization|proxy-authorization|cookie|"
    r"set-cookie)[ \t]*:[ \t]*)[^\r\n]*(?:\n[ \t]+[^\r\n]*)*"
)
_SENSITIVE_KEY = (
    r"password|passwd|pwd|authorization|proxy[-_ ]authorization|cookie|"
    r"set[-_ ]cookie|bearer|token|access[-_ ]token|refresh[-_ ]token|"
    r"api[-_ ]key|apikey|secret|client[-_ ]secret|private[-_ ]key|privatekey|"
    r"preshared[-_ ]key|presharedkey|psk|auth|auth[-_ ]str|uuid|"
    r"subscription|subscription[-_ ]url|sub[-_ ]url"
)
_SENSITIVE_ASSIGNMENT = re.compile(
    rf"(?im)(?P<prefix>(?:^|[\s,{{;])['\"]?(?:{_SENSITIVE_KEY})"
    rf"['\"]?\s*[:=]\s*)[^\r\n]*"
)
_BEARER_VALUE = re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+")
_URI = re.compile(r"(?i)\b[a-z][a-z0-9+.-]*://[^\s<>\"']+")
_UUID = re.compile(
    r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}\b"
)


def redact_text(value: str, maximum_length: int) -> str:
    normalized = value.replace("\r\n", "\n").replace("\r", "\n")
    cleaned = "".join(
        character
        for character in normalized
        if character in {"\n", "\t"}
        or unicodedata.category(character) not in {"Cc", "Cf"}
    )
    cleaned = _PEM_PRIVATE_KEY.sub(REDACTED, cleaned)
    cleaned = _SENSITIVE_HEADER.sub(
        lambda match: f"{match.group('prefix')}{REDACTED}",
        cleaned,
    )
    cleaned = _SENSITIVE_ASSIGNMENT.sub(
        lambda match: f"{match.group('prefix')}{REDACTED}",
        cleaned,
    )
    cleaned = _BEARER_VALUE.sub(f"Bearer {REDACTED}", cleaned)
    cleaned = _URI.sub("[REDACTED_URI]", cleaned)
    cleaned = _UUID.sub(REDACTED, cleaned)
    return cleaned[:maximum_length]


def _redacted_report(report: CrashReport) -> dict[str, object]:
    retained_logs: list[str] = []
    retained_characters = 0
    for raw_line in report.log_tail[:50]:
        line = redact_text(raw_line, 512)
        remaining = 8_000 - retained_characters
        if remaining <= 0:
            break
        retained = line[:remaining]
        retained_logs.append(retained)
        retained_characters += len(retained)
    return {
        "app_version": redact_text(report.app_version, 32),
        "exception_type": redact_text(report.exception_type, 120),
        "message": redact_text(report.message, 1_000),
        "stack_trace": redact_text(report.stack_trace, 12_000),
        "log_tail": retained_logs,
    }


class CrashStore:
    def __init__(self, config: ServerConfig) -> None:
        self._path = config.database_path
        self._retention_count = config.retention_count
        self._retention_seconds = config.retention_days * 86_400
        self._clock = config.clock
        self._lock = threading.Lock()
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self._path, timeout=5)
        connection.execute("PRAGMA busy_timeout = 5000")
        return connection

    def _initialize(self) -> None:
        with self._lock, self._connect() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS reports (
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    report_id TEXT NOT NULL UNIQUE,
                    received_at INTEGER NOT NULL,
                    app_version TEXT NOT NULL,
                    exception_type TEXT NOT NULL,
                    message TEXT NOT NULL,
                    stack_trace TEXT NOT NULL,
                    log_tail TEXT NOT NULL
                )
                """
            )
            self._cleanup(connection, int(self._clock()))

    def _cleanup(self, connection: sqlite3.Connection, now: int) -> None:
        cutoff = now - self._retention_seconds
        connection.execute(
            "DELETE FROM reports WHERE received_at < ?",
            (cutoff,),
        )
        connection.execute(
            """
            DELETE FROM reports
            WHERE sequence NOT IN (
                SELECT sequence FROM reports
                ORDER BY received_at DESC, sequence DESC
                LIMIT ?
            )
            """,
            (self._retention_count,),
        )

    def save(self, report: CrashReport) -> str:
        redacted = _redacted_report(report)
        now = int(self._clock())
        for _ in range(3):
            report_id = secrets.token_urlsafe(9)
            try:
                with self._lock, self._connect() as connection:
                    connection.execute("BEGIN IMMEDIATE")
                    self._cleanup(connection, now)
                    connection.execute(
                        """
                        INSERT INTO reports (
                            report_id, received_at, app_version, exception_type,
                            message, stack_trace, log_tail
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            report_id,
                            now,
                            redacted["app_version"],
                            redacted["exception_type"],
                            redacted["message"],
                            redacted["stack_trace"],
                            json.dumps(
                                redacted["log_tail"],
                                ensure_ascii=False,
                                separators=(",", ":"),
                            ),
                        ),
                    )
                    self._cleanup(connection, now)
                    connection.commit()
                return report_id
            except sqlite3.IntegrityError:
                continue
        raise RuntimeError("Unable to allocate report ID")

    def page(self, page_number: int, page_size: int) -> list[dict[str, object]]:
        now = int(self._clock())
        offset = (page_number - 1) * page_size
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._cleanup(connection, now)
            rows = connection.execute(
                """
                SELECT report_id, received_at, app_version, exception_type,
                       message, stack_trace, log_tail
                FROM reports
                ORDER BY received_at DESC, sequence DESC
                LIMIT ? OFFSET ?
                """,
                (page_size, offset),
            ).fetchall()
            connection.commit()
        result: list[dict[str, object]] = []
        for row in rows:
            try:
                logs = json.loads(row[6])
            except (TypeError, ValueError):
                logs = []
            result.append(
                {
                    "report_id": row[0],
                    "received_at": row[1],
                    "app_version": row[2],
                    "exception_type": row[3],
                    "message": row[4],
                    "stack_trace": row[5],
                    "log_tail": logs if isinstance(logs, list) else [],
                }
            )
        return result

    def clear(self) -> None:
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("DELETE FROM reports")
            connection.commit()

    def enforce_retention(self) -> None:
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._cleanup(connection, int(self._clock()))
            connection.commit()


class SlidingWindowLimiter:
    _MAX_BUCKETS = 4_096

    def __init__(
        self,
        limit: int,
        window_seconds: int,
        clock: Callable[[], float],
    ) -> None:
        self._limit = limit
        self._window = window_seconds
        self._clock = clock
        self._salt = os.urandom(16)
        self._buckets: dict[str, deque[float]] = {}
        self._next_sweep_at = float("-inf")
        self._lock = threading.Lock()

    def allow(self, origin: str) -> bool:
        now = self._clock()
        origin_key = hashlib.blake2s(
            origin.encode("utf-8", errors="replace"),
            key=self._salt,
            digest_size=16,
        ).hexdigest()
        cutoff = now - self._window
        with self._lock:
            bucket = self._buckets.get(origin_key)
            if bucket is None:
                if now >= self._next_sweep_at:
                    stale = [
                        key
                        for key, values in self._buckets.items()
                        if not values or values[-1] <= cutoff
                    ]
                    for key in stale:
                        self._buckets.pop(key, None)
                    self._next_sweep_at = now + self._window
                if len(self._buckets) >= self._MAX_BUCKETS:
                    return False
                bucket = deque()
                self._buckets[origin_key] = bucket
            while bucket and bucket[0] <= cutoff:
                bucket.popleft()
            if len(bucket) >= self._limit:
                return False
            bucket.append(now)
            return True


class SessionStore:
    _MAX_SESSIONS = 256

    def __init__(self, config: ServerConfig) -> None:
        self._path = config.database_path
        self._clock = config.clock
        self._hash_key = hashlib.sha256(
            config.session_secret.encode(
                "utf-8",
                errors="surrogatepass",
            )
        ).digest()
        self._lock = threading.Lock()
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self._path, timeout=5)
        connection.execute("PRAGMA busy_timeout = 5000")
        return connection

    def _initialize(self) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS panel_sessions (
                    session_key TEXT PRIMARY KEY,
                    expires_at INTEGER NOT NULL
                )
                """
            )
            self._purge_expired(connection, int(self._clock()))

    def create(self, expires_at: float) -> str:
        now = int(self._clock())
        for _ in range(3):
            session_id = secrets.token_urlsafe(32)
            session_key = self._key(session_id)
            try:
                with self._lock, self._connect() as connection:
                    connection.execute("BEGIN IMMEDIATE")
                    self._purge_expired(connection, now)
                    connection.execute(
                        """
                        INSERT INTO panel_sessions (session_key, expires_at)
                        VALUES (?, ?)
                        """,
                        (session_key, int(expires_at)),
                    )
                    connection.execute(
                        """
                        DELETE FROM panel_sessions
                        WHERE session_key NOT IN (
                            SELECT session_key FROM panel_sessions
                            ORDER BY expires_at DESC, session_key DESC
                            LIMIT ?
                        )
                        """,
                        (self._MAX_SESSIONS,),
                    )
                    connection.commit()
                return session_id
            except sqlite3.IntegrityError:
                continue
        raise RuntimeError("Unable to allocate session ID")

    def active(self, session_id: object) -> bool:
        if not isinstance(session_id, str) or len(session_id) < 32:
            return False
        session_key = self._key(session_id)
        now = int(self._clock())
        with self._lock, self._connect() as connection:
            row = connection.execute(
                """
                SELECT expires_at FROM panel_sessions
                WHERE session_key = ?
                """,
                (session_key,),
            ).fetchone()
            if row is None:
                return False
            if row[0] <= now:
                connection.execute(
                    "DELETE FROM panel_sessions WHERE session_key = ?",
                    (session_key,),
                )
                connection.commit()
                return False
        return True

    def revoke(self, session_id: object) -> None:
        if not isinstance(session_id, str):
            return
        with self._lock, self._connect() as connection:
            connection.execute(
                "DELETE FROM panel_sessions WHERE session_key = ?",
                (self._key(session_id),),
            )
            connection.commit()

    def cleanup_expired(self) -> None:
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._purge_expired(connection, int(self._clock()))
            connection.commit()

    @staticmethod
    def _purge_expired(
        connection: sqlite3.Connection,
        now: int,
    ) -> None:
        connection.execute(
            "DELETE FROM panel_sessions WHERE expires_at <= ?",
            (now,),
        )

    def _key(self, session_id: str) -> str:
        return hashlib.blake2s(
            session_id.encode("utf-8", errors="replace"),
            key=self._hash_key,
            digest_size=16,
        ).hexdigest()


class CrashRateLimitMiddleware:
    def __init__(
        self,
        app: ASGIApp,
        limiter: SlidingWindowLimiter,
        trust_forwarded_for: bool,
        trusted_proxy_cidrs: tuple[str, ...],
    ) -> None:
        self.app = app
        self.limiter = limiter
        self.trust_forwarded_for = trust_forwarded_for
        self.trusted_proxy_networks = tuple(
            ipaddress.ip_network(network, strict=False)
            for network in trusted_proxy_cidrs
        )

    async def __call__(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
    ) -> None:
        if (
            scope["type"] == "http"
            and scope.get("method") == "POST"
            and scope.get("path") == "/lean/crash"
            and not self.limiter.allow(
                _scope_origin(
                    scope,
                    self.trust_forwarded_for,
                    self.trusted_proxy_networks,
                )
            )
        ):
            response = JSONResponse(
                {"detail": "Too many reports"},
                status_code=429,
            )
            await response(scope, receive, send)
            return
        await self.app(scope, receive, send)


class CrashMediaTypeMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
    ) -> None:
        if (
            scope["type"] == "http"
            and scope.get("method") == "POST"
            and scope.get("path") == "/lean/crash"
        ):
            content_type = ""
            for key, value in scope.get("headers", []):
                if key.lower() == b"content-type":
                    content_type = value.decode("latin-1")
                    break
            media_type = content_type.split(";", 1)[0].strip().lower()
            if (
                media_type != "application/json"
                and not media_type.endswith("+json")
            ):
                response = JSONResponse(
                    {"detail": "Unsupported media type"},
                    status_code=415,
                )
                await response(scope, receive, send)
                return
        await self.app(scope, receive, send)


class BodyLimitMiddleware:
    def __init__(self, app: ASGIApp, maximum_bytes: int) -> None:
        self.app = app
        self.maximum_bytes = maximum_bytes

    async def __call__(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
    ) -> None:
        if scope["type"] != "http" or scope.get("method") not in {
            "POST",
            "PUT",
            "PATCH",
        }:
            await self.app(scope, receive, send)
            return
        headers = {
            key.lower(): value
            for key, value in scope.get("headers", [])
        }
        content_length = headers.get(b"content-length")
        if content_length is not None:
            try:
                declared_length = int(content_length)
            except ValueError:
                await self._error(scope, receive, send, 400, "Invalid request body")
                return
            if declared_length < 0:
                await self._error(scope, receive, send, 400, "Invalid request body")
                return
            if declared_length > self.maximum_bytes:
                await self._error(
                    scope,
                    receive,
                    send,
                    413,
                    "Request body too large",
                )
                return

        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                await self.app(scope, _single_message_receive(message), send)
                return
            if message["type"] != "http.request":
                continue
            chunk = message.get("body", b"")
            if len(body) + len(chunk) > self.maximum_bytes:
                await self._error(
                    scope,
                    receive,
                    send,
                    413,
                    "Request body too large",
                )
                return
            body.extend(chunk)
            if not message.get("more_body", False):
                break

        await self.app(
            scope,
            _single_message_receive(
                {
                    "type": "http.request",
                    "body": bytes(body),
                    "more_body": False,
                }
            ),
            send,
        )

    @staticmethod
    async def _error(
        scope: Scope,
        receive: Receive,
        send: Send,
        status_code: int,
        detail: str,
    ) -> None:
        response = JSONResponse({"detail": detail}, status_code=status_code)
        await response(scope, receive, send)


def _single_message_receive(message: Message) -> Receive:
    delivered = False

    async def receive() -> Message:
        nonlocal delivered
        if not delivered:
            delivered = True
            return message
        return {"type": "http.disconnect"}

    return receive


class SecurityHeadersMiddleware:
    def __init__(self, app: ASGIApp, production: bool) -> None:
        self.app = app
        self.production = production

    async def __call__(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
    ) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        async def send_with_headers(message: Message) -> None:
            if message["type"] == "http.response.start":
                _add_security_headers(message, scope, self.production)
            await send(message)

        await self.app(scope, receive, send_with_headers)


class ErrorBoundaryMiddleware:
    def __init__(self, app: ASGIApp, production: bool) -> None:
        self.app = app
        self.production = production

    async def __call__(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
    ) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        response_started = False

        async def guarded_send(message: Message) -> None:
            nonlocal response_started
            if message["type"] == "http.response.start":
                _add_security_headers(message, scope, self.production)
                response_started = True
            await send(message)

        try:
            await self.app(scope, receive, guarded_send)
        except Exception:
            event_id = secrets.token_hex(8)
            ERROR_LOGGER.error("Unhandled server error event_id=%s", event_id)
            if not response_started:
                response = JSONResponse(
                    {"detail": "Service unavailable"},
                    status_code=503,
                )
                await response(scope, receive, guarded_send)


def _add_security_headers(
    message: Message,
    scope: Scope,
    production: bool,
) -> None:
    headers = list(message.get("headers", []))
    names = {key.lower() for key, _ in headers}
    defensive_headers = {
        b"content-security-policy": (
            b"default-src 'none'; style-src 'self'; img-src 'self'; "
            b"form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
        ),
        b"x-content-type-options": b"nosniff",
        b"x-frame-options": b"DENY",
        b"referrer-policy": b"no-referrer",
        b"permissions-policy": b"camera=(), microphone=(), geolocation=()",
        b"cache-control": b"no-store",
        b"pragma": b"no-cache",
    }
    if production and scope.get("scheme") == "https":
        defensive_headers[b"strict-transport-security"] = (
            b"max-age=31536000; includeSubDomains"
        )
    for key, value in defensive_headers.items():
        if key not in names:
            headers.append((key, value))
    message["headers"] = headers


def _scope_origin(
    scope: Scope,
    trust_forwarded_for: bool,
    trusted_proxy_networks: tuple[
        ipaddress.IPv4Network | ipaddress.IPv6Network,
        ...,
    ] = (),
) -> str:
    client = scope.get("client")
    immediate_peer = str(client[0]) if client else "unknown"
    try:
        peer_address = ipaddress.ip_address(immediate_peer)
    except ValueError:
        peer_address = None
    peer_is_trusted = peer_address is not None and any(
        peer_address in network
        for network in trusted_proxy_networks
    )
    if trust_forwarded_for and peer_is_trusted:
        forwarded = ""
        for key, value in scope.get("headers", []):
            if key.lower() == b"x-forwarded-for":
                forwarded = value.decode("latin-1")
                break
        first = forwarded.split(",", 1)[0].strip()
        try:
            return str(ipaddress.ip_address(first))
        except ValueError:
            pass
    return immediate_peer


def _client_origin(
    request: Request,
    trust_forwarded_for: bool,
    trusted_proxy_networks: tuple[
        ipaddress.IPv4Network | ipaddress.IPv6Network,
        ...,
    ],
) -> str:
    return _scope_origin(
        request.scope,
        trust_forwarded_for,
        trusted_proxy_networks,
    )


def _constant_match(submitted: object, expected: object) -> bool:
    if not isinstance(submitted, str) or not isinstance(expected, str):
        return False
    return secrets.compare_digest(
        submitted.encode("utf-8", errors="surrogatepass"),
        expected.encode("utf-8", errors="surrogatepass"),
    )


def _document(title: str, content: str) -> str:
    return (
        "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        f"<title>{html.escape(title, quote=True)}</title></head><body>"
        f"{content}</body></html>"
    )


def _login_document(csrf_token: str) -> str:
    csrf = html.escape(csrf_token, quote=True)
    return _document(
        "Lean Crash Diagnostics",
        "<main><h1>Lean Crash Diagnostics</h1>"
        "<form method=\"post\" action=\"/lean/login\">"
        "<label>Пароль <input type=\"password\" name=\"password\" "
        "autocomplete=\"current-password\" required></label>"
        f"<input type=\"hidden\" name=\"csrf_token\" value=\"{csrf}\">"
        "<button type=\"submit\">Войти</button></form></main>",
    )


def _panel_document(
    reports: list[dict[str, object]],
    csrf_token: str,
    page_number: int,
    page_size: int,
) -> str:
    csrf = html.escape(csrf_token, quote=True)
    blocks: list[str] = []
    for report in reports:
        logs = report.get("log_tail", [])
        log_text = "\n".join(str(item) for item in logs if isinstance(item, str))
        blocks.append(
            "<article>"
            f"<h2>{html.escape(str(report['exception_type']), quote=True)}</h2>"
            f"<p>Report: {html.escape(str(report['report_id']), quote=True)}</p>"
            f"<p>Received: {html.escape(str(report['received_at']), quote=True)}</p>"
            f"<p>App: {html.escape(str(report['app_version']), quote=True)}</p>"
            f"<pre>{html.escape(str(report['message']), quote=True)}</pre>"
            f"<pre>{html.escape(str(report['stack_trace']), quote=True)}</pre>"
            f"<pre>{html.escape(log_text, quote=True)}</pre>"
            "</article>"
        )
    if not blocks:
        blocks.append("<p>Нет сохранённых отчётов.</p>")
    navigation = ""
    if page_number > 1:
        navigation += f'<a href="/lean/panel?page={page_number - 1}">Назад</a> '
    if len(reports) == page_size:
        navigation += f'<a href="/lean/panel?page={page_number + 1}">Далее</a>'
    actions = (
        "<form method=\"post\" action=\"/lean/panel/clear\">"
        f"<input type=\"hidden\" name=\"csrf_token\" value=\"{csrf}\">"
        "<button type=\"submit\">Удалить все отчёты</button></form>"
        "<form method=\"post\" action=\"/lean/logout\">"
        f"<input type=\"hidden\" name=\"csrf_token\" value=\"{csrf}\">"
        "<button type=\"submit\">Выйти</button></form>"
    )
    return _document(
        "Lean Crash Diagnostics",
        "<main><h1>Lean Crash Diagnostics</h1>"
        f"{actions}{navigation}{''.join(blocks)}</main>",
    )


def create_app(config: ServerConfig | None = None) -> ASGIApp:
    active_config = config or ServerConfig.from_environment()
    trusted_proxy_networks = tuple(
        ipaddress.ip_network(network, strict=False)
        for network in active_config.trusted_proxy_cidrs
    )
    try:
        store = CrashStore(active_config)
        session_registry = SessionStore(active_config)
    except (OSError, sqlite3.Error):
        raise ConfigurationError("Unable to initialize crash store") from None
    report_limiter = SlidingWindowLimiter(
        active_config.rate_limit_count,
        active_config.rate_limit_window_seconds,
        active_config.rate_clock,
    )
    login_limiter = SlidingWindowLimiter(
        active_config.login_rate_limit_count,
        active_config.rate_limit_window_seconds,
        active_config.rate_clock,
    )

    async def retention_loop() -> None:
        while True:
            await asyncio.sleep(
                active_config.retention_cleanup_interval_seconds
            )
            try:
                await asyncio.to_thread(store.enforce_retention)
                await asyncio.to_thread(session_registry.cleanup_expired)
            except Exception:
                event_id = secrets.token_hex(8)
                ERROR_LOGGER.error(
                    "Retention cleanup failed event_id=%s",
                    event_id,
                )

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        cleanup_task = asyncio.create_task(retention_loop())
        try:
            yield
        finally:
            cleanup_task.cancel()
            with suppress(asyncio.CancelledError):
                await cleanup_task

    middleware: list[Middleware] = [
        Middleware(
            TrustedHostMiddleware,
            allowed_hosts=list(active_config.allowed_hosts),
        )
    ]
    if active_config.production:
        middleware.append(Middleware(HTTPSRedirectMiddleware))
    middleware.extend(
        [
            Middleware(
                SecurityHeadersMiddleware,
                production=active_config.production,
            ),
            Middleware(
                CrashRateLimitMiddleware,
                limiter=report_limiter,
                trust_forwarded_for=active_config.trust_forwarded_for,
                trusted_proxy_cidrs=active_config.trusted_proxy_cidrs,
            ),
            Middleware(CrashMediaTypeMiddleware),
            Middleware(
                BodyLimitMiddleware,
                maximum_bytes=active_config.body_max_bytes,
            ),
            Middleware(
                SessionMiddleware,
                secret_key=active_config.session_secret,
                session_cookie=SESSION_COOKIE,
                max_age=active_config.session_max_age_seconds,
                path="/lean",
                same_site="strict",
                https_only=True,
            ),
        ]
    )
    app = FastAPI(
        title="Lean Crash Diagnostics",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        middleware=middleware,
        lifespan=lifespan,
    )

    def authenticated(request: Request) -> bool:
        session = request.session
        expires_at = session.get("expires_at")
        csrf_token = session.get("csrf_token")
        session_id = session.get("session_id")
        if (
            session.get("authenticated") is not True
            or not isinstance(expires_at, (int, float))
            or expires_at <= active_config.clock()
            or not isinstance(csrf_token, str)
            or len(csrf_token) < 32
            or not session_registry.active(session_id)
        ):
            session_registry.revoke(session_id)
            session.clear()
            return False
        return True

    def invalidate_session(request: Request) -> None:
        session_registry.revoke(request.session.get("session_id"))
        request.session.clear()

    def csrf_matches(request: Request, submitted: object) -> bool:
        return _constant_match(submitted, request.session.get("csrf_token"))

    @app.exception_handler(RequestValidationError)
    async def validation_error(
        _request: Request,
        error: RequestValidationError,
    ) -> JSONResponse:
        malformed = any(item.get("type") == "json_invalid" for item in error.errors())
        return JSONResponse(
            {"detail": "Malformed JSON" if malformed else "Invalid report"},
            status_code=400 if malformed else 422,
        )

    @app.exception_handler(Exception)
    async def unexpected_error(_request: Request, _error: Exception) -> JSONResponse:
        return JSONResponse(
            {"detail": "Service unavailable"},
            status_code=503,
        )

    @app.get("/lean/health")
    async def health() -> dict[str, str]:
        return {
            "status": "ok",
            "service": "lean-crash",
            "version": SERVICE_VERSION,
        }

    @app.post("/lean/crash", status_code=202)
    async def submit_crash(report: CrashReport, request: Request) -> dict[str, str]:
        media_type = request.headers.get("content-type", "").split(";", 1)[0].lower()
        if media_type != "application/json" and not media_type.endswith("+json"):
            raise HTTPException(status_code=415, detail="Unsupported media type")
        try:
            report_id = store.save(report)
        except (OSError, sqlite3.Error, RuntimeError):
            raise HTTPException(status_code=503, detail="Service unavailable") from None
        return {"report_id": report_id, "status": "accepted"}

    @app.get("/lean/login", response_class=HTMLResponse)
    async def login_page(request: Request) -> HTMLResponse:
        invalidate_session(request)
        login_csrf = secrets.token_urlsafe(32)
        request.session["login_csrf"] = login_csrf
        return HTMLResponse(_login_document(login_csrf))

    @app.post("/lean/login")
    async def login_submit(request: Request):
        origin = _client_origin(
            request,
            active_config.trust_forwarded_for,
            trusted_proxy_networks,
        )
        if not login_limiter.allow(origin):
            invalidate_session(request)
            return PlainTextResponse("Invalid credentials", status_code=429)
        form = await request.form()
        submitted_csrf = form.get("csrf_token")
        expected_csrf = request.session.get("login_csrf")
        if not _constant_match(submitted_csrf, expected_csrf):
            invalidate_session(request)
            return PlainTextResponse("Forbidden", status_code=403)
        submitted_password = form.get("password")
        invalidate_session(request)
        if not _constant_match(submitted_password, active_config.admin_password):
            replacement_csrf = secrets.token_urlsafe(32)
            request.session["login_csrf"] = replacement_csrf
            return PlainTextResponse("Invalid credentials", status_code=401)
        expires_at = (
            int(active_config.clock())
            + active_config.session_max_age_seconds
        )
        request.session.update(
            {
                "authenticated": True,
                "csrf_token": secrets.token_urlsafe(32),
                "expires_at": expires_at,
                "session_id": session_registry.create(expires_at),
            }
        )
        return RedirectResponse("/lean/panel", status_code=303)

    @app.get("/lean/panel", response_class=HTMLResponse)
    async def panel(
        request: Request,
        page: int = Query(default=1, ge=1, le=10_000),
    ):
        if not authenticated(request):
            return RedirectResponse("/lean/login", status_code=303)
        try:
            reports = store.page(page, active_config.panel_page_size)
        except (OSError, sqlite3.Error):
            raise HTTPException(status_code=503, detail="Service unavailable") from None
        return HTMLResponse(
            _panel_document(
                reports,
                request.session["csrf_token"],
                page,
                active_config.panel_page_size,
            )
        )

    @app.post("/lean/panel/clear")
    async def clear_reports(request: Request):
        if not authenticated(request):
            return RedirectResponse("/lean/login", status_code=303)
        form = await request.form()
        if not csrf_matches(request, form.get("csrf_token")):
            return PlainTextResponse("Forbidden", status_code=403)
        try:
            store.clear()
        except (OSError, sqlite3.Error):
            raise HTTPException(status_code=503, detail="Service unavailable") from None
        request.session["csrf_token"] = secrets.token_urlsafe(32)
        return RedirectResponse("/lean/panel", status_code=303)

    @app.post("/lean/logout")
    async def logout(request: Request):
        if not authenticated(request):
            invalidate_session(request)
            return RedirectResponse("/lean/login", status_code=303)
        form = await request.form()
        if not csrf_matches(request, form.get("csrf_token")):
            return PlainTextResponse("Forbidden", status_code=403)
        invalidate_session(request)
        return RedirectResponse("/lean/login", status_code=303)

    return ErrorBoundaryMiddleware(
        app,
        production=active_config.production,
    )
