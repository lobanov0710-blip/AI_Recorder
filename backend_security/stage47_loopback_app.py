"""Test-only ASGI application for isolated HTTPS authorization smoke tests.

NEVER deploy this application or import it into the working FastAPI service.
It has deliberately synthetic responses and cannot process real recordings.
"""
import os
from pathlib import Path

from fastapi import FastAPI

from backend_security.device_registry import DeviceRegistry
from backend_security.lan_auth import LanAuthGuard

if os.getenv("AI_STAGE47_TEST_ONLY") != "1":
    raise RuntimeError("Stage 47 test application is disabled")
_path = os.getenv("AI_STAGE47_TEST_DB", "")
if not _path or not Path(_path).is_absolute():
    raise RuntimeError("Stage 47 test database must be an absolute path")

_test_registry = DeviceRegistry(_path)
_application = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)


@_application.get("/health")
def health():
    return {"status": "ok", "test_only": True}


@_application.post("/v1/analyze")
def analyze():
    return {"accepted": True, "test_only": True}


@_application.post("/v1/transcribe")
def transcribe():
    return {"accepted": True, "test_only": True}


app = LanAuthGuard(_application, enabled=True, token_verifier=_test_registry.verify)
