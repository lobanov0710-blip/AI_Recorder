"""Opt-in HTTPS-only LAN bearer authentication for a future AI-Recorder rollout.

Not wired into main.py. First provision per-device credentials and verified TLS.
"""
import hmac
import os

class LanAuthGuard:
    """Minimal ASGI authentication gate for sensitive AI routes.

    The gate does not read request bodies. This is not enrollment or a full
    identity/session system. Do not use over HTTP or embed the token in an APK.
    """
    PROTECTED = frozenset(("/v1/analyze", "/v1/transcribe"))

    def __init__(self, app, *, enabled=False, token_env="AI_LAN_API_TOKEN"):
        self.app = app
        self.enabled = enabled
        self._token = b""
        if enabled:
            token = os.environ.get(token_env, "").encode("utf-8")
            if not (32 <= len(token) <= 256):
                raise RuntimeError("LAN authentication credential missing or invalid")
            self._token = token

    @staticmethod
    async def _reject(send, status, code):
        import json
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
        if not self.enabled or scope["type"] != "http" or scope.get("path") not in self.PROTECTED:
            return await self.app(scope, receive, send)
        # Never send bearer credentials over plain HTTP. TLS must be verified
        # by the ingress. Client X-Forwarded-Proto must not be trusted.
        if scope.get("scheme") != "https":
            return await self._reject(send, 403, "AI_TLS_REQUIRED")
        headers = [value for name, value in scope.get("headers", ())
                   if name.lower() == b"authorization"]
        if len(headers) != 1 or not headers[0].startswith(b"Bearer "):
            return await self._reject(send, 401, "AI_AUTH_REQUIRED")
        supplied = headers[0][7:]
        if not supplied or not hmac.compare_digest(supplied, self._token):
            return await self._reject(send, 401, "AI_AUTH_REQUIRED")
        return await self.app(scope, receive, send)
