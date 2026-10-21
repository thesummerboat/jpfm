"""Önskningar: skapa, godkänna, neka, skicka till Lidarr och följa hur det går."""

from __future__ import annotations

import asyncio
import json
import time

from . import store as st
from .lidarr import LidarrClient, LidarrConfig, LidarrError, LidarrUnreachable, album
from .plex import PlexAuth, PlexUser

NOTHING_FOUND_AFTER = 30 * 60
RAW_TTL = 3600


class NotAllowed(Exception):
    pass


class NotFound(Exception):
    pass


class Conflict(Exception):
    pass


class Service:
    def __init__(self, store: st.Store, plex: PlexAuth, lidarr: LidarrClient):
        self.store = store
        self.plex = plex
        self.lidarr = lidarr
        self._raw: dict[str, tuple[float, dict]] = {}
        self._sync_lock = asyncio.Lock()
        self.wake = asyncio.Event()

    # ---------- vem och vad ----------

    @property
    def server_id(self) -> str | None:
        return self.store.get("server_id")

    @property
    def owner_id(self) -> int | None:
        return self.store.get("owner_id")

    def lidarr_config(self) -> LidarrConfig | None:
        return LidarrConfig.from_dict(self.store.get("lidarr"))

    async def role(self, user: PlexUser, token: str) -> str:
        """'owner', 'member', 'unclaimed' eller 'denied'."""
        sid = self.server_id
        if not sid:
            return "unclaimed"
        if user.id == self.owner_id:
            return "owner"
        return "member" if await self.plex.access(token, sid) else "denied"

    async def claim(self, user: PlexUser, token: str, server_id: str) -> None:
        """Den som äger Plex-servern kopplar JPFM-servern till den (en gång)."""
        if self.server_id and self.owner_id != user.id:
            raise Conflict("JPFM-servern är redan kopplad till ett annat Plex-konto")
        if await self.plex.access(token, server_id) != "owned":
            raise NotAllowed("Bara den som äger Plex-servern kan koppla JPFM-servern")
        self.store.set("server_id", server_id)
        self.store.set("owner_id", user.id)
        self.store.set("owner_name", user.name)
        self.store.update_user(user.id, auto_approve=True)

    # ---------- Lidarr-inställningar ----------

    async def test_lidarr(self, url: str, key: str | None) -> dict:
        from .lidarr import normalize_url

        cfg = self.lidarr_config()
        api_key = (key or "").strip() or (cfg.api_key if cfg else "")
        if not api_key:
            raise NotAllowed("Ange API-nyckeln")
        return await self.lidarr.setup(normalize_url(url), api_key)

    def save_lidarr(self, url: str, key: str | None, root: str, quality: int, metadata: int) -> None:
        from .lidarr import normalize_url

        old = self.lidarr_config()
        api_key = (key or "").strip() or (old.api_key if old else "")
        if not api_key:
            raise NotAllowed("Ange API-nyckeln")
        self.store.set(
            "lidarr",
            {
                "url": normalize_url(url),
                "api_key": api_key,
                "root_folder": root,
                "quality_profile_id": int(quality),
                "metadata_profile_id": int(metadata),
            },
        )

    def _cfg(self) -> LidarrConfig:
        cfg = self.lidarr_config()
        if not cfg:
            raise NotAllowed("Ägaren har inte kopplat Lidarr än")
        return cfg

    # ---------- söka ----------

    async def search(self, term: str, fallback: str | None, viewer: int) -> list[dict]:
        cfg = self._cfg()
        raws = await self.lidarr.lookup(cfg, term)
        if not raws and fallback and fallback.strip().lower() != term.strip().lower():
            raws = await self.lidarr.lookup(cfg, fallback)
        now = time.time()
        out = []
        for raw in raws[:40]:
            a = album(raw)
            if not a:
                continue
            self._raw[a["foreignAlbumId"]] = (now, raw)
            existing = self._current(a["foreignAlbumId"])
            a["request"] = existing.public(viewer) if existing else None
            out.append(a)
        self._prune_raw()
        return out

    def _prune_raw(self) -> None:
        now = time.time()
        for k in [k for k, (t, _) in self._raw.items() if now - t > RAW_TTL]:
            self._raw.pop(k, None)

    def _current(self, foreign_album_id: str) -> st.Req | None:
        """Pågående eller klar önskan för albumet (nekade och misslyckade räknas inte)."""
        for r in self.store.by_album(foreign_album_id):
            if r.status in st.ACTIVE or r.status == st.DONE:
                return r
        return None

    async def _raw_album(self, cfg: LidarrConfig, foreign_album_id: str) -> dict:
        hit = self._raw.get(foreign_album_id)
        if hit:
            return hit[1]
        for raw in await self.lidarr.lookup(cfg, f"lidarr:{foreign_album_id}"):
            if raw.get("foreignAlbumId") == foreign_album_id:
                return raw
        raise NotFound("Hittade inte albumet i Lidarr – sök igen")

    # ---------- önska ----------

    async def create(self, user: PlexUser, role: str, foreign_album_id: str, song: str | None) -> tuple[st.Req, bool]:
        """Ny önskan. Returnerar (önskan, fanns redan)."""
        cfg = self._cfg()
        u = self.store.user(user.id)
        if u and u.blocked:
            raise NotAllowed("Du kan inte önska musik här")
        existing = self._current(foreign_album_id)
        if existing:
            if existing.status == st.PENDING and role == "owner":
                await self.approve(existing.id)
                existing = self.store.request(existing.id) or existing
            return existing, True
        raw = await self._raw_album(cfg, foreign_album_id)
        a = album(raw) or {}
        auto = role == "owner" or bool(u and u.auto_approve)
        r = st.Req(
            id=0,
            foreign_album_id=foreign_album_id,
            title=a.get("title") or "",
            artist=a.get("artist") or "",
            type=a.get("type"),
            year=a.get("year"),
            cover=a.get("cover"),
            song=song,
            album_json=json.dumps(raw),
            user_id=user.id,
            user_name=user.name,
            status=st.APPROVED if auto else st.PENDING,
        )
        if a.get("complete"):
            r.status = st.DONE
            r.progress = 1.0
            r.lidarr_id = a.get("lidarrId") or 0
            r.message = "Fanns redan"
        r = self.store.add(r)
        if r.status == st.APPROVED:
            r = await self._send(cfg, r)
        return r, False

    async def approve(self, rid: int) -> st.Req:
        r = self.store.request(rid)
        if not r:
            raise NotFound("Önskan finns inte")
        if r.status not in (st.PENDING, st.DENIED, st.FAILED):
            return r
        r.status = st.APPROVED
        r.reason = None
        r.message = None
        self.store.save(r)
        cfg = self.lidarr_config()
        if cfg:
            r = await self._send(cfg, r)
        return r

    def deny(self, rid: int, reason: str | None) -> st.Req:
        r = self.store.request(rid)
        if not r:
            raise NotFound("Önskan finns inte")
        if r.status not in (st.PENDING, st.APPROVED):
            raise Conflict("Önskan är redan skickad till Lidarr")
        r.status = st.DENIED
        r.reason = (reason or "").strip() or None
        r.message = None
        return self.store.save(r)

    def remove(self, rid: int, user_id: int, owner: bool) -> None:
        r = self.store.request(rid)
        if not r:
            return
        if not owner and r.user_id != user_id:
            raise NotAllowed("Det är inte din önskan")
        if not owner and r.status in st.IN_LIDARR:
            raise Conflict("Önskan är redan på väg och kan inte tas bort")
        self.store.delete(rid)

    async def _send(self, cfg: LidarrConfig, r: st.Req) -> st.Req:
        try:
            raw = json.loads(r.album_json) if r.album_json else await self._raw_album(cfg, r.foreign_album_id)
            r.lidarr_id = await self.lidarr.request(cfg, raw)
            r.status = st.SEARCHING
            r.message = None
        except LidarrUnreachable:
            r.message = "Når inte Lidarr – försöker igen"
        except (LidarrError, NotFound) as e:
            r.status = st.FAILED
            r.message = str(e)
        return self.store.save(r)

    # ---------- följa upp ----------

    async def sync(self) -> None:
        cfg = self.lidarr_config()
        if not cfg:
            return
        async with self._sync_lock:
            for r in self.store.requests(statuses=(st.APPROVED,)):
                r = await self._send(cfg, r)
                if r.status == st.APPROVED:
                    return  # Lidarr går inte att nå: vänta till nästa gång.
            active = self.store.requests(statuses=st.IN_LIDARR)
            ids = sorted({r.lidarr_id for r in active if r.lidarr_id})
            if not ids:
                return
            try:
                albums = {a["lidarrId"]: a for a in await self.lidarr.albums_by_id(cfg, ids)}
                try:
                    queue = await self.lidarr.queue(cfg)
                except LidarrError:
                    queue = []
            except (LidarrUnreachable, LidarrError):
                return
            by_album: dict[int, list[dict]] = {}
            for q in queue:
                by_album.setdefault(q["albumId"], []).append(q)
            now = time.time()
            for r in active:
                if not r.lidarr_id:
                    continue
                nxt = next_status(r, albums.get(r.lidarr_id), by_album.get(r.lidarr_id, []), now)
                if (nxt.status, round(nxt.progress, 3), nxt.message) != (r.status, round(r.progress, 3), r.message):
                    self.store.save(nxt)


def next_status(r: st.Req, a: dict | None, queue: list[dict], now: float) -> st.Req:
    """Hur det går, utifrån Lidarrs album och nedladdningskö."""
    import copy

    r = copy.copy(r)
    if a is None:
        r.status, r.message = st.FAILED, "Borttaget i Lidarr"
        return r
    if a.get("complete"):
        r.status, r.progress, r.message = st.DONE, 1.0, None
        return r
    if queue:
        q = max(queue, key=lambda x: x["progress"])
        state = (q.get("state") or "").lower()
        if state.startswith("failed"):
            r.status, r.message = st.SEARCHING, q.get("error") or "Nedladdningen misslyckades, Lidarr provar igen"
        elif state.startswith("import"):
            r.status, r.message = st.IMPORTING, None
        else:
            r.status, r.message = st.DOWNLOADING, None
        r.progress = float(q["progress"])
        return r
    if a.get("trackFiles", 0) > 0:
        r.status = st.IMPORTING
        r.progress = a["trackFiles"] / max(1, a.get("tracks") or 1)
        r.message = None
        return r
    r.status, r.progress = st.SEARCHING, 0.0
    r.message = "Inget hittat än – Lidarr fortsätter leta" if now - r.created > NOTHING_FOUND_AFTER else None
    return r
