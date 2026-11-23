# Commons Bell

See **who represents you**, **how they vote**, and **what they say in the Chamber** — straight from Northern Ireland Assembly open data. Built **NI-first**; Wales, Scotland and England come later.

Two ways to run it:

| | What it is | How you use it |
|---|---|---|
| 📱 **Android app** | A self-contained APK that talks to Stormont directly (no server in between) | Install the APK on your phone |
| 🌐 **Web app** *(optional/secondary)* | A lightweight Python server + phone-friendly UI (self-hosted) | `python server.py` → localhost |

The Android app is the main deliverable.

## 📱 Android app (the APK)

The app fetches the NI Assembly open-data services directly from your phone — no backend, nothing to host. Features:

- **🔔 Bell home screen** — tap the bell (it rings) to reveal *"What's on today"* and *"Bills in progress"*.
- **Your 5 MLAs** for your constituency (default **North Antrim**), each with:
  - a photo, party colour, and contact (email / phone);
  - **member history** — member-since, current role, previous role, and their *pre-politics* career;
  - **turnout** — how often they actually show up to vote.
- **Recent votes** — how *your* MLAs voted (Aye / No / Abstained / Absent), with a **"Bill fine print"** expander (plain-English summary + supporters/critics).
- **Statement history** — what your MLAs said in the Chamber, linked to the Official Report.
- **Cast your vote** — give your own Aye/No on recent divisions, then see how your MLAs voted. Your verdicts are logged on-device and, after **3 votes**, the app recommends the party that votes most like you (recalibrating each time, or flagging "no clear match").
- **Region selector** — switch between NI Assembly, Westminster, Scottish Parliament and Senedd (your pick is remembered across sessions). Only NI Assembly is live for now.
- **Pull-to-refresh** + a background refresh every ~3 hours (WorkManager).
- **Offline-friendly** — caches the last snapshot so it opens instantly.

### Getting the APK

Pre-built, signed APKs are attached to each [GitHub Release](https://github.com/donkza374/commons-bell/releases):

1. Open the latest release.
2. Download `app-release.apk`.
3. Copy it to your phone and install (allow "install from unknown sources" for first-time sideloading).

> The APK is **signed** with a stable key, so newer versions install *in place* over older ones (your settings and logged votes carry over).

Builds happen automatically via GitHub Actions on every push to `main` (see `.github/workflows/build-apk.yml`).

## 🌐 Web app (self-hosted, optional)

The original quick version. No dependencies (Python standard library only):

```bash
python server.py          # then open http://localhost:8000
python server.py 8080     # custom port
```

Or double-click `start.bat` on Windows.

## How it works

Both apps hit the [NI Assembly Open Data](https://data.niassembly.gov.uk/) JSON services:

- `members.asmx` — constituencies + MLAs (+ photos, contact details).
- `plenary.asmx` — divisions and per-member votes.
- `hansard.asmx` — the Official Report (members' spoken contributions).

The Android app does this natively via `HttpURLConnection` (no CORS, no backend). The
web app does it server-side via Python (browsers block cross-origin requests).

> **Editorial content:** "bill fine print" (summaries/benefits/negatives) and member
> "pre-politics career" are **curated** (not in the API) and bundled as assets —
> `bill_knowledge.json` and `member_biography.json`.

## Data licensing / attribution

Contains Parliamentary information licensed under the
[Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/).
Code is MIT (see [LICENSE](LICENSE)).

## Project layout

```
android/                                    Android app (Java, no Kotlin)
  app/src/main/java/com/commonsbell/
    MainActivity.java                       home bell + area screens
    VoteActivity.java                       cast-your-vote + vote log / recommendation
    DataFetcher.java                        all NI Assembly API calls + models
    BillKnowledge.java / MemberBio.java     curated asset loaders
    Ui.java                                 shared colours + formatting helpers
    Cache.java / RefreshWorker.java         snapshot cache + background refresh
  app/src/main/assets/                      bill_knowledge.json, member_biography.json
server.py / fetcher.py / static/            web app (Python, self-hosted)
.github/workflows/build-apk.yml             builds + signs the APK on GitHub
```

## Roadmap

See [ROADMAP.md](ROADMAP.md) for the full plan. Deferred: Westminster MPs, postcode →
constituency lookup, search, watchlist, dark mode, glossary, and Scotland/Wales/England.
