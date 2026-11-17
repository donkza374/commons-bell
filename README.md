# Commons Bell

Shows you **your MLAs (Northern Ireland Assembly)**, **how they vote**, and **what they say in the Chamber** — linked to the official Assembly sources. Built for **Northern Ireland first**, with the data pulled straight from Stormont's open data.

Two ways to run it:

| | What it is | How you use it |
|---|---|---|
| 📱 **Android app** | A self-contained APK that fetches Stormont directly (no server in between) | Install the APK on your phone |
| 🌐 **Web app** | A lightweight Python server + phone-friendly UI (self-hosted) | `python server.py` → localhost |

## 📱 Android app (the APK)

The Android app talks **directly** to the NI Assembly open-data services from your
phone — there is no backend and nothing to host. Features:

- **Your 5 MLAs** for your constituency (default **North Antrim**), each linked to their AIMS profile.
- **Recent division votes** — with how *your* MLAs voted (Aye / No / Abstained / Absent).
- **Recent statements** — what your MLAs actually said in the Chamber, with topic.
- **Update button** — pull fresh data whenever you want.
- **Auto-refresh** — refreshes in the background every ~3 hours (via WorkManager).
- **Offline-friendly** — caches the last snapshot, so it opens instantly and works offline.

### Getting the APK

The APK is built automatically by GitHub Actions on every push:

1. Go to the **Actions** tab → pick the latest successful **"Build APK"** run.
2. Download the **`commons-bell-apk`** artifact and unzip it to get `app-debug.apk`.
3. Copy it to your Android phone and install (you'll be asked to allow "install from unknown sources" for a first-time sideloaded APK).

> This first build is a **debug** APK (auto-signed, installable directly). A
> Play-Store-ready signed release is a later step (needs a signing key).

## 🌐 Web app (self-hosted, optional)

The original quick version. No dependencies (Python standard library only):

```bash
python server.py          # then open http://localhost:8000
python server.py 8080     # custom port
```

Or double-click `start.bat` on Windows.

## Refresh model

- **Manual** — the "Update" / "Refresh" button.
- **Scheduled** — full refresh every `refresh_interval_hours` (default **3**).
- **Vote-aware** (web app only, for now) — a lightweight watch detects newly-recorded
  divisions and pulls them in right after a vote is cast.

## How it works

Both apps hit the [NI Assembly Open Data](https://data.niassembly.gov.uk/) JSON services:

- `members.asmx` — constituencies + MLAs.
- `plenary.asmx` — divisions and per-member votes.
- `hansard.asmx` — the Official Report (members' spoken contributions).

The Android app does this natively via `HttpURLConnection` (no CORS, no backend). The
web app does it server-side via Python (because browsers block cross-origin requests).

## Data licensing / attribution

Contains Parliamentary information licensed under the
[Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/).
Code is MIT (see [LICENSE](LICENSE)).

## Project layout

```
server.py / fetcher.py / static/   web app (Python, self-hosted)
android/                           Android app (Kotlin-free Java + Gradle)
  app/src/main/java/...            MainActivity, DataFetcher, refresh worker
.github/workflows/build-apk.yml    builds the APK on GitHub
```

## Roadmap

- [ ] Westminster MP module (adds Jim Allister for North Antrim via the UK Parliament APIs).
- [ ] Postcode → constituency lookup (currently a dropdown / default to North Antrim).
- [ ] Signed release build + Play Store publishing.
- [ ] Upcoming business / Order Paper.
