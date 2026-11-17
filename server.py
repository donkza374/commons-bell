#!/usr/bin/env python3
"""
NI Politics Tracker server — pure Python standard library (no dependencies).

Serves a small phone-friendly web app and a JSON API, backed by fetcher.py
(NI Assembly open data). Single user, keeps the latest snapshot on disk so the
app still opens instantly (even offline) while a background thread keeps it
fresh.

Refresh model (as requested):
  * manual   — the "Refresh" button hits POST /api/refresh
  * scheduled — a full refresh every N hours (default 3)
  * vote-aware — between full refreshes, a lightweight "vote watch" checks every
    M minutes (default 5) for newly-recorded divisions and, when one appears,
    pulls it in immediately (i.e. shortly after a vote is cast). This mirrors
    "refresh after a vote's estimated duration", and is skipped when the data
    source returns nothing new.

Run:
    python server.py                # http://localhost:8000
    python server.py 8080           # custom port
"""
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import fetcher

STATIC_DIR = os.path.join(HERE, "static")
CONFIG_FILE = os.path.join(HERE, "config.json")
STATE_FILE = os.path.join(HERE, "state.json")

DEFAULT_PORT = 8000


# ---------------------------------------------------------------------------
# Config / state persistence
# ---------------------------------------------------------------------------

def load_config():
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"constituency_id": 9, "refresh_interval_hours": 3, "vote_watch_minutes": 5}


def save_config(cfg):
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception:
        pass


def load_state():
    try:
        with open(STATE_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(st):
    try:
        with open(STATE_FILE, "w", encoding="utf-8") as f:
            json.dump(st, f)
    except Exception:
        pass


config = load_config()
state = load_state()
_refresh_lock = threading.Lock()


def refresh_interval_seconds():
    return float(config.get("refresh_interval_hours", 3)) * 3600.0


def vote_watch_seconds():
    return float(config.get("vote_watch_minutes", 5)) * 60.0


# ---------------------------------------------------------------------------
# Refresh logic
# ---------------------------------------------------------------------------

def do_full_refresh():
    """Fetch everything and update state. Returns True on success."""
    global state
    cid = config.get("constituency_id", 9)
    try:
        snapshot = fetcher.collect(cid, config)
    except Exception as e:
        state["last_error"] = str(e)
        state["last_error_time"] = time.time()
        save_state(state)
        return False
    state["snapshot"] = snapshot
    state["last_refresh"] = time.time()
    state["next_refresh"] = time.time() + refresh_interval_seconds()
    state.pop("last_error", None)
    state.pop("last_error_time", None)
    save_state(state)
    return True


def check_for_new_divisions():
    """Vote watch: detect divisions recorded since our last snapshot, and if
    any are new, trigger a full refresh so the result is captured promptly."""
    snap = state.get("snapshot") or {}
    known = {v.get("documentId") for v in snap.get("votes", [])} if snap else set()
    import datetime
    from datetime import date, timedelta
    end = date.today()
    start = end - timedelta(days=3)
    try:
        divs = fetcher.get_votes(start.isoformat(), end.isoformat())
    except Exception:
        return
    if any(str(d.get("DocumentID")) not in known for d in divs):
        state["pending_vote_refresh"] = True
        do_full_refresh()
        state.pop("pending_vote_refresh", None)


def _scheduler_loop():
    """Background thread: full refresh on schedule, vote-watch in between."""
    while True:
        try:
            now = time.time()
            next_full = state.get("next_refresh", 0)
            last_watch = state.get("last_vote_watch", 0)
            if now >= next_full:
                do_full_refresh()
            elif now - last_watch >= vote_watch_seconds():
                state["last_vote_watch"] = now
                save_state(state)
                check_for_new_divisions()
        except Exception:
            pass
        time.sleep(30)


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "NI-Politics-Tracker/0.1"

    def _send_json(self, obj, status=200):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_static(self, path):
        if path in ("/", ""):
            path = "/index.html"
        # prevent traversal
        rel = path.lstrip("/")
        if rel.split("/")[0] == ".." or ".." in rel.split("/"):
            self.send_error(404)
            return
        full = os.path.join(STATIC_DIR, rel)
        if not os.path.isfile(full):
            self.send_error(404)
            return
        try:
            with open(full, "rb") as f:
                data = f.read()
        except Exception:
            self.send_error(500)
            return
        ctype = "text/html; charset=utf-8"
        if rel.endswith(".css"):
            ctype = "text/css; charset=utf-8"
        elif rel.endswith(".js"):
            ctype = "application/javascript; charset=utf-8"
        elif rel.endswith(".svg"):
            ctype = "image/svg+xml"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    # -- API ----------------------------------------------------------------
    def _api_state(self):
        self._send_json({
            "constituency": (state.get("snapshot") or {}).get("constituency"),
            "mlas": (state.get("snapshot") or {}).get("mlas", []),
            "votes": (state.get("snapshot") or {}).get("votes", []),
            "statements": (state.get("snapshot") or {}).get("statements", []),
            "last_refresh": state.get("last_refresh"),
            "next_refresh": state.get("next_refresh"),
            "refresh_interval_hours": config.get("refresh_interval_hours", 3),
            "last_error": state.get("last_error"),
        })

    def _api_constituencies(self):
        cid = config.get("constituency_id", 9)
        try:
            clist = fetcher.get_constituencies()
        except Exception:
            clist = []
        self._send_json({
            "constituencies": clist,
            "current": cid,
        })

    def _api_refresh(self):
        global state
        if _refresh_lock.acquire(blocking=False):
            try:
                state["refreshing"] = True
                save_state(state)
                ok = do_full_refresh()
            finally:
                state["refreshing"] = False
                save_state(state)
                _refresh_lock.release()
        else:
            ok = False
        self._api_state()

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/state":
            return self._api_state()
        if path == "/api/constituencies":
            return self._api_constituencies()
        if path.startswith("/api/"):
            self._send_json({"error": "not found"}, 404)
            return
        return self._send_static(path)

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/api/refresh":
            return self._api_refresh()
        if path == "/api/constituency":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length) if length else b""
            try:
                payload = json.loads(body.decode("utf-8"))
                new_id = int(payload.get("constituency_id"))
                config["constituency_id"] = new_id
                save_config(config)
                # re-fetch immediately so the new area's reps appear
                do_full_refresh()
            except Exception as e:
                self._send_json({"error": str(e)}, 400)
                return
            return self._api_state()
        if path.startswith("/api/"):
            self._send_json({"error": "not found"}, 404)
            return
        self.send_error(405)

    def log_message(self, fmt, *args):
        # keep console quiet unless something is worth seeing
        if "/api/" in (args[0] if args else ""):
            return
        super().log_message(fmt, *args)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    threading.Thread(target=_scheduler_loop, daemon=True).start()

    # Prime the cache on first run but don't block startup.
    if not state.get("snapshot"):
        # load the very first snapshot synchronously so the app has data now
        print("[start] first run — fetching initial data (may take ~30s)...")
        threading.Thread(target=do_full_refresh, daemon=True).start()
    else:
        print(f"[start] loaded cached data (last refresh "
              f"{int(time.time() - state.get('last_refresh', time.time()))}s ago)")

    print(f"Serving on http://localhost:{port} (Ctrl+C to stop)")
    server = ThreadingHTTPServer(("", port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
