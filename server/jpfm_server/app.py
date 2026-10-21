"""HTTP-API:t som JPFM-appen pratar med."""


import asyncio
import contextlib
import logging
import os
import uuid
from dataclasses import asdict
from pathlib import Path
from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from . import VERSION
from . import store as st
from .lidarr import LidarrClient, LidarrError, LidarrUnreachable
from .plex import PlexAuth, PlexAuthError, PlexUser
from .service import Conflict, NotAllowed, NotFound, Service

log = logging.getLogger("jpfm")


class Caller(BaseModel):
    id: int
    name: str
    thumb: str | None
    role: str
    token: str


class ClaimBody(BaseModel):
    serverId: str


class LidarrTestBody(BaseModel):
    url: str
    apiKey: str | None = None


class LidarrSaveBody(BaseModel):
    url: str
    apiKey: str | None = None
    rootFolder: str
    qualityProfileId: int
    metadataProfileId: int


class RequestBody(BaseModel):
    foreignAlbumId: str
    song: str | None = None


class DenyBody(BaseModel):
    reason: str | None = None


class UserBody(BaseModel):
    autoApprove: bool | None = None
    blocked: bool | None = None


def create_app(data_dir: Path, http: httpx.AsyncClient | None = None, sync_every: float = 60) -> FastAPI:
    store = st.Store(data_dir / "jpfm.db")
    client_id = store.get("client_id")
    if not client_id:
        client_id = "jpfm-server-" + uuid.uuid4().hex
        store.set("client_id", client_id)
    own_http = http is None
    http = http or httpx.AsyncClient(follow_redirects=True)
    service = Service(store, PlexAuth(http, client_id), LidarrClient(http))

    async def worker() -> None:
        while True:
            try:
                await service.sync()
            except Exception:  # noqa: BLE001 – jobbet får aldrig dö
                log.exception("Synkningen misslyckades")
            service.wake.clear()
            with contextlib.suppress(asyncio.TimeoutError):
                await asyncio.wait_for(service.wake.wait(), timeout=sync_every)

    @contextlib.asynccontextmanager
    async def lifespan(_: FastAPI):
        task = asyncio.create_task(worker()) if sync_every > 0 else None
        yield
        if task:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
        if own_http:
            await http.aclose()

    app = FastAPI(title="JPFM Server", version=VERSION, lifespan=lifespan)
    app.state.service = service

    # ---------- fel ----------

    def handler(status: int):
        async def h(_: Request, exc: Exception) -> JSONResponse:
            return JSONResponse(status_code=status, content={"detail": str(exc)})

        return h

    app.add_exception_handler(NotAllowed, handler(403))
    app.add_exception_handler(NotFound, handler(404))
    app.add_exception_handler(Conflict, handler(409))
    app.add_exception_handler(LidarrError, handler(502))
    app.add_exception_handler(LidarrUnreachable, handler(503))
    app.add_exception_handler(PlexAuthError, handler(401))

    # ---------- inloggning ----------

    async def caller(
        x_plex_token: Annotated[str | None, Header()] = None,
    ) -> Caller:
        token = (x_plex_token or "").strip()
        user: PlexUser = await service.plex.identify(token)
        role = await service.role(user, token)
        store.touch_user(user.id, user.name, user.thumb)
        return Caller(id=user.id, name=user.name, thumb=user.thumb, role=role, token=token)

    async def member(c: Annotated[Caller, Depends(caller)]) -> Caller:
        if c.role == "unclaimed":
            raise HTTPException(409, "JPFM-servern är inte kopplad än – ägaren av Plex-servern behöver ansluta först")
        if c.role not in ("owner", "member"):
            raise HTTPException(403, "Du har inte tillgång till den här Plex-servern")
        return c

    async def owner(c: Annotated[Caller, Depends(member)]) -> Caller:
        if c.role != "owner":
            raise HTTPException(403, "Bara ägaren kan göra det")
        return c

    Member = Annotated[Caller, Depends(member)]
    Owner = Annotated[Caller, Depends(owner)]

    # ---------- allmänt ----------

    @app.get("/api/health")
    async def health() -> dict:
        return {"app": "jpfm-server", "version": VERSION, "claimed": service.server_id is not None}

    @app.get("/api/me")
    async def me(c: Annotated[Caller, Depends(caller)]) -> dict:
        u = store.user(c.id)
        return {
            "id": c.id,
            "name": c.name,
            "role": c.role,
            "ownerName": store.get("owner_name"),
            "lidarr": service.lidarr_config() is not None,
            "autoApprove": c.role == "owner" or bool(u and u.auto_approve),
            "blocked": bool(u and u.blocked),
            "pending": len(store.requests(statuses=(st.PENDING,))) if c.role == "owner" else 0,
        }

    @app.post("/api/claim")
    async def claim(body: ClaimBody, c: Annotated[Caller, Depends(caller)]) -> dict:
        await service.claim(PlexUser(c.id, c.name, c.thumb), c.token, body.serverId)
        return {"ok": True, "role": "owner"}

    # ---------- Lidarr (ägaren) ----------

    @app.get("/api/lidarr")
    async def lidarr_settings(_: Owner) -> dict:
        cfg = service.lidarr_config()
        if not cfg:
            return {"configured": False}
        return {
            "configured": True,
            "url": cfg.url,
            "hasKey": bool(cfg.api_key),
            "rootFolder": cfg.root_folder,
            "qualityProfileId": cfg.quality_profile_id,
            "metadataProfileId": cfg.metadata_profile_id,
        }

    @app.post("/api/lidarr/test")
    async def lidarr_test(body: LidarrTestBody, _: Owner) -> dict:
        return await service.test_lidarr(body.url, body.apiKey)

    @app.put("/api/lidarr")
    async def lidarr_save(body: LidarrSaveBody, _: Owner) -> dict:
        service.save_lidarr(body.url, body.apiKey, body.rootFolder, body.qualityProfileId, body.metadataProfileId)
        service.wake.set()
        return {"ok": True}

    # ---------- söka och önska ----------

    @app.get("/api/search")
    async def search(c: Member, term: Annotated[str, Query(min_length=1)], fallback: str | None = None) -> list[dict]:
        return await service.search(term, fallback, c.id)

    @app.get("/api/requests")
    async def requests(c: Member) -> list[dict]:
        rows = store.requests() if c.role == "owner" else store.requests(user_id=c.id)
        return [r.public(c.id) for r in rows]

    @app.post("/api/requests")
    async def create(body: RequestBody, c: Member) -> dict:
        r, existed = await service.create(PlexUser(c.id, c.name, c.thumb), c.role, body.foreignAlbumId, body.song)
        return {**r.public(c.id), "existed": existed}

    @app.post("/api/requests/{rid}/approve")
    async def approve(rid: int, c: Owner) -> dict:
        return (await service.approve(rid)).public(c.id)

    @app.post("/api/requests/{rid}/deny")
    async def deny(rid: int, body: DenyBody, c: Owner) -> dict:
        return service.deny(rid, body.reason).public(c.id)

    @app.delete("/api/requests/{rid}")
    async def delete(rid: int, c: Member) -> dict:
        service.remove(rid, c.id, c.role == "owner")
        return {"ok": True}

    # ---------- användare (ägaren) ----------

    @app.get("/api/users")
    async def users(_: Owner) -> list[dict]:
        owner_id = service.owner_id
        counts: dict[int, int] = {}
        for r in store.requests(limit=10_000):
            counts[r.user_id] = counts.get(r.user_id, 0) + 1
        return [
            {**asdict(u), "owner": u.id == owner_id, "requests": counts.get(u.id, 0)}
            for u in store.users()
        ]

    @app.put("/api/users/{uid}")
    async def update_user(uid: int, body: UserBody, _: Owner) -> dict:
        if uid == service.owner_id:
            raise HTTPException(400, "Ägaren kan inte ändras")
        u = store.update_user(uid, body.autoApprove, body.blocked)
        if not u:
            raise HTTPException(404, "Användaren finns inte")
        return asdict(u)

    return app


def main() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    data = Path(os.environ.get("DATA_DIR", "/data"))
    port = int(os.environ.get("PORT", "8687"))
    uvicorn.run(create_app(data), host="0.0.0.0", port=port, log_level="info")
