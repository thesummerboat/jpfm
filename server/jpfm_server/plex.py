"""Vem är det som frågar? Kontrolleras mot plex.tv med användarens egen Plex-token."""

from __future__ import annotations

import time
from dataclasses import dataclass

import httpx

PLEX_TV = "https://plex.tv"


class PlexAuthError(Exception):
    pass


@dataclass(frozen=True)
class PlexUser:
    id: int
    name: str
    thumb: str | None


class PlexAuth:
    """Identifierar användare och ser om de äger eller har delad tillgång till en Plex-server."""

    def __init__(self, http: httpx.AsyncClient, client_id: str, ttl: float = 600):
        self._http = http
        self._client_id = client_id
        self._ttl = ttl
        self._users: dict[str, tuple[float, PlexUser]] = {}
        self._access: dict[tuple[str, str], tuple[float, str | None]] = {}

    def _headers(self, token: str) -> dict[str, str]:
        return {
            "Accept": "application/json",
            "X-Plex-Token": token,
            "X-Plex-Client-Identifier": self._client_id,
            "X-Plex-Product": "JPFM Server",
        }

    async def identify(self, token: str) -> PlexUser:
        if not token:
            raise PlexAuthError("Ingen Plex-inloggning")
        hit = self._users.get(token)
        if hit and time.time() - hit[0] < self._ttl:
            return hit[1]
        try:
            r = await self._http.get(f"{PLEX_TV}/api/v2/user", headers=self._headers(token), timeout=15)
        except httpx.HTTPError as e:
            raise PlexAuthError("Når inte plex.tv") from e
        if r.status_code in (401, 403):
            raise PlexAuthError("Plex-inloggningen gäller inte längre")
        r.raise_for_status()
        o = r.json()
        user = PlexUser(
            id=int(o["id"]),
            name=o.get("title") or o.get("username") or "Plex-användare",
            thumb=o.get("thumb") or None,
        )
        self._users[token] = (time.time(), user)
        return user

    async def access(self, token: str, server_id: str) -> str | None:
        """'owned', 'shared' eller None beroende på om användaren når Plex-servern."""
        key = (token, server_id)
        hit = self._access.get(key)
        if hit and time.time() - hit[0] < self._ttl:
            return hit[1]
        try:
            r = await self._http.get(
                f"{PLEX_TV}/api/v2/resources",
                params={"includeHttps": 1, "includeRelay": 1},
                headers=self._headers(token),
                timeout=20,
            )
        except httpx.HTTPError as e:
            raise PlexAuthError("Når inte plex.tv") from e
        if r.status_code in (401, 403):
            raise PlexAuthError("Plex-inloggningen gäller inte längre")
        r.raise_for_status()
        result: str | None = None
        for res in r.json():
            if res.get("clientIdentifier") == server_id and "server" in (res.get("provides") or ""):
                result = "owned" if res.get("owned") else "shared"
                break
        self._access[key] = (time.time(), result)
        return result

    def forget(self, token: str) -> None:
        self._users.pop(token, None)
        for k in [k for k in self._access if k[0] == token]:
            self._access.pop(k, None)
