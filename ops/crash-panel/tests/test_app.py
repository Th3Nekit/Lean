from __future__ import annotations

import importlib.util
import json
import logging
import re
import sqlite3
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient


MODULE_PATH = Path(__file__).parents[1] / "app.py"
PROJECT_PATH = MODULE_PATH.parent
ADMIN_PASSWORD = "Correct-Horse-Battery-Staple-729!"
SESSION_SECRET = "Yp9v3dE7sQ2mL8xK5cN1rT6wA4uF0hJzB7gV9eD3"


@dataclass
class MutableClock:
    value: float = 1_750_000_000.0

    def __call__(self) -> float:
        return self.value

    def advance(self, seconds: float) -> None:
        self.value += seconds


class MissingServerModule:
    def __getattr__(self, name: str):
        pytest.fail(f"server feature is not implemented: missing {name}")


@pytest.fixture(scope="session")
def server_module():
    if not MODULE_PATH.is_file():
        return MissingServerModule()
    spec = importlib.util.spec_from_file_location("lean_crash_panel_app", MODULE_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def make_config(server_module, tmp_path: Path, **overrides: Any):
    values = {
        "admin_password": ADMIN_PASSWORD,
        "session_secret": SESSION_SECRET,
        "allowed_hosts": ("testserver",),
        "database_path": tmp_path / "crashes.sqlite3",
        "production": False,
        "body_max_bytes": 32 * 1024,
        "rate_limit_count": 100,
        "rate_limit_window_seconds": 60,
        "login_rate_limit_count": 20,
        "retention_count": 100,
        "retention_days": 30,
        "panel_page_size": 25,
        "session_max_age_seconds": 900,
        "trust_forwarded_for": False,
        "clock": MutableClock(),
    }
    values.update(overrides)
    return server_module.ServerConfig(**values)


def make_payload(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "schema_version": 1,
        "app_version": "0.9.4",
        "exception_type": "IllegalStateException",
        "message": "VPN core stopped unexpectedly",
        "stack_trace": "IllegalStateException: VPN core stopped unexpectedly\n  at lean.Core.start",
        "log_tail": ["service start", "core stopped"],
    }
    payload.update(overrides)
    return payload


def make_client(server_module, tmp_path: Path, **config_overrides: Any):
    config = make_config(server_module, tmp_path, **config_overrides)
    app = server_module.create_app(config)
    return TestClient(app, base_url="https://testserver"), config


def test_runtime_requirements_exclude_test_tooling():
    runtime = (PROJECT_PATH / "requirements.txt").read_text(encoding="utf-8")
    development_path = PROJECT_PATH / "requirements-dev.txt"

    assert development_path.is_file()
    development = development_path.read_text(encoding="utf-8")
    assert "pytest" not in runtime
    assert "httpx" not in runtime
    assert "-r requirements.txt" in development
    assert "pytest==" in development
    assert "httpx==" in development


def report_count(database_path: Path) -> int:
    with sqlite3.connect(database_path) as connection:
        return connection.execute("SELECT COUNT(*) FROM reports").fetchone()[0]


def database_files_bytes(database_path: Path) -> bytes:
    return b"".join(
        candidate.read_bytes()
        for candidate in (
            database_path,
            Path(f"{database_path}-wal"),
            Path(f"{database_path}-shm"),
        )
        if candidate.exists()
    )


def login(client: TestClient, password: str = ADMIN_PASSWORD):
    login_page = client.get("/lean/login")
    assert login_page.status_code == 200
    csrf = extract_csrf(login_page.text)
    return client.post(
        "/lean/login",
        data={"password": password, "csrf_token": csrf},
        follow_redirects=False,
    )


def extract_csrf(html: str) -> str:
    match = re.search(r'name="csrf_token" value="([^"]+)"', html)
    assert match is not None
    return match.group(1)


def test_health_is_generic_and_secret_free(server_module, tmp_path):
    client, config = make_client(server_module, tmp_path)

    response = client.get("/lean/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "lean-crash",
        "version": "1",
    }
    serialized = response.text
    assert ADMIN_PASSWORD not in serialized
    assert SESSION_SECRET not in serialized
    assert str(config.database_path) not in serialized


def test_startup_refuses_missing_or_weak_production_secrets(
    server_module, monkeypatch
):
    for name in (
        "LEAN_CRASH_ADMIN_PASSWORD",
        "LEAN_CRASH_SESSION_SECRET",
        "LEAN_CRASH_ALLOWED_HOSTS",
    ):
        monkeypatch.delenv(name, raising=False)

    with pytest.raises(server_module.ConfigurationError):
        server_module.create_app()

    monkeypatch.setenv("LEAN_CRASH_ADMIN_PASSWORD", "weak")
    monkeypatch.setenv("LEAN_CRASH_SESSION_SECRET", SESSION_SECRET)
    monkeypatch.setenv("LEAN_CRASH_ALLOWED_HOSTS", "crash.example.test")
    with pytest.raises(server_module.ConfigurationError):
        server_module.create_app()

    monkeypatch.setenv("LEAN_CRASH_ADMIN_PASSWORD", ADMIN_PASSWORD)
    monkeypatch.setenv("LEAN_CRASH_SESSION_SECRET", "short")
    with pytest.raises(server_module.ConfigurationError):
        server_module.create_app()


def test_config_representation_does_not_expose_secrets(server_module, tmp_path):
    config = make_config(server_module, tmp_path)

    rendered = repr(config)

    assert ADMIN_PASSWORD not in rendered
    assert SESSION_SECRET not in rendered


@pytest.mark.parametrize(
    "overrides",
    [
        {"body_max_bytes": 1_048_577},
        {"rate_limit_count": 1_001},
        {"rate_limit_window_seconds": 3_601},
        {"login_rate_limit_count": 101},
        {"retention_count": 10_001},
        {"retention_days": 366},
        {"retention_cleanup_interval_seconds": 3_601},
        {"retention_cleanup_interval_seconds": float("nan")},
        {"session_max_age_seconds": 86_401},
    ],
)
def test_security_limits_reject_unsafe_operator_values(
    server_module, tmp_path, overrides
):
    with pytest.raises(server_module.ConfigurationError):
        make_config(server_module, tmp_path, **overrides)


def test_store_startup_error_does_not_disclose_path_or_secrets(
    server_module, tmp_path, monkeypatch
):
    config = make_config(server_module, tmp_path)
    internal_error = (
        f"cannot open {config.database_path}: "
        f"{ADMIN_PASSWORD} {SESSION_SECRET}"
    )

    def fail_store(_config):
        raise OSError(internal_error)

    monkeypatch.setattr(server_module, "CrashStore", fail_store)
    with pytest.raises(server_module.ConfigurationError) as captured:
        server_module.create_app(config)

    rendered = str(captured.value)
    assert rendered == "Unable to initialize crash store"
    assert str(config.database_path) not in rendered
    assert ADMIN_PASSWORD not in rendered
    assert SESSION_SECRET not in rendered


def test_valid_versioned_submission_is_persisted_redacted(
    server_module, tmp_path
):
    client, config = make_client(server_module, tmp_path)
    private_value = "PRIVATE_MATERIAL_SHOULD_NEVER_PERSIST"
    bearer_value = "bearer-value-should-never-persist"
    payload = make_payload(
        message=f"password={private_value}",
        stack_trace=f"Authorization: Bearer {bearer_value}",
    )

    response = client.post("/lean/crash", json=payload)

    assert response.status_code == 202
    assert set(response.json()) == {"report_id", "status"}
    assert response.json()["status"] == "accepted"
    assert re.fullmatch(r"[A-Za-z0-9_-]{10,24}", response.json()["report_id"])
    with sqlite3.connect(config.database_path) as connection:
        row = connection.execute(
            "SELECT message, stack_trace FROM reports"
        ).fetchone()
    assert row is not None
    assert private_value not in row[0]
    assert bearer_value not in row[1]
    assert "[REDACTED]" in " ".join(row)


@pytest.mark.parametrize(
    ("request_kwargs", "expected_status"),
    [
        ({"content": b'{"schema_version":', "headers": {"content-type": "application/json"}}, 400),
        ({"json": make_payload(unexpected="value")}, 422),
        ({"json": make_payload(schema_version=2)}, 422),
        ({"json": make_payload(message="x" * 1001)}, 422),
        ({"json": make_payload(log_tail=["x" * 513])}, 422),
        ({"json": make_payload(log_tail=["line"] * 101)}, 422),
    ],
)
def test_invalid_submissions_are_generic_and_not_persisted(
    server_module, tmp_path, request_kwargs, expected_status
):
    client, config = make_client(server_module, tmp_path)

    response = client.post("/lean/crash", **request_kwargs)

    assert response.status_code == expected_status
    assert response.json() in (
        {"detail": "Malformed JSON"},
        {"detail": "Invalid report"},
    )
    assert report_count(config.database_path) == 0


def test_body_limit_counts_streamed_body_without_content_length(
    server_module, tmp_path
):
    client, config = make_client(server_module, tmp_path, body_max_bytes=512)
    body = json.dumps(make_payload(stack_trace="x" * 2_000)).encode()

    def chunks():
        yield body[:400]
        yield body[400:]

    response = client.post(
        "/lean/crash",
        content=chunks(),
        headers={"content-type": "application/json"},
    )

    assert response.status_code == 413
    assert response.json() == {"detail": "Request body too large"}
    assert report_count(config.database_path) == 0


def test_body_limit_does_not_trust_false_content_length(server_module, tmp_path):
    client, config = make_client(server_module, tmp_path, body_max_bytes=512)
    body = json.dumps(make_payload(stack_trace="x" * 2_000)).encode()

    response = client.post(
        "/lean/crash",
        content=body,
        headers={"content-type": "application/json", "content-length": "1"},
    )

    assert response.status_code == 413
    assert report_count(config.database_path) == 0


def test_non_json_media_type_is_rejected_before_body_validation(
    server_module, tmp_path
):
    client, config = make_client(server_module, tmp_path)
    body = json.dumps(make_payload()).encode()

    response = client.post(
        "/lean/crash",
        content=body,
        headers={"content-type": "text/plain"},
    )

    assert response.status_code == 415
    assert response.json() == {"detail": "Unsupported media type"}
    assert report_count(config.database_path) == 0


def test_rate_limit_triggers(server_module, tmp_path):
    client, config = make_client(server_module, tmp_path, rate_limit_count=2)

    assert client.post("/lean/crash", json=make_payload()).status_code == 202
    assert client.post("/lean/crash", json=make_payload()).status_code == 202
    response = client.post("/lean/crash", json=make_payload())

    assert response.status_code == 429
    assert response.json() == {"detail": "Too many reports"}
    assert report_count(config.database_path) == 2


def test_invalid_submission_consumes_the_origin_rate_limit(server_module, tmp_path):
    client, config = make_client(server_module, tmp_path, rate_limit_count=1)

    invalid = client.post(
        "/lean/crash",
        content=b'{"schema_version":',
        headers={"content-type": "application/json"},
    )
    blocked = client.post("/lean/crash", json=make_payload())

    assert invalid.status_code == 400
    assert blocked.status_code == 429
    assert report_count(config.database_path) == 0


def test_rate_limiter_has_a_hard_origin_capacity(server_module):
    limiter = server_module.SlidingWindowLimiter(
        limit=1,
        window_seconds=60,
        clock=MutableClock(),
    )

    for index in range(4_096):
        assert limiter.allow(f"198.51.100.{index}") is True

    assert limiter.allow("capacity-overflow") is False
    assert len(limiter._buckets) == 4_096


def test_rate_limiter_does_not_full_scan_for_every_new_origin(server_module):
    class CountingBuckets(dict):
        full_scans = 0

        def items(self):
            self.full_scans += 1
            return super().items()

    limiter = server_module.SlidingWindowLimiter(
        limit=1,
        window_seconds=60,
        clock=MutableClock(),
    )
    buckets = CountingBuckets()
    limiter._buckets = buckets

    for index in range(100):
        assert limiter.allow(f"origin-{index}") is True

    assert buckets.full_scans <= 1


def test_rate_window_uses_a_clock_independent_from_wall_time(
    server_module, tmp_path
):
    wall_clock = MutableClock()
    rate_clock = MutableClock()
    config = make_config(
        server_module,
        tmp_path,
        clock=wall_clock,
        rate_clock=rate_clock,
        rate_limit_count=1,
        rate_limit_window_seconds=60,
    )
    client = TestClient(
        server_module.create_app(config),
        base_url="https://testserver",
    )

    assert client.post("/lean/crash", json=make_payload()).status_code == 202
    wall_clock.advance(61)
    assert client.post("/lean/crash", json=make_payload()).status_code == 429
    rate_clock.advance(61)
    assert client.post("/lean/crash", json=make_payload()).status_code == 202


def test_rate_limit_ignores_spoofed_forwarding_headers_by_default(
    server_module, tmp_path
):
    client, _ = make_client(server_module, tmp_path, rate_limit_count=1)

    first = client.post(
        "/lean/crash",
        json=make_payload(),
        headers={"x-forwarded-for": "198.51.100.10"},
    )
    second = client.post(
        "/lean/crash",
        json=make_payload(),
        headers={"x-forwarded-for": "203.0.113.20"},
    )

    assert first.status_code == 202
    assert second.status_code == 429


def test_forwarded_mode_requires_an_explicit_proxy_allowlist(
    server_module, tmp_path
):
    with pytest.raises(server_module.ConfigurationError):
        make_config(
            server_module,
            tmp_path,
            trust_forwarded_for=True,
        )


def test_forwarded_origin_requires_a_trusted_immediate_peer(
    server_module, tmp_path
):
    config = make_config(
        server_module,
        tmp_path / "untrusted",
        trust_forwarded_for=True,
        trusted_proxy_cidrs=("10.0.0.0/8",),
        rate_limit_count=1,
    )
    client = TestClient(
        server_module.create_app(config),
        base_url="https://testserver",
        client=("203.0.113.44", 51000),
    )

    first = client.post(
        "/lean/crash",
        json=make_payload(),
        headers={"x-forwarded-for": "198.51.100.10"},
    )
    second = client.post(
        "/lean/crash",
        json=make_payload(),
        headers={"x-forwarded-for": "198.51.100.20"},
    )

    assert first.status_code == 202
    assert second.status_code == 429


def test_forwarded_origin_is_used_for_an_allowlisted_proxy(
    server_module, tmp_path
):
    config = make_config(
        server_module,
        tmp_path / "trusted",
        trust_forwarded_for=True,
        trusted_proxy_cidrs=("10.0.0.0/8",),
        rate_limit_count=1,
    )
    client = TestClient(
        server_module.create_app(config),
        base_url="https://testserver",
        client=("10.20.30.40", 51000),
    )

    first = client.post(
        "/lean/crash",
        json=make_payload(),
        headers={"x-forwarded-for": "198.51.100.10"},
    )
    second = client.post(
        "/lean/crash",
        json=make_payload(),
        headers={"x-forwarded-for": "198.51.100.20"},
    )

    assert first.status_code == 202
    assert second.status_code == 202


def test_all_secret_classes_are_absent_from_sqlite_and_panel(
    server_module, tmp_path
):
    client, config = make_client(server_module, tmp_path)
    secrets_to_remove = [
        "PEM-PRIVATE-MATERIAL-9173",
        "PasswordValue-2841",
        "BasicCredential-7364",
        "CookieValue-8192",
        "BearerToken-1928",
        "AccessToken-6574",
        "SubscriptionSecret-4826",
        "123e4567-e89b-12d3-a456-426614174000",
        "UriUser-9182",
        "UriPassword-6573",
        "UriQueryToken-9184",
        "WireguardPrivate-2736",
        "WireguardPsk-7382",
    ]
    sensitive_text = "\n".join(
        [
            "-----BEGIN PRIVATE KEY-----",
            secrets_to_remove[0],
            "-----END PRIVATE KEY-----",
            f"password={secrets_to_remove[1]}",
            f"Authorization: Basic {secrets_to_remove[2]}",
            f"Cookie: session={secrets_to_remove[3]}",
            f"Bearer {secrets_to_remove[4]}",
            f"access_token={secrets_to_remove[5]}",
            f"subscription_url=https://example.test/{secrets_to_remove[6]}",
            secrets_to_remove[7],
            (
                "https://"
                f"{secrets_to_remove[8]}:{secrets_to_remove[9]}"
                f"@vpn.example.test/path?token={secrets_to_remove[10]}"
            ),
            f"PrivateKey = {secrets_to_remove[11]}",
            f"PresharedKey = {secrets_to_remove[12]}",
        ]
    )
    response = client.post(
        "/lean/crash",
        json=make_payload(
            message=sensitive_text[:900],
            stack_trace=sensitive_text,
            log_tail=[sensitive_text],
        ),
    )
    assert response.status_code == 202

    database_bytes = config.database_path.read_bytes()
    with sqlite3.connect(config.database_path) as connection:
        stored_text = "\n".join(
            str(value)
            for value in connection.execute(
                "SELECT message, stack_trace, log_tail FROM reports"
            ).fetchone()
        )
    assert login(client).status_code == 303
    panel = client.get("/lean/panel")
    assert panel.status_code == 200

    for secret in secrets_to_remove:
        assert secret.encode() not in database_bytes
        assert secret not in stored_text
        assert secret not in panel.text
    assert "[REDACTED]" in stored_text


@pytest.mark.parametrize(
    ("secret", "diagnostic"),
    [
        (
            "TRUNCATED-PEM-MATERIAL-7284",
            "-----BEGIN PRIVATE KEY-----\nTRUNCATED-PEM-MATERIAL-7284",
        ),
        (
            "SECOND-COOKIE-SECRET-5912",
            "Cookie: theme=light; sid=SECOND-COOKIE-SECRET-5912",
        ),
        (
            "COMMA-PASSWORD-SECRET-8361",
            '{"password":"prefix,COMMA-PASSWORD-SECRET-8361,suffix","state":"failed"}',
        ),
        (
            "MULTILINE-PASSWORD-SECRET-4715",
            "password:\n  MULTILINE-PASSWORD-SECRET-4715",
        ),
        (
            "018f6c44-7a12-7abc-8def-0123456789ab",
            "session=018f6c44-7a12-7abc-8def-0123456789ab",
        ),
    ],
    ids=(
        "truncated-pem",
        "second-cookie",
        "quoted-comma-password",
        "multiline-password",
        "uuid-v7",
    ),
)
def test_redaction_bypasses_never_reach_database_or_panel(
    server_module, tmp_path, secret, diagnostic
):
    client, config = make_client(server_module, tmp_path)

    submitted = client.post(
        "/lean/crash",
        json=make_payload(
            message=diagnostic,
            stack_trace=diagnostic,
            log_tail=[diagnostic],
        ),
    )
    assert submitted.status_code == 202
    assert login(client).status_code == 303
    panel = client.get("/lean/panel")

    assert panel.status_code == 200
    assert secret.encode() not in database_files_bytes(config.database_path)
    assert secret not in panel.text
    assert "[REDACTED]" in panel.text


def test_unicode_control_characters_are_removed_before_persistence(
    server_module, tmp_path
):
    client, config = make_client(server_module, tmp_path)
    diagnostic = "before\u0085middle\u202eafter"

    response = client.post(
        "/lean/crash",
        json=make_payload(message=diagnostic),
    )

    assert response.status_code == 202
    with sqlite3.connect(config.database_path) as connection:
        stored = connection.execute(
            "SELECT message FROM reports"
        ).fetchone()[0]
    assert stored == "beforemiddleafter"
    assert "\u0085" not in stored
    assert "\u202e" not in stored


def test_unauthenticated_panel_cannot_see_report_data(server_module, tmp_path):
    client, _ = make_client(server_module, tmp_path)
    visible_only_after_login = "private crash summary 6831"
    assert (
        client.post(
            "/lean/crash",
            json=make_payload(message=visible_only_after_login),
        ).status_code
        == 202
    )

    response = client.get("/lean/panel", follow_redirects=False)

    assert response.status_code == 303
    assert response.headers["location"] == "/lean/login"
    assert visible_only_after_login not in response.text


def test_login_is_generic_and_sets_exact_cookie_security_flags(
    server_module, tmp_path
):
    client, _ = make_client(server_module, tmp_path)

    wrong = login(client, password="Wrong-password-that-is-still-long")
    assert wrong.status_code == 401
    assert wrong.text == "Invalid credentials"
    assert ADMIN_PASSWORD not in wrong.text

    valid = login(client)
    assert valid.status_code == 303
    assert valid.headers["location"] == "/lean/panel"
    cookie = valid.headers["set-cookie"].lower()
    assert cookie.startswith("lean_crash_session=")
    assert "path=/lean" in cookie
    assert "max-age=900" in cookie
    assert "httponly" in cookie
    assert "samesite=strict" in cookie
    assert "secure" in cookie
    assert ADMIN_PASSWORD not in cookie
    assert "token=" not in valid.headers["location"].lower()


def test_session_expiry_and_logout_invalidate_access(server_module, tmp_path):
    clock = MutableClock()
    client, _ = make_client(
        server_module,
        tmp_path,
        clock=clock,
        session_max_age_seconds=10,
    )
    assert login(client).status_code == 303
    assert client.get("/lean/panel").status_code == 200

    clock.advance(11)
    expired = client.get("/lean/panel", follow_redirects=False)
    assert expired.status_code == 303
    assert expired.headers["location"] == "/lean/login"

    fresh_client, _ = make_client(
        server_module,
        tmp_path,
        clock=MutableClock(),
        session_max_age_seconds=10,
    )
    assert login(fresh_client).status_code == 303
    panel = fresh_client.get("/lean/panel")
    csrf = extract_csrf(panel.text)
    logout = fresh_client.post(
        "/lean/logout",
        data={"csrf_token": csrf},
        follow_redirects=False,
    )
    assert logout.status_code == 303
    assert logout.headers["location"] == "/lean/login"
    denied = fresh_client.get("/lean/panel", follow_redirects=False)
    assert denied.status_code == 303


def test_logout_revokes_a_copied_signed_session_cookie(server_module, tmp_path):
    config = make_config(server_module, tmp_path)
    app = server_module.create_app(config)
    client = TestClient(app, base_url="https://testserver")
    assert login(client).status_code == 303
    copied_cookie = client.cookies.get(server_module.SESSION_COOKIE)
    assert copied_cookie

    panel = client.get("/lean/panel")
    logout = client.post(
        "/lean/logout",
        data={"csrf_token": extract_csrf(panel.text)},
        follow_redirects=False,
    )
    assert logout.status_code == 303

    replay = TestClient(app, base_url="https://testserver").get(
        "/lean/panel",
        headers={"cookie": f"{server_module.SESSION_COOKIE}={copied_cookie}"},
        follow_redirects=False,
    )
    assert replay.status_code == 303
    assert replay.headers["location"] == "/lean/login"


def test_signed_session_is_shared_and_revoked_across_app_instances(
    server_module, tmp_path
):
    config = make_config(server_module, tmp_path)
    app_a = server_module.create_app(config)
    app_b = server_module.create_app(config)
    client_a = TestClient(app_a, base_url="https://testserver")
    client_b = TestClient(app_b, base_url="https://testserver")
    assert login(client_a).status_code == 303
    copied_cookie = client_a.cookies.get(server_module.SESSION_COOKIE)
    assert copied_cookie
    cookie_header = {
        "cookie": f"{server_module.SESSION_COOKIE}={copied_cookie}"
    }

    panel_on_b = client_b.get(
        "/lean/panel",
        headers=cookie_header,
        follow_redirects=False,
    )
    assert panel_on_b.status_code == 200
    logout_on_b = client_b.post(
        "/lean/logout",
        headers=cookie_header,
        data={"csrf_token": extract_csrf(panel_on_b.text)},
        follow_redirects=False,
    )
    assert logout_on_b.status_code == 303

    replay_on_a = client_a.get("/lean/panel", follow_redirects=False)
    assert replay_on_a.status_code == 303
    assert replay_on_a.headers["location"] == "/lean/login"


def test_state_change_requires_current_session_csrf(server_module, tmp_path):
    client, config = make_client(server_module, tmp_path)
    assert client.post("/lean/crash", json=make_payload()).status_code == 202
    assert login(client).status_code == 303

    missing = client.post("/lean/panel/clear", data={})
    wrong = client.post(
        "/lean/panel/clear",
        data={"csrf_token": "wrong-token"},
    )
    assert missing.status_code == 403
    assert wrong.status_code == 403
    assert report_count(config.database_path) == 1

    panel = client.get("/lean/panel")
    current_csrf = extract_csrf(panel.text)
    accepted = client.post(
        "/lean/panel/clear",
        data={"csrf_token": current_csrf},
        follow_redirects=False,
    )
    assert accepted.status_code == 303
    assert accepted.headers["location"] == "/lean/panel"
    assert report_count(config.database_path) == 0


def test_panel_escapes_html_and_sets_defensive_headers(server_module, tmp_path):
    client, _ = make_client(server_module, tmp_path)
    hostile = '<script>alert("stored-xss")</script>'
    assert (
        client.post(
            "/lean/crash",
            json=make_payload(message=hostile),
        ).status_code
        == 202
    )
    assert login(client).status_code == 303

    panel = client.get("/lean/panel")

    assert panel.status_code == 200
    assert hostile not in panel.text
    assert "&lt;script&gt;alert(&quot;stored-xss&quot;)&lt;/script&gt;" in panel.text
    assert panel.headers["cache-control"] == "no-store"
    assert panel.headers["x-content-type-options"] == "nosniff"
    assert panel.headers["x-frame-options"] == "DENY"
    assert panel.headers["referrer-policy"] == "no-referrer"
    csp = panel.headers["content-security-policy"]
    assert "default-src 'none'" in csp
    assert "frame-ancestors 'none'" in csp


def test_retention_by_age_and_count_is_enforced(server_module, tmp_path):
    clock = MutableClock()
    client, config = make_client(
        server_module,
        tmp_path,
        clock=clock,
        retention_count=2,
        retention_days=7,
    )
    with sqlite3.connect(config.database_path) as connection:
        connection.execute(
            """
            INSERT INTO reports (
                report_id, received_at, app_version, exception_type,
                message, stack_trace, log_tail
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                "old-report",
                int(clock.value - 8 * 86_400),
                "0.1",
                "OldError",
                "too old",
                "old stack",
                "[]",
            ),
        )
        connection.commit()

    for message in ("newest-1", "newest-2", "newest-3"):
        assert (
            client.post(
                "/lean/crash",
                json=make_payload(message=message),
            ).status_code
            == 202
        )

    with sqlite3.connect(config.database_path) as connection:
        rows = connection.execute(
            "SELECT report_id, message FROM reports ORDER BY sequence DESC"
        ).fetchall()
    assert len(rows) == 2
    assert {message for _, message in rows} == {"newest-2", "newest-3"}
    assert all(report_id != "old-report" for report_id, _ in rows)


def test_age_retention_runs_while_service_is_idle(server_module, tmp_path):
    clock = MutableClock()
    config = make_config(
        server_module,
        tmp_path,
        clock=clock,
        retention_days=1,
        retention_cleanup_interval_seconds=0.05,
    )
    app = server_module.create_app(config)

    with TestClient(app, base_url="https://testserver") as client:
        assert client.post("/lean/crash", json=make_payload()).status_code == 202
        assert report_count(config.database_path) == 1
        clock.advance(2 * 86_400)

        deadline = time.monotonic() + 2
        while report_count(config.database_path) != 0 and time.monotonic() < deadline:
            time.sleep(0.02)

        assert report_count(config.database_path) == 0


def test_errors_do_not_disclose_body_or_configured_secrets(
    server_module, tmp_path
):
    client, config = make_client(server_module, tmp_path)
    request_secret = "request-only-secret-9917"

    response = client.post(
        "/lean/crash",
        json={
            **make_payload(),
            "unexpected": request_secret,
            "message": ADMIN_PASSWORD,
        },
    )
    health = client.get("/lean/health")

    assert response.status_code == 422
    for secret in (
        request_secret,
        ADMIN_PASSWORD,
        SESSION_SECRET,
        str(config.database_path),
    ):
        assert secret not in response.text
        assert secret not in health.text
    assert report_count(config.database_path) == 0


def test_unexpected_error_is_contained_and_logged_without_sensitive_details(
    server_module, tmp_path, monkeypatch, caplog
):
    sensitive_error = (
        f"unexpected {ADMIN_PASSWORD} {SESSION_SECRET} "
        f"{tmp_path / 'private.sqlite3'}"
    )

    def fail_save(_store, _report):
        raise ValueError(sensitive_error)

    monkeypatch.setattr(server_module.CrashStore, "save", fail_save)
    client, _ = make_client(server_module, tmp_path)

    with caplog.at_level(logging.ERROR, logger="lean_crash.errors"):
        response = client.post("/lean/crash", json=make_payload())

    assert response.status_code == 503
    assert response.json() == {"detail": "Service unavailable"}
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert "default-src 'none'" in response.headers["content-security-policy"]
    assert len(caplog.records) == 1
    assert re.fullmatch(
        r"Unhandled server error event_id=[0-9a-f]{16}",
        caplog.records[0].getMessage(),
    )
    assert caplog.records[0].exc_info is None
    for secret in (
        sensitive_error,
        ADMIN_PASSWORD,
        SESSION_SECRET,
        str(tmp_path),
    ):
        assert secret not in response.text
        assert secret not in caplog.text


def test_production_redirects_http_and_rejects_untrusted_hosts(
    server_module, tmp_path
):
    config = make_config(server_module, tmp_path, production=True)
    app = server_module.create_app(config)
    http_client = TestClient(
        app,
        base_url="http://testserver",
        follow_redirects=False,
    )

    redirect = http_client.get("/lean/health")
    assert redirect.status_code in (307, 308)
    assert redirect.headers["location"] == "https://testserver/lean/health"

    untrusted = TestClient(
        app,
        base_url="https://attacker.example",
        follow_redirects=False,
    ).get("/lean/health")
    assert untrusted.status_code == 400
