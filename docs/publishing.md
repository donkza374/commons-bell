# Publishing to Google Play

This repo can push signed App Bundles (AABs) straight to Google Play automatically.
Once set up, the flow is:

```
git tag v0.10.0  →  GitHub Actions builds + signs the AAB  →  uploads to Play  →  Play auto-updates users
```

You still create a GitHub release for the sideload APK as before; this is purely the Play delivery path.

## One-time setup

### 1. Create a Google Cloud service account
1. Go to the [Google Cloud Console](https://console.cloud.google.com/).
2. Select (or create) the project linked to your Play developer account.
3. **IAM & Admin → Service Accounts → Create Service Account**.
4. Name it `commons-bell-play-upload`. You can skip assigning IAM roles (Play permissions are granted inside Play Console, not IAM).
5. Open the new account → **Keys → Add key → Create new key → JSON**, and download the JSON file.

### 2. Enable the API
In the same project: **APIs & Services → Enable APIs and services**, then enable **Google Play Android Developer API**.

### 3. Link the service account in Play Console
1. Play Console → **Setup → API access** (under "Developer account").
2. Under **Service accounts**, `+ Create new service account` (or link the one you just made).
3. Paste the service account email (e.g. `commons-bell-play-upload@...iam.gserviceaccount.com`).
4. Grant it permission **"Release to production"** (and/or testing tracks), then **Invite user** / grant access.

### 4. Add the GitHub secret
In this repo → **Settings → Secrets and variables → Actions → New repository secret**:

- Name: `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`
- Value: the *entire contents* of the downloaded JSON key file.

> The workflow is guarded by `if: secrets.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON != ''`, so it is a no-op until this secret exists.

## Publishing a release to Play

Once the secret is set, either:

- **Manual:** Actions → "Publish to Google Play" → Run workflow, or
- **Automatic:** push a version tag, e.g. `gh release create v0.10.0` (this pushes the `v0.10.0` tag and triggers the publish workflow).

The AAB is uploaded to the **Internal testing** track. To go to production, change `track: internal` → `track: production` in `.github/workflows/publish-play.yml` (and ideally promote via a closed/beta track first).

## Note on signing keys

The CI signs the AAB with the keystore from the `COMMONS_BELL_*` secrets. With **Play App Signing** enabled, this keystore is your *upload key*: Play re-signs the APK delivered to users with its own *app-signing key*. To keep Play installs compatible with your sideload users, enrol the *same* key's certificate as the app-signing key when you first enable Play App Signing (Play Console → App signing → "Use your existing key").
