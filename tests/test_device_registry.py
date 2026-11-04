"""Offline tests: no network, real Gemini key, Windows listener or APK needed."""
import os
import sqlite3
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from backend_security.device_registry import DeviceRegistry, DeviceRegistryError
from backend_security.lan_auth import LanAuthGuard


def make_registry(tmp_path, **options):
    return DeviceRegistry(tmp_path / "paired-devices.sqlite3", initialize=True, **options)


def app_client(registry, *, https=True):
    app = FastAPI()

    @app.post("/v1/analyze")
    def analyze():
        return {"ok": True}

    @app.post("/v1/transcribe")
    def transcribe():
        return {"ok": True}

    @app.get("/health")
    def health():
        return {"status": "ok"}

    return TestClient(
        LanAuthGuard(app, enabled=True, token_verifier=registry.verify),
        base_url="https://testserver" if https else "http://testserver",
    )


def bearer(token):
    return {"Authorization": "Bearer " + token}


def test_registry_requires_explicit_initialization(tmp_path):
    path = tmp_path / "pairing.sqlite3"
    with pytest.raises(DeviceRegistryError, match="missing"):
        DeviceRegistry(path)
    assert not path.exists()


def test_issue_and_reload_revocable_token(tmp_path):
    registry = make_registry(tmp_path)
    credential = registry.issue("Test Android")
    raw = credential.token.encode("ascii")
    assert credential.token.startswith("ar1_")
    assert len(raw) == 47
    assert registry.verify(raw)
    assert credential.token.encode("ascii") not in registry.path.read_bytes()

    loaded = DeviceRegistry(registry.path)
    assert loaded.verify(raw)
    devices = loaded.list_devices()
    assert len(devices) == 1
    assert devices[0].device_id == credential.device_id
    assert devices[0].label == "Test Android"
    assert not hasattr(devices[0], "token")

    assert loaded.revoke(credential.device_id)
    assert not registry.verify(raw)  # No stale per-worker token cache
    assert not loaded.revoke(credential.device_id)


def test_unique_device_tokens_and_independent_revocation(tmp_path):
    registry = make_registry(tmp_path)
    a = registry.issue("Android A")
    b = registry.issue("Android B")
    assert a.token != b.token
    registry.revoke(a.device_id)
    assert not registry.verify(a.token.encode())
    assert registry.verify(b.token.encode())


def test_invalid_input_and_device_cap(tmp_path):
    registry = make_registry(tmp_path, max_active=1)
    with pytest.raises(ValueError):
        registry.issue("\nforbidden")
    registry.issue("One")
    with pytest.raises(DeviceRegistryError, match="limit"):
        registry.issue("Two")
    assert not registry.verify(b"")
    assert not registry.verify(b"ar1_" + b"x" * 42)
    assert not registry.verify(b"ar1_" + b"!" * 43)
    assert not registry.verify("ar1_" + "x" * 43)
    assert not registry.revoke("../../no")


def test_corrupted_database_fails_closed(tmp_path):
    registry = make_registry(tmp_path)
    token = registry.issue("Android").token
    registry.path.write_bytes(b"not a sqlite database")
    with pytest.raises(DeviceRegistryError):
        registry.verify(token.encode("ascii"))


def test_registry_symlink_rejected(tmp_path):
    real = make_registry(tmp_path)
    link = tmp_path / "link.sqlite3"
    try:
        link.symlink_to(real.path)
    except (OSError, NotImplementedError):
        pytest.skip("Cannot create symlinks on this platform")
    with pytest.raises(DeviceRegistryError, match="symbolic"):
        DeviceRegistry(link)


def test_https_auth_valid_and_revoked(tmp_path):
    registry = make_registry(tmp_path)
    credential = registry.issue("Android")
    client = app_client(registry)
    assert client.post("/v1/analyze", headers=bearer(credential.token)).status_code == 200
    assert client.post("/v1/transcribe", headers=bearer(credential.token)).status_code == 200
    assert client.post("/v1/analyze").status_code == 401
    assert client.post("/v1/analyze", headers=bearer("ar1_" + "x" * 43)).status_code == 401
    assert client.get("/health").status_code == 200
    registry.revoke(credential.device_id)
    r = client.post("/v1/analyze", headers=bearer(credential.token))
    assert r.status_code == 401
    assert credential.token not in r.text


def test_tls_only_even_with_valid_device_credential(tmp_path):
    registry = make_registry(tmp_path)
    credential = registry.issue("Android")
    client = app_client(registry, https=False)
    r = client.post("/v1/analyze", headers={
        **bearer(credential.token),
        "X-Forwarded-Proto": "https",
    })
    assert r.status_code == 403
    assert r.json()["detail"]["code"] == "AI_TLS_REQUIRED"


def test_corrupt_registry_is_503_not_bypass(tmp_path):
    registry = make_registry(tmp_path)
    credential = registry.issue("Android")
    registry.path.write_bytes(b"not a sqlite database")
    r = app_client(registry).post("/v1/analyze", headers=bearer(credential.token))
    assert r.status_code == 503
    assert r.json()["detail"]["code"] == "AI_AUTH_UNAVAILABLE"
    assert credential.token not in r.text


def test_duplicate_auth_headers_rejected(tmp_path):
    registry = make_registry(tmp_path)
    credential = registry.issue("Android")
    client = app_client(registry)
    headers = [
        ("Authorization", "Bearer " + credential.token),
        ("authorization", "Bearer " + credential.token),
    ]
    r = client.post("/v1/analyze", headers=headers)
    assert r.status_code == 401


def test_no_credential_in_sqlite_schema_or_journal(tmp_path):
    registry = make_registry(tmp_path)
    token = registry.issue("Android").token
    connection = sqlite3.connect(str(registry.path))
    try:
        rows = connection.execute(
            "SELECT device_id, label, token_digest FROM devices"
        ).fetchall()
    finally:
        connection.close()
    assert len(rows) == 1
    assert len(rows[0][2]) == 32
    assert token.encode() not in registry.path.read_bytes()
