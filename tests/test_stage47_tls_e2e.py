"""Real TLS over loopback, isolated synthetic app and ephemeral credentials.

No recording, Gemini request, Windows firewall change or working Uvicorn restart.
Can run on both Windows and Linux GitHub runners.
"""
from __future__ import annotations

import datetime
import os
import socket
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path

import httpx
import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

from backend_security.device_registry import DeviceRegistry


def _tls_material(root: Path) -> tuple[Path, Path, Path]:
    """Test-only CA and leaf; private keys never leave tmp_path or GitHub."""
    now = datetime.datetime.now(datetime.timezone.utc)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Stage47 throwaway test CA")])
    ca = (
        x509.CertificateBuilder()
        .subject_name(ca_name)
        .issuer_name(ca_name)
        .public_key(ca_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(minutes=5))
        .not_valid_after(now + datetime.timedelta(days=1))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(x509.KeyUsage(
            digital_signature=True, key_encipherment=False, content_commitment=False,
            data_encipherment=False, key_agreement=False, key_cert_sign=True,
            crl_sign=True, encipher_only=None, decipher_only=None
        ), critical=True)
        .sign(ca_key, hashes.SHA256())
    )
    leaf_key = ec.generate_private_key(ec.SECP256R1())
    leaf_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    leaf = (
        x509.CertificateBuilder()
        .subject_name(leaf_name)
        .issuer_name(ca.subject)
        .public_key(leaf_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(minutes=5))
        .not_valid_after(now + datetime.timedelta(hours=12))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost")]), critical=False)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(x509.KeyUsage(
            digital_signature=True, key_encipherment=False, content_commitment=False,
            data_encipherment=False, key_agreement=False, key_cert_sign=False,
            crl_sign=False, encipher_only=None, decipher_only=None
        ), critical=True)
        .sign(ca_key, hashes.SHA256())
    )
    ca_path = root / "ca.pem"
    cert_path = root / "server.pem"
    key_path = root / "server.key"
    ca_path.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    cert_path.write_bytes(leaf.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(leaf_key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ))
    if os.name != "nt":
        key_path.chmod(0o600)
    return ca_path, cert_path, key_path


def _unused_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@contextmanager
def _serve(root: Path, db_path: Path, *, ca_path=None, cert_path=None, key_path=None):
    """Launch exactly one isolated uvicorn process and stop only that process."""
    port = _unused_loopback_port()
    cmd = [
        sys.executable, "-m", "uvicorn",
        "backend_security.stage47_loopback_app:app",
        "--host", "127.0.0.1",
        "--port", str(port),
        "--no-proxy-headers",
        "--no-access-log",
        "--log-level", "error",
    ]
    if cert_path is not None:
        cmd.extend(["--ssl-certfile", str(cert_path), "--ssl-keyfile", str(key_path)])
    environment = os.environ.copy()
    environment["AI_STAGE47_TEST_ONLY"] = "1"
    environment["AI_STAGE47_TEST_DB"] = str(db_path)
    environment["PYTHONIOENCODING"] = "utf-8"
    log_path = root / ("https.log" if cert_path is not None else "http.log")
    scheme = "https" if cert_path is not None else "http"
    host = "localhost" if cert_path is not None else "127.0.0.1"
    url = f"{scheme}://{host}:{port}"
    with log_path.open("wb") as logfile:
        proc = subprocess.Popen(cmd, cwd=str(Path(__file__).resolve().parents[1]),
                                env=environment, stdout=logfile,
                                stderr=subprocess.STDOUT, close_fds=True)
        try:
            with httpx.Client(verify=str(ca_path) if ca_path else True,
                              timeout=1.0, trust_env=False) as client:
                deadline = time.monotonic() + 20
                while time.monotonic() < deadline:
                    if proc.poll() is not None:
                        raise AssertionError("Isolated TLS test server exited before health check")
                    try:
                        response = client.get(url + "/health")
                        if response.status_code == 200:
                            break
                    except httpx.RequestError:
                        pass
                    time.sleep(0.15)
                else:
                    raise AssertionError("Isolated TLS test server did not become healthy")
            yield url, port
        finally:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=8)
    # No unlink: Windows may briefly hold test logs open. Temporary dir is
    # managed by pytest, and log cleanup must never overwrite a PASS.


def test_tls_auth_and_device_revocation(tmp_path):
    db = tmp_path / "devices.sqlite3"
    registry = DeviceRegistry(db, initialize=True)
    first = registry.issue("Synthetic Android A")
    second = registry.issue("Synthetic Android B")
    ca, cert, key = _tls_material(tmp_path)
    with _serve(tmp_path, db, ca_path=ca, cert_path=cert, key_path=key) as (base, port):
        with httpx.Client(verify=str(ca), trust_env=False, timeout=5.0) as client:
            health = client.get(base + "/health")
            assert health.status_code == 200
            assert health.json() == {"status": "ok", "test_only": True}

            assert client.post(base + "/v1/analyze").status_code == 401
            wrong = "ar1_" + "x" * 43
            assert client.post(base + "/v1/analyze",
                               headers={"Authorization": "Bearer " + wrong}).status_code == 401
            authorized = client.post(base + "/v1/analyze",
                                     headers={"Authorization": "Bearer " + first.token})
            assert authorized.status_code == 200
            assert authorized.json() == {"accepted": True, "test_only": True}
            assert client.post(base + "/v1/transcribe",
                               headers={"Authorization": "Bearer " + first.token}).status_code == 200

            assert registry.revoke(first.device_id)
            denied = client.post(base + "/v1/analyze",
                                 headers={"Authorization": "Bearer " + first.token})
            assert denied.status_code == 401
            assert first.token not in denied.text
            assert client.post(base + "/v1/analyze",
                               headers={"Authorization": "Bearer " + second.token}).status_code == 200

        # Valid trusted CA, incorrect hostname must fail certificate validation.
        with httpx.Client(verify=str(ca), trust_env=False, timeout=5.0) as client:
            with pytest.raises(httpx.ConnectError):
                client.get(f"https://127.0.0.1:{port}/health")

        # Untrusted CA must also fail (never use verify=False as a workaround).
        with httpx.Client(trust_env=False, timeout=5.0) as client:
            with pytest.raises(httpx.ConnectError):
                client.get(base + "/health")


def test_http_cannot_bypass_guard_with_forwarded_proto(tmp_path):
    db = tmp_path / "devices.sqlite3"
    registry = DeviceRegistry(db, initialize=True)
    credential = registry.issue("Synthetic Android")
    with _serve(tmp_path, db) as (base, _):
        with httpx.Client(trust_env=False, timeout=5.0) as client:
            r = client.post(base + "/v1/analyze", headers={
                "Authorization": "Bearer " + credential.token,
                "X-Forwarded-Proto": "https",
            })
            assert r.status_code == 403
            assert r.json()["detail"]["code"] == "AI_TLS_REQUIRED"
            assert credential.token not in r.text


def test_gated_test_app_never_importable_by_accident(monkeypatch):
    monkeypatch.delenv("AI_STAGE47_TEST_ONLY", raising=False)
    # Import only in this process after all live-server subprocesses are gone:
    # the module MUST fail closed unless explicitly launched for the test.
    with pytest.raises(RuntimeError, match="disabled"):
        __import__("backend_security.stage47_loopback_app", fromlist=["app"])
