"""SQLite-lagring: inställningar, användare och önskningar."""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any

# Status för en önskan.
PENDING = "pending"        # väntar på ägarens godkännande
DENIED = "denied"          # nekad av ägaren
APPROVED = "approved"      # godkänd, ska skickas till Lidarr
SEARCHING = "searching"    # i Lidarr, letar efter en nedladdning
DOWNLOADING = "downloading"
IMPORTING = "importing"
DONE = "done"
FAILED = "failed"

ACTIVE = (PENDING, APPROVED, SEARCHING, DOWNLOADING, IMPORTING)
IN_LIDARR = (SEARCHING, DOWNLOADING, IMPORTING)

SCHEMA = """
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    thumb TEXT,
    auto_approve INTEGER NOT NULL DEFAULT 0,
    blocked INTEGER NOT NULL DEFAULT 0,
    last_seen REAL NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    foreign_album_id TEXT NOT NULL,
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    type TEXT,
    year INTEGER,
    cover TEXT,
    song TEXT,
    album_json TEXT,
    user_id INTEGER NOT NULL,
    user_name TEXT NOT NULL,
    status TEXT NOT NULL,
    lidarr_id INTEGER NOT NULL DEFAULT 0,
    progress REAL NOT NULL DEFAULT 0,
    message TEXT,
    reason TEXT,
    created REAL NOT NULL,
    updated REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS requests_album ON requests (foreign_album_id);
CREATE INDEX IF NOT EXISTS requests_user ON requests (user_id);
"""


@dataclass
class Req:
    id: int
    foreign_album_id: str
    title: str
    artist: str
    type: str | None
    year: int | None
    cover: str | None
    song: str | None
    album_json: str | None
    user_id: int
    user_name: str
    status: str
    lidarr_id: int = 0
    progress: float = 0.0
    message: str | None = None
    reason: str | None = None
    created: float = field(default_factory=time.time)
    updated: float = field(default_factory=time.time)

    def public(self, viewer_id: int | None = None) -> dict[str, Any]:
        d = asdict(self)
        d.pop("album_json", None)
        d["mine"] = viewer_id is not None and viewer_id == self.user_id
        return d


@dataclass
class User:
    id: int
    name: str
    thumb: str | None = None
    auto_approve: bool = False
    blocked: bool = False
    last_seen: float = 0.0


class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock:
            self._db.executescript(SCHEMA)
            self._db.commit()

    # ---------- inställningar ----------

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            row = self._db.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        return json.loads(row["value"]) if row else default

    def set(self, key: str, value: Any) -> None:
        with self._lock:
            if value is None:
                self._db.execute("DELETE FROM settings WHERE key = ?", (key,))
            else:
                self._db.execute(
                    "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    (key, json.dumps(value)),
                )
            self._db.commit()

    # ---------- användare ----------

    def touch_user(self, uid: int, name: str, thumb: str | None) -> User:
        now = time.time()
        with self._lock:
            self._db.execute(
                "INSERT INTO users (id, name, thumb, last_seen) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET name = excluded.name, thumb = excluded.thumb, last_seen = excluded.last_seen",
                (uid, name, thumb, now),
            )
            self._db.commit()
        return self.user(uid)  # type: ignore[return-value]

    def user(self, uid: int) -> User | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM users WHERE id = ?", (uid,)).fetchone()
        return _user(row) if row else None

    def users(self) -> list[User]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM users ORDER BY last_seen DESC").fetchall()
        return [_user(r) for r in rows]

    def update_user(self, uid: int, auto_approve: bool | None = None, blocked: bool | None = None) -> User | None:
        with self._lock:
            if auto_approve is not None:
                self._db.execute("UPDATE users SET auto_approve = ? WHERE id = ?", (int(auto_approve), uid))
            if blocked is not None:
                self._db.execute("UPDATE users SET blocked = ? WHERE id = ?", (int(blocked), uid))
            self._db.commit()
        return self.user(uid)

    # ---------- önskningar ----------

    def add(self, r: Req) -> Req:
        d = asdict(r)
        d.pop("id")
        cols = ", ".join(d)
        marks = ", ".join("?" for _ in d)
        with self._lock:
            cur = self._db.execute(f"INSERT INTO requests ({cols}) VALUES ({marks})", tuple(d.values()))
            self._db.commit()
            r.id = int(cur.lastrowid)
        return r

    def save(self, r: Req) -> Req:
        r.updated = time.time()
        d = asdict(r)
        rid = d.pop("id")
        sets = ", ".join(f"{k} = ?" for k in d)
        with self._lock:
            self._db.execute(f"UPDATE requests SET {sets} WHERE id = ?", (*d.values(), rid))
            self._db.commit()
        return r

    def request(self, rid: int) -> Req | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM requests WHERE id = ?", (rid,)).fetchone()
        return _req(row) if row else None

    def requests(self, user_id: int | None = None, statuses: tuple[str, ...] | None = None, limit: int = 300) -> list[Req]:
        q = "SELECT * FROM requests"
        args: list[Any] = []
        where = []
        if user_id is not None:
            where.append("user_id = ?")
            args.append(user_id)
        if statuses:
            where.append("status IN (%s)" % ", ".join("?" for _ in statuses))
            args.extend(statuses)
        if where:
            q += " WHERE " + " AND ".join(where)
        q += " ORDER BY created DESC LIMIT ?"
        args.append(limit)
        with self._lock:
            rows = self._db.execute(q, args).fetchall()
        return [_req(r) for r in rows]

    def by_album(self, foreign_album_id: str) -> list[Req]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM requests WHERE foreign_album_id = ? ORDER BY created DESC", (foreign_album_id,)
            ).fetchall()
        return [_req(r) for r in rows]

    def delete(self, rid: int) -> None:
        with self._lock:
            self._db.execute("DELETE FROM requests WHERE id = ?", (rid,))
            self._db.commit()


def _user(row: sqlite3.Row) -> User:
    return User(
        id=row["id"],
        name=row["name"],
        thumb=row["thumb"],
        auto_approve=bool(row["auto_approve"]),
        blocked=bool(row["blocked"]),
        last_seen=row["last_seen"],
    )


def _req(row: sqlite3.Row) -> Req:
    return Req(**{k: row[k] for k in row.keys()})
