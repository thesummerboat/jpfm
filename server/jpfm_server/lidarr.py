"""Lidarrs API (v1): söka, önska och följa nedladdningar."""

from __future__ import annotations

import copy
from dataclasses import dataclass
from typing import Any

import httpx


class LidarrError(Exception):
    """Lidarr svarade, men sa nej (fel nyckel, ogiltigt album …)."""


class LidarrUnreachable(Exception):
    """Lidarr går inte att nå just nu."""


@dataclass
class LidarrConfig:
    url: str
    api_key: str
    root_folder: str
    quality_profile_id: int
    metadata_profile_id: int

    @staticmethod
    def from_dict(d: dict | None) -> "LidarrConfig | None":
        if not d or not d.get("url") or not d.get("api_key"):
            return None
        return LidarrConfig(
            url=d["url"],
            api_key=d["api_key"],
            root_folder=d.get("root_folder", ""),
            quality_profile_id=int(d.get("quality_profile_id") or 0),
            metadata_profile_id=int(d.get("metadata_profile_id") or 0),
        )


def normalize_url(raw: str) -> str:
    u = (raw or "").strip().rstrip("/")
    if u and not u.startswith(("http://", "https://")):
        u = "http://" + u
    return u


def _s(o: dict, key: str) -> str | None:
    v = o.get(key)
    if v is None:
        return None
    v = str(v).strip()
    return v or None


def album(o: dict) -> dict | None:
    """Det vi bryr oss om i ett album från Lidarr."""
    foreign = _s(o, "foreignAlbumId")
    if not foreign:
        return None
    artist = o.get("artist") or {}
    stats = o.get("statistics") or {}
    cover = _s(o, "remoteCover")
    if not cover:
        for img in o.get("images") or []:
            if str(img.get("coverType", "")).lower() == "cover" and _s(img, "remoteUrl"):
                cover = _s(img, "remoteUrl")
                break
    tracks = int(stats.get("trackCount") or 0) or int(stats.get("totalTrackCount") or 0)
    files = int(stats.get("trackFileCount") or 0)
    date = _s(o, "releaseDate")
    return {
        "foreignAlbumId": foreign,
        "title": _s(o, "title") or "",
        "artist": _s(artist, "artistName") or "",
        "type": _s(o, "albumType"),
        "year": int(date[:4]) if date and date[:4].isdigit() else None,
        "cover": cover,
        "lidarrId": int(o.get("id") or 0),
        "monitored": bool(o.get("monitored")),
        "trackFiles": files,
        "tracks": tracks,
        "complete": tracks > 0 and files >= tracks,
    }


def add_body(raw: dict, cfg: LidarrConfig) -> dict:
    """Lägg till albumet och bevaka bara det – inte artistens andra skivor."""
    o = copy.deepcopy(raw)
    foreign = o.get("foreignAlbumId")
    o["monitored"] = True
    o["anyReleaseOk"] = True
    o["addOptions"] = {"searchForNewAlbum": True}
    artist = o.setdefault("artist", {})
    artist.update(
        {
            "qualityProfileId": cfg.quality_profile_id,
            "metadataProfileId": cfg.metadata_profile_id,
            "rootFolderPath": cfg.root_folder,
            "monitored": True,
            "monitorNewItems": "none",
            "addOptions": {
                "monitor": "none",
                "albumsToMonitor": [foreign],
                "monitored": True,
                "searchForMissingAlbums": False,
            },
        }
    )
    return o


def queue_items(items: list[dict]) -> list[dict]:
    out = []
    for o in items:
        album_id = int(o.get("albumId") or 0)
        if album_id <= 0:
            continue
        size = float(o.get("size") or 0)
        left = float(o.get("sizeleft") or 0)
        out.append(
            {
                "albumId": album_id,
                "progress": max(0.0, min(1.0, (size - left) / size)) if size > 0 else 0.0,
                "state": _s(o, "trackedDownloadState") or _s(o, "status"),
                "error": _s(o, "errorMessage"),
            }
        )
    return out


def error_message(body: str) -> str | None:
    import json

    try:
        d = json.loads(body)
    except ValueError:
        return None
    if isinstance(d, list) and d and isinstance(d[0], dict):
        return _s(d[0], "errorMessage")
    if isinstance(d, dict):
        return _s(d, "message") or _s(d, "error")
    return None


class LidarrClient:
    def __init__(self, http: httpx.AsyncClient):
        self._http = http

    async def _call(self, method: str, url: str, key: str, path: str, **kw: Any) -> Any:
        try:
            r = await self._http.request(
                method,
                url + path,
                headers={"X-Api-Key": key, "Accept": "application/json"},
                timeout=httpx.Timeout(30, connect=6),
                **kw,
            )
        except httpx.InvalidURL as e:
            raise LidarrError("Ogiltig adress") from e
        except httpx.HTTPError as e:
            raise LidarrUnreachable("Når inte Lidarr") from e
        if r.status_code == 401:
            raise LidarrError("Fel API-nyckel")
        if r.status_code >= 400:
            raise LidarrError(error_message(r.text) or f"Lidarr svarade {r.status_code}")
        if not r.content:
            return None
        try:
            return r.json()
        except ValueError as e:
            raise LidarrError("Det där verkar inte vara Lidarr") from e

    async def setup(self, url: str, key: str) -> dict:
        status = await self._call("GET", url, key, "/api/v1/system/status")
        if not isinstance(status, dict):
            raise LidarrError("Det där verkar inte vara Lidarr")
        roots = await self._call("GET", url, key, "/api/v1/rootfolder")
        quality = await self._call("GET", url, key, "/api/v1/qualityprofile")
        metadata = await self._call("GET", url, key, "/api/v1/metadataprofile")
        return {
            "version": status.get("version") or "?",
            "roots": [
                {
                    "path": r["path"],
                    "name": r.get("name") or r["path"],
                    "defaultQualityProfileId": int(r.get("defaultQualityProfileId") or 0),
                    "defaultMetadataProfileId": int(r.get("defaultMetadataProfileId") or 0),
                    "freeSpace": r.get("freeSpace"),
                }
                for r in roots or []
                if r.get("path")
            ],
            "quality": [{"id": int(p["id"]), "name": p.get("name") or f"Profil {p['id']}"} for p in quality or [] if p.get("id")],
            "metadata": [{"id": int(p["id"]), "name": p.get("name") or f"Profil {p['id']}"} for p in metadata or [] if p.get("id")],
        }

    async def lookup(self, cfg: LidarrConfig, term: str) -> list[dict]:
        """Råa album från Lidarr (behövs för att kunna lägga till dem)."""
        res = await self._call("GET", cfg.url, cfg.api_key, "/api/v1/album/lookup", params={"term": term})
        return [o for o in res or [] if isinstance(o, dict) and o.get("foreignAlbumId")]

    async def albums_by_id(self, cfg: LidarrConfig, ids: list[int]) -> list[dict]:
        if not ids:
            return []
        res = await self._call("GET", cfg.url, cfg.api_key, "/api/v1/album", params=[("albumIds", i) for i in ids])
        return [a for a in (album(o) for o in res or []) if a]

    async def queue(self, cfg: LidarrConfig) -> list[dict]:
        res = await self._call(
            "GET", cfg.url, cfg.api_key, "/api/v1/queue/details", params={"includeAlbum": "false", "includeArtist": "false"}
        )
        return queue_items(res or [])

    async def find(self, cfg: LidarrConfig, foreign_album_id: str) -> int | None:
        try:
            res = await self._call("GET", cfg.url, cfg.api_key, "/api/v1/album", params={"foreignAlbumId": foreign_album_id})
        except LidarrError:
            return None
        for o in res or []:
            if int(o.get("id") or 0) > 0:
                return int(o["id"])
        return None

    async def request(self, cfg: LidarrConfig, raw: dict) -> int:
        """Önska ett album. Returnerar Lidarrs id för det."""
        foreign = raw["foreignAlbumId"]
        existing = int(raw.get("id") or 0) or await self.find(cfg, foreign)
        if existing:
            await self._monitor_and_search(cfg, existing)
            return existing
        try:
            res = await self._call("POST", cfg.url, cfg.api_key, "/api/v1/album", json=add_body(raw, cfg))
        except LidarrError:
            # Lagt till under tiden? Då räcker det att bevaka och söka.
            found = await self.find(cfg, foreign)
            if not found:
                raise
            await self._monitor_and_search(cfg, found)
            return found
        new_id = int((res or {}).get("id") or 0) if isinstance(res, dict) else 0
        if new_id:
            try:
                await self._search(cfg, new_id)
            except LidarrError:
                pass
            return new_id
        found = await self.find(cfg, foreign)
        if not found:
            raise LidarrError("Lidarr svarade inte med något album")
        return found

    async def _monitor_and_search(self, cfg: LidarrConfig, album_id: int) -> None:
        await self._call("PUT", cfg.url, cfg.api_key, "/api/v1/album/monitor", json={"albumIds": [album_id], "monitored": True})
        await self._search(cfg, album_id)

    async def _search(self, cfg: LidarrConfig, album_id: int) -> None:
        await self._call("POST", cfg.url, cfg.api_key, "/api/v1/command", json={"name": "AlbumSearch", "albumIds": [album_id]})
