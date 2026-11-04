import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from backend_security.lan_auth import LanAuthGuard


@pytest.fixture(autouse=True)
def key(monkeypatch):
    monkeypatch.setenv("AI_LAN_API_TOKEN", "e" * 48)


def client(enabled, *, https=True):
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
        LanAuthGuard(app, enabled=enabled),
        base_url="https://testserver" if https else "http://testserver",
    )


def test_disabled_retains_existing_http_client():
    assert client(False, https=False).post("/v1/analyze").status_code == 200


def test_missing_key_fails_closed(monkeypatch):
    monkeypatch.delenv("AI_LAN_API_TOKEN")
    with pytest.raises(RuntimeError, match="credential missing"):
        client(True)


def test_http_refused_even_with_valid_key():
    r = client(True, https=False).post(
        "/v1/analyze", headers={"Authorization": "Bearer " + "e" * 48}
    )
    assert r.status_code == 403
    assert r.json()["detail"]["code"] == "AI_TLS_REQUIRED"


def test_missing_token_and_health():
    c = client(True)
    assert c.post("/v1/analyze").status_code == 401
    assert c.post("/v1/transcribe").status_code == 401
    assert c.get("/health").json() == {"status": "ok"}


def test_correct_token():
    c = client(True)
    headers = {"Authorization": "Bearer " + "e" * 48}
    assert c.post("/v1/analyze", headers=headers).status_code == 200
    assert c.post("/v1/transcribe", headers=headers).status_code == 200


def test_wrong_token_redacts_secret():
    r = client(True).post(
        "/v1/analyze", headers={"Authorization": "Bearer " + "f" * 48}
    )
    assert r.status_code == 401
    assert "e" * 48 not in r.text


def test_forwarded_proto_cannot_bypass_http_restriction():
    r = client(True, https=False).post(
        "/v1/analyze",
        headers={
            "X-Forwarded-Proto": "https",
            "Authorization": "Bearer " + "e" * 48,
        },
    )
    assert r.status_code == 403


def test_wrong_case_bearer_rejected_without_leak():
    r = client(True).post(
        "/v1/analyze", headers={"AUTHORIZATION": "Bearer wrong"}
    )
    assert r.status_code == 401
    assert "e" * 48 not in r.text
