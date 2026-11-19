# Commons Bell — Roadmap

> **Status: PLANNING ONLY.** No builds/releases until the user gives the go-ahead.
> Shipped so far: v0.3.3 (signed release APK, updates install in place).

## Product goals

A politics-tracking app, **NI-first**, that answers:

1. Who represents my area? (the 5 MLAs for my constituency)
2. How do they vote? (divisions)
3. What do they say? (Hansard statements)
4. What's happening / coming up?
5. **Put a face to my representative, and show what they're actually doing for my community.**

Core motivation: people barely know who represents them, or what those people do for their area in return.

Eventually expands to Scotland, Wales, and England.

> Data status used throughout: ✅ = live API · 🔗 = link out to an official page · ✏️ = curated (I write it).

---

## Short-term (next planned batch)

### 1. Two-page structure

- **Home page** — generic landing/dashboard.
- **Area page** — the current content (your MLAs, votes, statements) with the **area dropdown** to switch constituency and re-title the page.

- [ ] Add Home page.
- [ ] Rename current screen to Area page (keep the dropdown there).
- [ ] Wire Home ↔ Area navigation.

### 2. Home page

Revised (leaner — area details live on the Area page, so Home doesn't duplicate them):

1. **Activity bell** — tap to expand **"What's on today"**: today's sitting, agenda, scheduled divisions, and committees. (Merges the earlier "bell" + "what's on today" + "committees today" into one tap.)
2. **Bills in progress** — live bills at their current stage.
3. **"Your area" nav** — a simple "North Antrim →" entry point to the Area page (plain link, no stats — the MLAs/votes/statements already live there).

- [x] Area entry = plain nav link (no duplicated stats). *— agreed*
- [ ] Implement each block.

Data sources: `GetBusinessDiary`, `GetPlenaryItemsPlenaryDate`, `GetCommitteeAgendaItems…`, `GetMotionBill`.

### 3. Vote info → "Bill fine print"

Replace the raw "Final Stage…" title in the vote dropdown with a plain-English explainer:

- **"Bill fine print"** section.
- 1–2 sentence summary of **what the bill is about**.
- **Estimated benefits** and **negatives**.

> ⚠️ **Data caveat:** the Assembly API gives only the raw title + motion text — no plain-language summary, benefits, or negatives. These are editorial content and must be **curated**.

**Chosen approach — Option A (curated):** bundle a small JSON keyed by bill/document ID. Fall back to the raw motion text if a bill isn't covered yet.

- [ ] Design the `bill_knowledge.json` schema.
- [ ] Draft summaries + benefits/negatives for current NI bills (I generate the first batch).
- [ ] Render in the vote info dropdown.

### 4. Member history (per MLA)

Show, for each MLA:

- **Current role(s)** — committee / ministerial / party-group roles (with start date).
- **Member since / duration** — how long they've been an MLA.
- **Previous role(s)** — their last committee / ministry.
- **Pre-politics career** — what they did before politics (e.g. teacher, farmer, solicitor). The user explicitly wants this so they can judge whether a candidate has had a real working life.

> ⚠️ **Data caveats (verified):**
> - ✅ Current + previous **roles** with start/end dates — `GetMemberRolesByPersonId_JSON`.
> - ✅ Ministers (`GetAllCurrentMinisters`) and Committee Chairs (`GetAllCurrentCommitteeChairs`).
> - ⚠️ **"Member since" (tenure)** — not in the member object; needs `GetAllMembersByGivenDate` reverse-lookup (or curated).
> - ❌ **Pre-politics career** — not in the API; must be **curated** (I draft the occupation info), optionally supplemented by scraping the AIMS profile page (`aims.niassembly.gov.uk/mlas/details.aspx`).

Approach: combine **live role data** (current/previous roles + dates) with a **curated biography** (`member_biography.json`: pre-politics occupation, and a cleaned "member since" year where the reverse-lookup is awkward).

- [ ] Add `getMemberRoles(personId)` to the fetcher.
- [ ] Build `member_biography.json` (pre-politics occupation per MLA) — I generate the first batch.
- [x] "Member since" = `GetAllMembersByGivenDate` reverse-lookup with curated fallback. *— agreed*
- [ ] Render "current role(s) · since <date>", "MLA since <year>", "previously …", and pre-politics occupation.

**Photos (put a face to the name):**
- ✅ MLA photo via `GetAllMembers_JSON` (`MemberImgUrl`, e.g. `aims.niassembly.gov.uk/images/mla/<id>_s.jpg`); it loads over **HTTPS** but the API returns `http://`, so force https. Cross-reference by `PersonId`.
- [ ] Show the MLA's photo at the top of each card.

### 5. What are they doing for my community?

A "who represents me" layer so people can see their representative is actually doing something for the area (not just a name):

- ✅ **Contact them** — email, phone, and constituency office address (`GetMemberContactDetailsByPersonId`). Add "Email / Call / Office" per MLA, plus a "email them about this vote" template.
- ✅ **Attendance / participation %** — how often they actually turned up to vote (derived from the division data we already fetch).
- ✅ **What they've tabled** — motions/bills they personally introduced (`GetPlenaryItemsTabledByMember`).
- ✅ **Committee work** — which committees they sit on (from member roles).
- ✅ **Local voice** — their Hansard statements (already have), framed as "what they raised for this area".

### 6. User-friendliness

- **Persistent bottom nav** — Home ↔ My Area as fixed tabs.
- **Tap-to-define glossary** — inline explanations for jargon (division, cross-community, Petition of Concern, Standing Order).
- **Filter & sort** — votes by outcome/keyword, statements by date/MLA/topic.
- **"Load more"** — cap each section (e.g. 10 votes / 10 statements) instead of endless scroll.
- **Readability** — larger text + dark mode.
- **First-run onboarding** — 3 screens, including a note that summaries/benefits are editorial.
- **Share** — copy/share a specific vote or statement.

### 7. "Is this really being debated?" (heckling — exploratory)

The user's pet peeve: serious issues debated while members heckle. Hansard *sometimes* records interruptions/interjections as separate components, so it's *theoretically* possible to flag "heckling" or "repeatedly interrupted" moments — but the data is noisy and inconsistent, so this is not promised.

- [ ] (Optional) Probe whether interruptions are reliably tagged in Hansard; if so, add a light "interruptions" marker. Otherwise drop.

**Link-outs** (cheap, not clean data): 🔗 MLAs' expenses · 🔗 open committee consultations / petitions ("how to have your say").

### 8. "Cast your vote" mode (in My Area)

A swipe-through on the Area page that closes the accountability loop — you give your verdict, then see how your MLAs actually voted and what was said.

**Flow:**
1. From the **Area page** (e.g. North Antrim), a "Cast your vote" entry.
2. Show the **Bill** (title), same visual style as the votes list.
3. **Aye / No** — swipe left (No) / right (Aye), or two buttons (Stormont's division format). **No comments**, just agree/disagree.
4. **"What's this bill?" ▸** — a dropdown that loads a short paragraph: what it's about, plus **upsides/benefits** and **downsides/negatives** (reuses bill fine print, section 3).
5. On cast, reveal:
   - **How your 5 MLAs voted** on that exact division (party-coloured AYE/NO/ABSENT badges).
   - **What was said** — the relevant Hansard transcript / your MLAs' statements, + a link to the full Official Report.
6. **Next** continues through the list — **older votes first, working up to newer**. Cast votes **save locally** (settings).

**Data status:**
- ✅ Divisions + per-MLA votes (live).
- ✅ "What was said" (live Hansard) — matched to the division by plenary date + topic (reuses the motion-text matching).
- 📱 **Your vote** — stored locally on-device for now (Option A). No server.

**Phasing:**
- **Now (A):** local only — your verdict stays on your phone; after the division it shows "you said No, your MLAs voted Aye".
- **Later (B):** public aggregate (anonymous first), with a **login** to deter spam. No comments — binary only.

- [x] Swipe direction = left (No) / right (Aye), with button fallback — matches Stormont's **Aye/No** format. *— agreed*
- [x] Order = previous (older) votes first, up to newer; cast votes saved in settings. *— agreed*
- [ ] Add the "What's this bill?" dropdown (bill fine print) into the card.

Reuses sections 3 (bill fine print), the existing vote badges, and Hansard.

---

## Later (deferred — do NOT start yet)

- **Scotland / Wales / England** expansion.
- **Westminster MPs** (e.g. Jim Allister, North Antrim).
- **Postcode lookup** (auto-detect constituency from postcode).
- **Search** (find an MLA by name/keyword).
- **Watchlist / follow** specific MLAs or topics.
- **Party seat breakdown** visual.
- **"How Stormont works"** explainers.
- **Favourites + share** a vote/statement.
- **In-app "check for updates"** link and/or **Play Store** release.

---

## Agreed scope (confirmed)

1. **Home page** — 3-item layout: activity bell → "what's on today", bills in progress, plain "your area" nav (no duplicated stats).
2. **Bill fine print** — Option A (curated): I write the plain-English summary + benefits + negatives, bundled in `bill_knowledge.json` with a raw-motion-text fallback.
3. **Member history** — four fields: current role(s), member-since/duration, previous role(s), and pre-politics career. Role data is live; **pre-politics career** and the **member-since** fallback are curated (I generate `member_biography.json`, sourced from the AIMS profile page / Wikipedia).
4. **Scope** — NI only for now; Scotland/Wales/England, Westminster MPs, postcode lookup, search, watchlist and Play Store are deferred.
5. **Cast your vote** — a swipe-through in My Area (Aye/No, no comments; "What's this bill?" dropdown with benefits/negatives; older→newer order). Reveals how your MLAs voted + the Hansard of what was said. Starts **local-only** (no server, no login); a public aggregate with a login is a later phase.

> No code changes, commits, or builds until the user gives the go-ahead.
