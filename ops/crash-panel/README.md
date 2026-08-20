# Lean crash diagnostics service

This module receives strictly bounded, versioned crash reports and exposes a
private, cookie-authenticated diagnostics panel. The Android client does not
carry a shared ingestion secret. Reports are redacted before SQLite receives
them and are removed by age and count.

## Run

Create an isolated environment, install `requirements.txt`, and provide all
deployment secrets through the process environment. The command below
intentionally starts a single process; panel sessions are also safe when
multiple workers share the same SQLite database.

```powershell
$env:LEAN_CRASH_ADMIN_PASSWORD = '<long random password>'
$env:LEAN_CRASH_SESSION_SECRET = '<at least 32 random characters>'
$env:LEAN_CRASH_ALLOWED_HOSTS = 'crash.example.com'
$env:LEAN_CRASH_DATABASE_PATH = 'D:\lean-crash-data\crashes.sqlite3'
$env:LEAN_CRASH_ENV = 'production'
python -m uvicorn app:create_app --factory --host 127.0.0.1 --port 8080 --no-access-log
```

There are no production secret defaults. `LEAN_CRASH_ALLOWED_HOSTS` is a
comma-separated list of hostnames and must not contain a global wildcard.
Production mode redirects HTTP to HTTPS and always emits a `Secure`,
`HttpOnly`, `SameSite=Strict` session cookie scoped to `/lean`.

The public contract is:

- `POST /lean/crash`
- `GET /lean/health`
- `GET /lean/panel`

The current ingestion schema is:

```json
{
  "schema_version": 1,
  "app_version": "0.9.4",
  "exception_type": "IllegalStateException",
  "message": "bounded message",
  "stack_trace": "bounded stack trace",
  "log_tail": ["bounded line"]
}
```

Unknown fields, unsupported versions, malformed JSON and oversized requests
are rejected. The service never stores source addresses, headers, cookies,
device identifiers or fingerprints.

## Reverse proxy

Terminate TLS at a reverse proxy with a valid hostname and certificate. Forward
the original host and scheme, restrict the application port to the proxy, and
configure the ASGI server to trust forwarding metadata only from that proxy.
For Uvicorn, set `--proxy-headers` and a narrow `--forwarded-allow-ips` value
matching the proxy address. Do not use `*`.

Keep request/access logging disabled for this service so source addresses,
headers and submitted diagnostics are not copied into another data store.
Unexpected application failures are contained by the outer ASGI boundary and
logged only as random event IDs, without exception messages or tracebacks.

`LEAN_CRASH_TRUST_FORWARDED_FOR` remains false by default. Enable it only when
the trusted proxy replaces client-supplied `X-Forwarded-For`, and also set
`LEAN_CRASH_TRUSTED_PROXY_CIDRS` to a comma-separated, narrow allowlist of the
proxy networks. Raw forwarding headers are ignored unless the immediate peer
belongs to that allowlist. Keep Uvicorn's `--forwarded-allow-ips` equally
narrow; never use `*`.

The built-in limiter is deliberately bounded and local to one process. A
multi-worker or multi-host deployment must enforce a distributed limit at the
trusted reverse proxy as well. Authenticated panel sessions are stored as
keyed hashes in the shared SQLite database, so login and logout work across
workers that share the database and session secret. Raw session IDs are never
persisted.

## Retention and operations

Optional non-secret settings:

- `LEAN_CRASH_BODY_MAX_BYTES` (default `32768`)
- `LEAN_CRASH_RATE_LIMIT_COUNT` (default `20`)
- `LEAN_CRASH_RATE_LIMIT_WINDOW_SECONDS` (default `60`)
- `LEAN_CRASH_LOGIN_RATE_LIMIT_COUNT` (default `5`)
- `LEAN_CRASH_RETENTION_COUNT` (default `500`)
- `LEAN_CRASH_RETENTION_DAYS` (default `14`)
- `LEAN_CRASH_RETENTION_CLEANUP_INTERVAL_SECONDS` (default `300`)
- `LEAN_CRASH_PANEL_PAGE_SIZE` (default `25`, maximum `100`)
- `LEAN_CRASH_SESSION_MAX_AGE_SECONDS` (default `900`)
- `LEAN_CRASH_TRUST_FORWARDED_FOR` (default `false`)
- `LEAN_CRASH_TRUSTED_PROXY_CIDRS` (required when forwarding trust is enabled)

Safety ceilings are 1,048,576 body bytes, 1,000 reports per rate window, 3,600
seconds per rate window, 100 login attempts per window, 10,000 retained
reports, 365 retention days, 3,600 seconds between retention sweeps, and 86,400
seconds per panel session. Startup fails instead of accepting larger values.

Age retention is enforced on startup, on database activity, and by a periodic
lifespan task. Consequently, an otherwise idle healthy process removes expired
rows no later than the configured cleanup interval after the age boundary.
Retention is logical deletion, not guaranteed physical erasure: deleted
content may remain in SQLite free pages, WAL history, filesystem snapshots, or
backups. Apply an independent retention policy to backups and use a separately
reviewed secure-delete/checkpoint/vacuum procedure if physical erasure is a
requirement.

Run the service as a dedicated unprivileged user. Grant that user access only
to the database directory. Back up the SQLite database together with its WAL
files while the service is stopped or through SQLite's online backup tooling.
Never publish `/lean/panel`; access it only through the authenticated HTTPS
endpoint.

## Tests

```powershell
python -m pip install -r requirements-dev.txt
python -B -m pytest -q -p no:cacheprovider
python -B -m pip check
```
