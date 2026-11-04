"""Opt-in HTTPS-only LAN authorization for a future AI-Recorder rollout.

This class is NOT wired into the running FastAPI app. Never deploy it over
plain HTTP or assume a reverse proxy's headers are trustworthy by default.
"""
import hmac
import json
import os


class LanAuthGuard:
    """ASGI gate for sensitive AI endpoints.

    `token_verifier` optionally accepts a callable receiving raw token bytes.
    For Stage 45, pass DeviceRegistry.verify. It opens the SQLite DB on every
    request, so a revoked device is denied immediately by every worker.
    The legacy environment-token option remains only for Stage 44 tests.
    """

    PROTECTED = frozenset(("/v1/analyze", "/v1/transcribe"))

    def __init__(self, app, *, enabled=False, token_env="AI_LAN_API_TOKEN",
                 token_verifier=None):
        self.app = app
        self.enabled = enabled
        self._token = b""
        self._token_verifier = token_verifier
        if not enabled:
            return
        if token_verifier is not None:
            if not callable(token_verifier):
                raise TypeError("Token verifier must be callable")
            return
        # Legacy single-token mode is for isolated tests only; DO NOT provision
        # this shared secret in an APK. Use a per-device registry for rollout.
        token = os.environ.get(token_env, "").encode("utf-8")
        if not (32 <= len(token) <= 256):
            raise RuntimeError("LAN authentication credential missing or invalid")
        self._token = token

    @staticmethod
    async def _reject(send, status, code):
        data = json.dumps({"detail": {"code": code}}).encode("utf-8")
        await send({
            "type": "http.response.start",
            "status": status,
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(data)).encode("ascii")),
                (b"cache-control", b"no-store"),
            ],
        })
        await send({"type": "http.response.body", "body": data})

    async def __call__(self, scope, receive, send):
        if (not self.enabled or scope["type"] != "http"
                or scope.get("path") not in self.PROTECTED):
            return await self.app(scope, receive, send)

        # TLS must be real, not inferred from untrusted X-Forwarded-Proto.
        # Reverse proxies must expose the backend only on a private loopback
        # interface and pass *trusted* scheme information.
        if scope.get("scheme") != "https":
            return await self._reject(send, 403, "AI_TLS_REQUIRED")

        headers = [value for name, value in scope.get("headers", ())
                   if name.lower() == b"authorization"]
        if len(headers) != 1 or not headers[0].startswith(b"Bearer "):
            return await self._reject(send, 401, "AI_AUTH_REQUIRED")
        supplied = headers[0][7:]
        if not supplied or len(supplied) > 256:
            return await self._reject(send, 401, "AI_AUTH_REQUIRED")

        try:
            valid = (bool(self._token_verifier(supplied))
                     if self._token_verifier is not None
                     else hmac.compare_digest(supplied, self._token))
        except Exception:
            # Fail closed; do not expose registry paths, secrets or exceptions.
            return await self._reject(send, 503, "AI_AUTH_UNAVAILABLE")
        if not valid:
            return await self._reject(send, 401, "AI_AUTH_REQUIRED")
        return await self.app(scope, receive, send)
