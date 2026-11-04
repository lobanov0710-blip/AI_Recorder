"""Local-only device credential registry for a future HTTPS pairing workflow.

No network enrollment endpoint exists. Administrator provisioning and Android
Keystore storage are separate, later steps. This module is not imported by the
working AI-Recorder FastAPI server.
"""
from __future__ import annotations

import hashlib
import os
import re
import secrets
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote


class DeviceRegistryError(RuntimeError):
    """Fail-closed registry configuration/storage error."""


@dataclass(frozen=True)
class IssuedCredential:
    device_id: str
    token: str  # Revealed to the trusted provisioning caller once; never persisted.


@dataclass(frozen=True)
class DeviceInfo:
    device_id: str
    label: str
    created_at: int
    revoked_at: int | None


class DeviceRegistry:
    """Stores only SHA-256 digests of 256-bit random tokens in SQLite.

    The tokens are unguessable; SHA-256 is used to avoid writing plaintext.
    The file's OS permissions still matter. On Windows use restrictive NTFS ACLs.
    Each verification opens the database anew, so revocation takes effect
    across workers without stale per-process caches.
    """

    TOKEN_PREFIX = b"ar1_"
    MAX_ACTIVE = 32
    _FORMAT = re.compile(rb"^ar1_[A-Za-z0-9_-]{43}$")
    _DEVICE_ID = re.compile(r"^[0-9a-f]{24}$")
    _SCHEMA = """CREATE TABLE IF NOT EXISTS devices (
        device_id TEXT PRIMARY KEY NOT NULL,
        label TEXT NOT NULL,
        token_digest BLOB NOT NULL UNIQUE,
        created_at INTEGER NOT NULL,
        revoked_at INTEGER
    )"""

    def __init__(self, path: str | Path, *, initialize: bool = False,
                 max_active: int = MAX_ACTIVE):
        self.path = Path(path).expanduser()
        if not self.path.is_absolute() or max_active < 1 or max_active > 128:
            raise DeviceRegistryError("Invalid registry configuration")
        self.max_active = max_active
        if self.path.is_symlink():
            raise DeviceRegistryError("Registry cannot be a symbolic link")

        if initialize:
            if not self.path.parent.is_dir():
                raise DeviceRegistryError("Registry parent directory missing")
            try:
                fd = os.open(str(self.path),
                             os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                os.close(fd)
            except FileExistsError as exc:
                raise DeviceRegistryError("Registry already exists") from exc
            try:
                with self._connect(read_only=False) as connection:
                    connection.execute(self._SCHEMA)
                    connection.commit()
            except sqlite3.DatabaseError as exc:
                raise DeviceRegistryError("Registry initialization failed") from exc
        elif not self.path.is_file():
            raise DeviceRegistryError("Registry is missing")

    def _connect(self, *, read_only: bool):
        if self.path.is_symlink() or not self.path.is_file():
            raise DeviceRegistryError("Registry is unavailable")
        try:
            if read_only:
                uri = "file:" + quote(str(self.path.resolve()), safe="/:") + "?mode=ro"
                return sqlite3.connect(uri, timeout=2.0, uri=True)
            return sqlite3.connect(str(self.path), timeout=2.0)
        except sqlite3.Error as exc:
            raise DeviceRegistryError("Registry unavailable") from exc

    @staticmethod
    def _digest(token: bytes) -> bytes:
        return hashlib.sha256(b"AIRecorder/device-token/v1\x00" + token).digest()

    def issue(self, label: str) -> IssuedCredential:
        if (not isinstance(label, str) or not 1 <= len(label.strip()) <= 64
                or any(ord(ch) < 32 or ord(ch) == 127 for ch in label)):
            raise ValueError("Invalid device label")
        device_id = secrets.token_hex(12)
        token = "ar1_" + secrets.token_urlsafe(32)
        token_bytes = token.encode("ascii")
        now = int(time.time())

        connection = self._connect(read_only=False)
        try:
            connection.execute("BEGIN IMMEDIATE")
            active = connection.execute(
                "SELECT COUNT(*) FROM devices WHERE revoked_at IS NULL"
            ).fetchone()[0]
            if active >= self.max_active:
                raise DeviceRegistryError("Active device limit reached")
            connection.execute(
                "INSERT INTO devices(device_id,label,token_digest,created_at) "
                "VALUES(?,?,?,?)",
                (device_id, label.strip(), self._digest(token_bytes), now),
            )
            connection.commit()
        except sqlite3.DatabaseError as exc:
            connection.rollback()
            raise DeviceRegistryError("Registry write failed") from exc
        finally:
            connection.close()
        return IssuedCredential(device_id, token)

    def verify(self, token: bytes) -> bool:
        if not isinstance(token, bytes) or self._FORMAT.fullmatch(token) is None:
            return False
        connection = self._connect(read_only=True)
        try:
            row = connection.execute(
                "SELECT revoked_at FROM devices WHERE token_digest=?",
                (self._digest(token),),
            ).fetchone()
            return row is not None and row[0] is None
        except sqlite3.DatabaseError as exc:
            raise DeviceRegistryError("Registry read failed") from exc
        finally:
            connection.close()

    def revoke(self, device_id: str) -> bool:
        if not isinstance(device_id, str) or not self._DEVICE_ID.fullmatch(device_id):
            return False
        connection = self._connect(read_only=False)
        try:
            with connection:
                cursor = connection.execute(
                    "UPDATE devices SET revoked_at=? "
                    "WHERE device_id=? AND revoked_at IS NULL",
                    (int(time.time()), device_id),
                )
                return cursor.rowcount == 1
        except sqlite3.DatabaseError as exc:
            raise DeviceRegistryError("Registry update failed") from exc
        finally:
            connection.close()

    def list_devices(self) -> list[DeviceInfo]:
        connection = self._connect(read_only=True)
        try:
            rows = connection.execute(
                "SELECT device_id,label,created_at,revoked_at "
                "FROM devices ORDER BY created_at, device_id"
            ).fetchall()
            return [DeviceInfo(*row) for row in rows]
        except sqlite3.DatabaseError as exc:
            raise DeviceRegistryError("Registry read failed") from exc
        finally:
            connection.close()
