import json
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from jpfm_server.app import create_app
from jpfm_server.lidarr import add_body, album, LidarrConfig, normalize_url, queue_items, error_message
from jpfm_server.service import next_status
from jpfm_server import store as st

USERS = {
    "owner-token": {"id": 1, "title": "Jonas", "username": "jonas"},
    "friend-token": {"id": 2, "title": "Kompis", "username": "kompis"},
    "friend2-token": {"id": 4, "title": "Kusin", "username": "kusin"},
    "stranger-token": {"id": 3, "title": "Okänd", "username": "okand"},
}
RESOURCES = {
    "owner-token": [{"clientIdentifier": "srv1", "provides": "server", "owned": True}],
    "friend-token": [{"clientIdentifier": "srv1", "provides": "server", "owned": False}],
    "friend2-token": [{"clientIdentifier": "srv1", "provides": "server,player", "owned": False}],
    "stranger-token": [{"clientIdentifier": "other", "provides": "server", "owned": True}],
}

LOOKUP = [
    {
        "id": 0,
        "title": "Housewife",
        "foreignAlbumId": "rg-1",
        "albumType": "Single",
        "releaseDate": "2026-10-15T00:00:00Z",
        "remoteCover": "https://caa/1.jpg",
        "artist": {"artistName": "Tate McRae", "foreignArtistId": "ar-1"},
    },
    {
        "id": 7,
        "title": "Gammal skiva",
        "foreignAlbumId": "rg-2",
        "albumType": "Album",
        "releaseDate": None,
        "statistics": {"trackFileCount": 10, "trackCount": 10},
        "artist": {"artistName": "Kent", "foreignArtistId": "ar-2"},
    },
]


class FakeWorld:
    def __init__(self):
        self.lidarr_up = True
        self.posted = []
        self.commands = []
        self.monitored = []
        self.stats = {"trackFileCount": 0, "trackCount": 1}
        self.queue = []

    def handler(self, req: httpx.Request) -> httpx.Response:
        url = req.url
        if url.host == "plex.tv":
            token = req.headers.get("X-Plex-Token", "")
            if token not in USERS:
                return httpx.Response(401)
            if url.path == "/api/v2/user":
                return httpx.Response(200, json=USERS[token])
            if url.path == "/api/v2/resources":
                return httpx.Response(200, json=RESOURCES[token])
        if url.host == "lidarr":
            if not self.lidarr_up:
                raise httpx.ConnectError("nere", request=req)
            if req.headers.get("X-Api-Key") != "nyckel":
                return httpx.Response(401)
            p = url.path
            if p == "/api/v1/system/status":
                return httpx.Response(200, json={"version": "2.9.0"})
            if p == "/api/v1/rootfolder":
                return httpx.Response(200, json=[{"path": "/music", "name": "Musik", "defaultQualityProfileId": 1, "defaultMetadataProfileId": 2}])
            if p == "/api/v1/qualityprofile":
                return httpx.Response(200, json=[{"id": 1, "name": "Lossless"}])
            if p == "/api/v1/metadataprofile":
                return httpx.Response(200, json=[{"id": 2, "name": "Allt"}])
            if p == "/api/v1/album/lookup":
                term = url.params.get("term")
                if term == "lidarr:rg-1":
                    return httpx.Response(200, json=[LOOKUP[0]])
                if term == "ingenting":
                    return httpx.Response(200, json=[])
                return httpx.Response(200, json=LOOKUP)
            if p == "/api/v1/album" and req.method == "POST":
                body = json.loads(req.content)
                self.posted.append(body)
                return httpx.Response(201, json={**body, "id": 42})
            if p == "/api/v1/album" and req.method == "GET":
                if "foreignAlbumId" in url.params:
                    return httpx.Response(200, json=[])
                ids = url.params.get_list("albumIds")
                return httpx.Response(
                    200,
                    json=[{"id": int(i), "foreignAlbumId": "rg-1", "title": "Housewife", "statistics": self.stats} for i in ids],
                )
            if p == "/api/v1/album/monitor":
                self.monitored.append(json.loads(req.content))
                return httpx.Response(202, json=[])
            if p == "/api/v1/command":
                self.commands.append(json.loads(req.content))
                return httpx.Response(201, json={"id": 1})
            if p == "/api/v1/queue/details":
                return httpx.Response(200, json=self.queue)
        return httpx.Response(404)


@pytest.fixture()
def world(tmp_path: Path):
    w = FakeWorld()
    http = httpx.AsyncClient(transport=httpx.MockTransport(w.handler))
    app = create_app(tmp_path, http=http, sync_every=0)
    with TestClient(app) as client:
        w.client = client
        w.app = app
        yield w


def h(token):
    return {"X-Plex-Token": token}


def setup_owner(w):
    c = w.client
    assert c.post("/api/claim", json={"serverId": "srv1"}, headers=h("owner-token")).status_code == 200
    test = c.post("/api/lidarr/test", json={"url": "lidarr:8686", "apiKey": "nyckel"}, headers=h("owner-token"))
    assert test.status_code == 200, test.text
    assert test.json()["version"] == "2.9.0"
    save = c.put(
        "/api/lidarr",
        json={"url": "http://lidarr:8686", "apiKey": "nyckel", "rootFolder": "/music", "qualityProfileId": 1, "metadataProfileId": 2},
        headers=h("owner-token"),
    )
    assert save.status_code == 200


def test_health_and_unclaimed(world):
    c = world.client
    assert c.get("/api/health").json()["claimed"] is False
    me = c.get("/api/me", headers=h("friend-token")).json()
    assert me["role"] == "unclaimed"
    r = c.get("/api/requests", headers=h("friend-token"))
    assert r.status_code == 409


def test_bad_token_is_rejected(world):
    assert world.client.get("/api/me", headers=h("fel")).status_code == 401
    assert world.client.get("/api/me").status_code == 401


def test_only_plex_owner_can_claim(world):
    c = world.client
    r = c.post("/api/claim", json={"serverId": "srv1"}, headers=h("friend-token"))
    assert r.status_code == 403
    assert c.post("/api/claim", json={"serverId": "srv1"}, headers=h("owner-token")).status_code == 200
    # Någon annan kan inte ta över.
    r = c.post("/api/claim", json={"serverId": "other"}, headers=h("stranger-token"))
    assert r.status_code == 409
    assert c.get("/api/me", headers=h("owner-token")).json()["role"] == "owner"
    assert c.get("/api/me", headers=h("friend-token")).json()["role"] == "member"
    assert c.get("/api/me", headers=h("stranger-token")).json()["role"] == "denied"


def test_owner_request_goes_straight_to_lidarr(world):
    setup_owner(world)
    c = world.client
    found = c.get("/api/search", params={"term": "Tate McRae Housewife"}, headers=h("owner-token")).json()
    assert [a["title"] for a in found] == ["Housewife", "Gammal skiva"]
    assert found[1]["complete"] is True
    r = c.post("/api/requests", json={"foreignAlbumId": "rg-1", "song": "Housewife"}, headers=h("owner-token")).json()
    assert r["status"] == "searching"
    assert r["lidarr_id"] == 42
    body = world.posted[0]
    assert body["artist"]["rootFolderPath"] == "/music"
    assert body["artist"]["addOptions"]["albumsToMonitor"] == ["rg-1"]
    assert body["artist"]["addOptions"]["monitor"] == "none"
    assert {"name": "AlbumSearch", "albumIds": [42]} in world.commands


def test_friend_request_needs_approval_and_can_be_denied(world):
    setup_owner(world)
    c = world.client
    c.get("/api/search", params={"term": "x"}, headers=h("friend-token"))
    r = c.post("/api/requests", json={"foreignAlbumId": "rg-1"}, headers=h("friend-token")).json()
    assert r["status"] == "pending"
    assert world.posted == []
    assert c.get("/api/me", headers=h("owner-token")).json()["pending"] == 1
    # Kompisen ser bara sina egna, ägaren ser alla.
    assert len(c.get("/api/requests", headers=h("friend-token")).json()) == 1
    assert c.get("/api/requests", headers=h("friend2-token")).json() == []
    # Kompisen kan inte godkänna själv.
    assert c.post(f"/api/requests/{r['id']}/approve", headers=h("friend-token")).status_code == 403
    d = c.post(f"/api/requests/{r['id']}/deny", json={"reason": "Inte i mitt bibliotek"}, headers=h("owner-token")).json()
    assert d["status"] == "denied"
    assert d["reason"] == "Inte i mitt bibliotek"
    mine = c.get("/api/requests", headers=h("friend-token")).json()[0]
    assert mine["status"] == "denied" and mine["mine"] is True
    assert world.posted == []


def test_approval_sends_to_lidarr(world):
    setup_owner(world)
    c = world.client
    c.get("/api/search", params={"term": "x"}, headers=h("friend-token"))
    r = c.post("/api/requests", json={"foreignAlbumId": "rg-1"}, headers=h("friend-token")).json()
    a = c.post(f"/api/requests/{r['id']}/approve", headers=h("owner-token")).json()
    assert a["status"] == "searching"
    assert len(world.posted) == 1


def test_duplicate_request_returns_existing(world):
    setup_owner(world)
    c = world.client
    c.get("/api/search", params={"term": "x"}, headers=h("friend-token"))
    first = c.post("/api/requests", json={"foreignAlbumId": "rg-1"}, headers=h("friend-token")).json()
    again = c.post("/api/requests", json={"foreignAlbumId": "rg-1"}, headers=h("friend2-token")).json()
    assert again["id"] == first["id"] and again["existed"] is True
    # Ägaren önskar samma: godkänns direkt.
    own = c.post("/api/requests", json={"foreignAlbumId": "rg-1"}, headers=h("owner-token")).json()
    assert own["id"] == first["id"] and own["status"] == "searching"


def test_auto_approve_and_block(world):
    setup_owner(world)
    c = world.client
    c.get("/api/me", headers=h("friend-token"))
    users = c.get("/api/users", headers=h("owner-token")).json()
    assert {u["name"] for u in users} == {"Jonas", "Kompis"}
    assert c.put("/api/users/2", json={"autoApprove": True}, headers=h("owner-token")).status_code == 200
    c.get("/api/search", params={"term": "x"}, headers=h("friend-token"))
    r = c.post("/api/requests", json={"foreignAlbumId": "rg-1"}, headers=h("friend-token")).json()
    assert r["status"] == "searching"
    assert c.put("/api/users/2", json={"blocked": True}, headers=h("owner-token")).status_code == 200
    blocked = c.post("/api/requests", json={"foreignAlbumId": "rg-2"}, headers=h("friend-token"))
    assert blocked.status_code == 403
    # Ägaren kan inte blockera sig själv.
    assert c.put("/api/users/1", json={"blocked": True}, headers=h("owner-token")).status_code == 400


def test_stranger_cannot_search_or_request(world):
    setup_owner(world)
    c = world.client
    assert c.get("/api/search", params={"term": "x"}, headers=h("stranger-token")).status_code == 403
    assert c.post("/api/requests", json={"foreignAlbumId": "rg-1"}, headers=h("stranger-token")).status_code == 403
    assert c.get("/api/lidarr", headers=h("friend-token")).status_code == 403


def test_request_without_search_uses_lidarr_lookup(world):
    setup_owner(world)
    r = world.client.post("/api/requests", json={"foreignAlbumId": "rg-1"}, headers=h("owner-token")).json()
    assert r["title"] == "Housewife"


def test_unreachable_lidarr_retries_on_sync(world):
    setup_owner(world)
    c = world.client
    c.get("/api/search", params={"term": "x"}, headers=h("owner-token"))
    world.lidarr_up = False
    r = c.post("/api/requests", json={"foreignAlbumId": "rg-1"}, headers=h("owner-token")).json()
    assert r["status"] == "approved"
    assert "Når inte Lidarr" in r["message"]
    world.lidarr_up = True
    service = world.app.state.service
    c.portal.call(service.sync)
    r2 = c.get("/api/requests", headers=h("owner-token")).json()[0]
    assert r2["status"] == "searching"


def test_sync_follows_download_to_done(world):
    setup_owner(world)
    c = world.client
    c.get("/api/search", params={"term": "x"}, headers=h("owner-token"))
    c.post("/api/requests", json={"foreignAlbumId": "rg-1"}, headers=h("owner-token"))
    service = world.app.state.service
    world.queue = [{"albumId": 42, "size": 100, "sizeleft": 40, "trackedDownloadState": "downloading"}]
    c.portal.call(service.sync)
    r = c.get("/api/requests", headers=h("owner-token")).json()[0]
    assert r["status"] == "downloading" and abs(r["progress"] - 0.6) < 0.01
    world.queue = []
    world.stats = {"trackFileCount": 1, "trackCount": 1}
    c.portal.call(service.sync)
    r = c.get("/api/requests", headers=h("owner-token")).json()[0]
    assert r["status"] == "done"


def test_member_can_remove_own_pending_but_not_others(world):
    setup_owner(world)
    c = world.client
    c.get("/api/search", params={"term": "x"}, headers=h("friend-token"))
    r = c.post("/api/requests", json={"foreignAlbumId": "rg-1"}, headers=h("friend-token")).json()
    assert c.delete(f"/api/requests/{r['id']}", headers=h("friend2-token")).status_code == 403
    assert c.delete(f"/api/requests/{r['id']}", headers=h("friend-token")).status_code == 200
    assert c.get("/api/requests", headers=h("owner-token")).json() == []


def test_requests_without_lidarr_explain_why(world):
    c = world.client
    c.post("/api/claim", json={"serverId": "srv1"}, headers=h("owner-token"))
    r = c.get("/api/search", params={"term": "x"}, headers=h("friend-token"))
    assert r.status_code == 403
    assert "Lidarr" in r.json()["detail"]


# ---------- ren logik ----------


def test_album_parsing_and_add_body():
    a = album(LOOKUP[0])
    assert a["year"] == 2026 and a["type"] == "Single" and a["complete"] is False
    b = album(LOOKUP[1])
    assert b["year"] is None and b["complete"] is True and b["lidarrId"] == 7
    assert album({"title": "utan id"}) is None
    cfg = LidarrConfig("http://l", "k", "/music", 1, 2)
    body = add_body(LOOKUP[0], cfg)
    assert body["artist"]["qualityProfileId"] == 1
    assert "addOptions" not in LOOKUP[0]


def test_helpers():
    assert normalize_url(" tower:8686/ ") == "http://tower:8686"
    assert queue_items([{"albumId": 1, "size": 10, "sizeleft": 5, "status": "queued"}, {"albumId": 0}])[0]["progress"] == 0.5
    assert error_message('[{"errorMessage":"Finns redan"}]') == "Finns redan"
    assert error_message("<html>") is None


def test_next_status_says_when_nothing_found():
    r = st.Req(0, "rg", "T", "A", None, None, None, None, None, 1, "J", st.SEARCHING, lidarr_id=1, created=0)
    n = next_status(r, {"complete": False, "trackFiles": 0, "tracks": 10}, [], now=3600)
    assert n.status == st.SEARCHING and "Inget hittat" in n.message
    assert next_status(r, None, [], 1).status == st.FAILED
    imp = next_status(r, {"complete": False, "trackFiles": 0, "tracks": 10}, [{"albumId": 1, "progress": 1.0, "state": "importPending", "error": None}], 1)
    assert imp.status == st.IMPORTING
