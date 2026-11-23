package com.commonsbell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks directly to the NI Assembly open-data JSON services (no CORS in
 * native HTTP, so this works from the phone with no backend in between).
 *
 * Mirrors the Python `fetcher.py` in the web app.
 */
public class DataFetcher {

    /** North Antrim = constituency 9 in the Assembly open data. */
    public static final int DEFAULT_CONSTITUENCY_ID = 9;

    static final String BASE = "https://data.niassembly.gov.uk";
    static final String PROFILE_BASE = "https://aims.niassembly.gov.uk/mlas/details.aspx";
    static final String OFFICIAL_REPORT_BASE = "https://aims.niassembly.gov.uk/officialreport/report.aspx";

    // --- model --------------------------------------------------------------
    public static class Mla {
        public String personId;
        public String affiliationId;
        public String name;
        public String party;
        public String profileUrl;
        public String photoUrl;   // https portrait
        public String email;
        public String phone;
    }

    public static class MyVote {
        public String name;
        public String party;
        public String vote; // AYE / NO / ABSTAINED / ABSENT / UNKNOWN
    }

    public static class Vote {
        public String documentId;
        public String subject;
        public String date;   // "2026-11-10 20:53"
        public String type;   // Simple Majority / Cross-Community
        public String outcome;            // "The Motion Was Carried" / "The Motion Was Defeated"
        public String totalAyes;
        public String totalNoes;
        public String totalAbstentions;
        public String description;         // full motion text: what the policy actually is
        public List<MyVote> myVotes = new ArrayList<>();
    }

    public static class Statement {
        public String mla;
        public String party;
        public String topic;
        public String text;
        public String url;
        public String date;   // "2026-11-10"
    }

    public static class Snapshot {
        public String constituencyName;
        public List<Mla> mlas = new ArrayList<>();
        public List<Vote> votes = new ArrayList<>();
        public List<Statement> statements = new ArrayList<>();
    }

    public static class Constituency {
        public String id;
        public String name;
    }

    public static class DiaryItem {
        public String eventType;        // "Committee Meeting" / "Sitting of the Assembly"
        public String organisationName;
        public String startTime;        // "09:30"
        public String weekday;          // "Tuesday"
        public String locationRoom;
        public String eventDate;        // "2026-11-17"
    }

    public static class Business {
        public String title;
        public String type;   // Motion / Motion Amendment / ...
        public String text;   // full motion text
        public String date;
    }

    // --- low-level HTTP -----------------------------------------------------
    private String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", "CommonsBell/0.1");
        c.setConnectTimeout(20000);
        c.setReadTimeout(40000);
        try {
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** Walk root -> k1 -> k2 and return the array, normalising a lone object. */
    private JSONArray arr(JSONObject root, String k1, String k2) {
        JSONObject a = root.optJSONObject(k1);
        if (a == null) return new JSONArray();
        Object v = a.opt(k2);
        if (v instanceof JSONArray) return (JSONArray) v;
        JSONArray out = new JSONArray();
        if (v instanceof JSONObject) out.put(v);
        return out;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String shortTime(String isoDate) {
        if (isoDate == null) return "";
        String t = isoDate.replace("T", " ");
        return t.length() > 16 ? t.substring(0, 16) : t;
    }

    /** All NI Assembly constituencies, for the location picker. */
    public List<Constituency> getConstituencies() throws Exception {
        List<Constituency> out = new ArrayList<>();
        JSONArray list = arr(
                new JSONObject(get(BASE + "/members.asmx/GetAllConstituencies_JSON")),
                "AllConstituencies", "Constituency");
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            Constituency c = new Constituency();
            c.id = String.valueOf(o.opt("ConstituencyId"));
            c.name = o.optString("ConstituencyName");
            out.add(c);
        }
        return out;
    }

    private static String shortClock(String iso) {
        // "2026-11-17T09:30:00+00:00" -> "09:30"
        if (iso == null) return "";
        int t = iso.indexOf('T');
        if (t < 0 || iso.length() < t + 6) return "";
        return iso.substring(t + 1, t + 6);
    }

    /** Upcoming Assembly business (committee meetings + sittings) for the next N days. */
    public List<DiaryItem> getBusinessDiary(int daysAhead) throws Exception {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusDays(daysAhead);
        List<DiaryItem> out = new ArrayList<>();
        JSONArray list = arr(new JSONObject(get(BASE
                        + "/plenary.asmx/GetBusinessDiary_JSON?startDate=" + start + "&endDate=" + end)),
                "BusinessDiary", "DiaryItem");
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            DiaryItem d = new DiaryItem();
            d.eventType = o.optString("EventType");
            d.organisationName = o.optString("OrganisationName");
            d.startTime = shortClock(o.optString("StartTime"));
            d.weekday = o.optString("Weekday");
            d.locationRoom = o.optString("LocationRoom");
            d.eventDate = o.optString("EventDate");
            out.add(d);
        }
        return out;
    }

    /** Bills & motions tabled between two dates (past or future Order Paper). */
    private List<Business> getBusinessBetween(LocalDate start, LocalDate end) throws Exception {
        List<Business> out = new ArrayList<>();
        JSONArray list = arr(new JSONObject(get(BASE
                        + "/plenary.asmx/GetPlenaryItemsPlenaryDate_JSON?startDate=" + start + "&endDate=" + end)),
                "PlenaryList", "Plenary");
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            String title = o.optString("Title");
            String type = o.optString("PlenaryType");
            String text = clean(o.optString("Text"));
            if (title == null || title.trim().isEmpty()) continue;
            boolean relevant = (text != null && !text.isEmpty())
                    || type.toLowerCase().contains("motion")
                    || title.toLowerCase().contains("bill")
                    || title.toLowerCase().contains("stage");
            if (!relevant) continue;
            Business b = new Business();
            b.title = title.trim();
            b.type = type;
            b.text = text;
            String pd = o.optString("PlenaryDate");
            b.date = (pd != null && pd.length() >= 10) ? pd.substring(0, 10) : "";
            out.add(b);
        }
        return out;
    }

    /** Bills & motions currently in progress (recent Order Paper business). */
    public List<Business> getBillsInProgress() throws Exception {
        LocalDate end = LocalDate.now();
        return getBusinessBetween(end.minusDays(21), end);
    }

    /** Bills & motions scheduled for the next N days (future Order Paper). */
    public List<Business> getUpcomingBusiness(int daysAhead) throws Exception {
        LocalDate start = LocalDate.now();
        return getBusinessBetween(start, start.plusDays(daysAhead));
    }

    /** Bills & motions tabled in the last N days. */
    public List<Business> getRecentBusiness(int daysBack) throws Exception {
        LocalDate end = LocalDate.now();
        return getBusinessBetween(end.minusDays(daysBack), end);
    }

    /** Enrich MLAs with photo URLs + contact details (each fetched in one call). */
    private void enrichMlas(List<Mla> mlas) {
        // photos (all members, single call)
        try {
            JSONArray all = arr(new JSONObject(get(BASE + "/members.asmx/GetAllMembers_JSON")),
                    "AllMembersList", "Member");
            Map<String, String> photos = new HashMap<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o == null) continue;
                String pid = String.valueOf(o.opt("PersonId"));
                String url = o.optString("MemberImgUrl");
                if (!pid.isEmpty() && url != null && !url.isEmpty()) {
                    photos.put(pid, url.replace("http://", "https://"));
                }
            }
            for (Mla m : mlas) m.photoUrl = photos.get(m.personId);
        } catch (Exception ignore) { }

        // contact details (all members, single call)
        try {
            JSONArray all = arr(new JSONObject(get(BASE + "/members.asmx/GetAllMemberContactDetails_JSON")),
                    "AllMembersList", "Member");
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o == null) continue;
                String pid = String.valueOf(o.opt("PersonId"));
                for (Mla m : mlas) {
                    if (!pid.equals(m.personId)) continue;
                    String email = o.optString("EmaiAddress");
                    if (email.trim().isEmpty()) email = o.optString("EmailAddress");
                    if ((m.email == null || m.email.isEmpty()) && !email.trim().isEmpty()) {
                        m.email = email.trim();
                    }
                    String phone = o.optString("TelephoneNumber");
                    if ((m.phone == null || m.phone.isEmpty()) && !phone.trim().isEmpty()) {
                        m.phone = phone.trim();
                    }
                }
            }
        } catch (Exception ignore) { }
    }

    // --- high-level fetch ---------------------------------------------------
    public Snapshot fetch(int constituencyId) throws Exception {
        Snapshot snap = new Snapshot();

        // constituency name
        JSONArray cons = arr(new JSONObject(get(BASE + "/members.asmx/GetAllConstituencies_JSON")),
                "AllConstituencies", "Constituency");
        for (int i = 0; i < cons.length(); i++) {
            JSONObject o = cons.optJSONObject(i);
            if (o != null && String.valueOf(o.opt("ConstituencyId"))
                    .equals(String.valueOf(constituencyId))) {
                snap.constituencyName = o.optString("ConstituencyName");
            }
        }
        if (snap.constituencyName == null || snap.constituencyName.isEmpty()) {
            snap.constituencyName = "Your area";
        }

        // MLAs
        JSONArray mlaArr = arr(
                new JSONObject(get(BASE + "/members.asmx/GetAllCurrentMembersByGivenConstituencyId_JSON"
                        + "?constituencyId=" + constituencyId)),
                "AllMembersList", "Member");
        List<String> personIds = new ArrayList<>();
        for (int i = 0; i < mlaArr.length(); i++) {
            JSONObject m = mlaArr.optJSONObject(i);
            if (m == null) continue;
            Mla mla = new Mla();
            mla.personId = String.valueOf(m.opt("PersonId"));
            mla.affiliationId = String.valueOf(m.opt("AffiliationId"));
            mla.name = m.optString("MemberFullDisplayName");
            mla.party = m.optString("PartyName");
            mla.profileUrl = PROFILE_BASE + "?aff=" + mla.affiliationId
                    + "&per=" + mla.personId + "&sel=1&ind=0&prv=0";
            snap.mlas.add(mla);
            personIds.add(mla.personId);
        }
        enrichMlas(snap.mlas);

        // votes (last 45 days)
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(45);
        JSONArray divArr = arr(
                new JSONObject(get(BASE + "/plenary.asmx/GetVotesOnDivision_JSON"
                        + "?startDate=" + start + "&endDate=" + end)),
                "DivisionList", "Division");

        int maxDetails = 20;
        Map<String, List<JSONObject>> plenaryCache = new HashMap<>();
        for (int i = 0; i < divArr.length(); i++) {
            JSONObject d = divArr.optJSONObject(i);
            if (d == null) continue;
            Vote v = new Vote();
            v.documentId = String.valueOf(d.opt("DocumentID"));
            v.subject = d.optString("DivisionSubject");
            v.date = shortTime(d.optString("DivisionDate"));
            v.type = d.optString("DivisonType");

            Map<String, String> memberVotes = new HashMap<>();
            if (i < maxDetails && !v.documentId.isEmpty() && !"null".equals(v.documentId)) {
                try {
                    JSONArray mva = arr(
                            new JSONObject(get(BASE + "/plenary.asmx/GetDivisionMemberVoting_JSON"
                                    + "?documentid=" + v.documentId)),
                            "MemberVoting", "Member");
                    for (int j = 0; j < mva.length(); j++) {
                        JSONObject mv = mva.optJSONObject(j);
                        if (mv != null) {
                            memberVotes.put(String.valueOf(mv.opt("PersonID")), mv.optString("Vote"));
                        }
                    }
                } catch (Exception ignore) {
                    // keep the division itself, just without the per-member detail
                }
            }

            // division outcome + totals + policy description (for the "more info" dropdown)
            if (!v.documentId.isEmpty() && !"null".equals(v.documentId)) {
                try {
                    JSONObject res = arr(
                            new JSONObject(get(BASE + "/plenary.asmx/GetDivisionResult_JSON"
                                    + "?documentId=" + v.documentId)),
                            "DivisionDetails", "Division").optJSONObject(0);
                    if (res != null) {
                        v.outcome = res.optString("Outcome");
                        v.totalAyes = res.optString("TotalAyes");
                        v.totalNoes = res.optString("TotalNoes");
                        v.totalAbstentions = res.optString("TotalAbstentions");
                    }
                } catch (Exception ignore) {
                    // leave outcome blank; the division subject still shows
                }

                // full motion text from the Order Paper = what the policy actually is
                try {
                    String dateOnly = d.optString("DivisionDate");
                    if (dateOnly.length() >= 10) dateOnly = dateOnly.substring(0, 10);
                    v.description = matchDescription(v.subject,
                            getPlenaryItemsCached(dateOnly, plenaryCache));
                } catch (Exception ignore) {
                    // no description; the title + outcome still show
                }
            }

            for (Mla mla : snap.mlas) {
                MyVote mv = new MyVote();
                mv.name = mla.name;
                mv.party = mla.party;
                String vote = memberVotes.get(mla.personId);
                if (vote != null && !vote.isEmpty()) {
                    mv.vote = vote;
                } else if (!memberVotes.isEmpty()) {
                    mv.vote = "ABSENT";
                } else {
                    mv.vote = "UNKNOWN";
                }
                v.myVotes.add(mv);
            }
            snap.votes.add(v);
        }

        // statements (last few sittings)
        JSONArray reportArr = arr(
                new JSONObject(get(BASE + "/hansard.asmx/GetAllHansardReports_JSON")),
                "AllHansardComponentsList", "HansardComponent");
        int stmtReports = 5;
        for (int r = 0; r < reportArr.length() && r < stmtReports; r++) {
            JSONObject rep = reportArr.optJSONObject(r);
            if (rep == null) continue;
            String reportId = String.valueOf(rep.opt("ReportDocId"));
            String plenaryDate = String.valueOf(rep.opt("PlenaryDate"));
            String date = plenaryDate.length() >= 10 ? plenaryDate.substring(0, 10) : plenaryDate;
            for (Mla mla : snap.mlas) {
                try {
                    JSONArray comps = arr(
                            new JSONObject(get(BASE + "/hansard.asmx/GetHansardComponentsByReportIdAndPersonId_JSON"
                                    + "?reportId=" + reportId + "&personId=" + mla.personId)),
                            "AllHansardComponentsList", "HansardComponent");
                    extractStatements(snap, comps, mla.name, mla.party, reportId, date);
                } catch (Exception ignore) {
                    // skip this person/report on error
                }
            }
        }

        // cap to keep the UI manageable
        int maxStatements = 60;
        if (snap.statements.size() > maxStatements) {
            snap.statements = new ArrayList<>(snap.statements.subList(0, maxStatements));
        }

        return snap;
    }

    private List<JSONObject> getPlenaryItemsCached(String date, Map<String, List<JSONObject>> cache)
            throws Exception {
        List<JSONObject> items = cache.get(date);
        if (items != null) return items;
        JSONArray a = arr(new JSONObject(get(
                BASE + "/plenary.asmx/GetPlenaryItemsPlenaryDate_JSON"
                        + "?startDate=" + date + "&endDate=" + date)),
                "PlenaryList", "Plenary");
        items = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) items.add(o);
        }
        cache.put(date, items);
        return items;
    }

    /** Normalise a division subject / plenary title so the two can be matched. */
    private static String normalizeTitle(String s) {
        if (s == null) return "";
        String t = s.toLowerCase();
        t = t.replaceAll("\\[.*?]", " ");       // strip "[Committee Chair - ...]" etc.
        t = t.replaceAll("-#?\\d+", " ");        // strip leading "-#1" markers
        t = t.replaceAll("\\s*-\\s*amendment.*", ""); // strip " - Amendment 1: ..."
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Match a division to its plenary item and return the full motion text. */
    private String matchDescription(String subject, List<JSONObject> items) {
        String ns = normalizeTitle(subject);
        if (ns.length() < 8) return "";
        String best = "";
        int bestLen = 0;
        for (JSONObject it : items) {
            String text = it.optString("Text");
            if (text == null || text.trim().isEmpty()) continue;
            String nt = normalizeTitle(it.optString("Title"));
            if (nt.length() < 8) continue;
            if (ns.contains(nt) || nt.contains(ns)) {
                if (nt.length() > bestLen) {
                    bestLen = nt.length();
                    best = clean(text);
                }
            }
        }
        return best;
    }

    private void extractStatements(Snapshot snap, JSONArray comps,
                                   String name, String party, String reportId, String date) {
        String topic = "";
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < comps.length(); i++) {
            JSONObject c = comps.optJSONObject(i);
            if (c == null) continue;
            String type = c.optString("ComponentType");
            if ("Speaker (MlaName)".equals(type)) {
                if (cur.length() > 0) {
                    addStatement(snap, name, party, topic, cur.toString(), reportId, date);
                    cur.setLength(0);
                }
                Object pt = c.opt("ParentComponentText");
                if (pt instanceof String) topic = clean((String) pt);
            } else if ("Spoken Text".equals(type) || "Spoken Text (Continuation)".equals(type)) {
                String t = clean(c.optString("ComponentText"));
                if (!t.isEmpty()) {
                    if (cur.length() > 0) cur.append(' ');
                    cur.append(t);
                }
            }
        }
        if (cur.length() > 0) {
            addStatement(snap, name, party, topic, cur.toString(), reportId, date);
        }
    }

    private void addStatement(Snapshot snap, String name, String party,
                              String topic, String text, String reportId, String date) {
        Statement s = new Statement();
        s.mla = name;
        s.party = party;
        s.topic = topic;
        s.text = text;
        s.url = OFFICIAL_REPORT_BASE + "?eveDate=" + date.replace('-', '/') + "&docID=" + reportId;
        s.date = date;
        snap.statements.add(s);
    }

    // --- snapshot <-> JSON (for the local cache) ----------------------------
    public static JSONObject snapshotToJson(Snapshot s) {
        JSONObject root = new JSONObject();
        try {
            root.put("constituencyName", s.constituencyName);

            JSONArray mlas = new JSONArray();
            for (Mla m : s.mlas) {
                JSONObject o = new JSONObject();
                o.put("personId", m.personId);
                o.put("affiliationId", m.affiliationId);
                o.put("name", m.name);
                o.put("party", m.party);
                o.put("profileUrl", m.profileUrl);
                o.put("photoUrl", m.photoUrl);
                o.put("email", m.email);
                o.put("phone", m.phone);
                mlas.put(o);
            }
            root.put("mlas", mlas);

            JSONArray votes = new JSONArray();
            for (Vote v : s.votes) {
                JSONObject o = new JSONObject();
                o.put("documentId", v.documentId);
                o.put("subject", v.subject);
                o.put("date", v.date);
                o.put("type", v.type);
                o.put("outcome", v.outcome);
                o.put("totalAyes", v.totalAyes);
                o.put("totalNoes", v.totalNoes);
                o.put("totalAbstentions", v.totalAbstentions);
                o.put("description", v.description);
                JSONArray mv = new JSONArray();
                for (MyVote x : v.myVotes) {
                    JSONObject mo = new JSONObject();
                    mo.put("name", x.name);
                    mo.put("party", x.party);
                    mo.put("vote", x.vote);
                    mv.put(mo);
                }
                o.put("myVotes", mv);
                votes.put(o);
            }
            root.put("votes", votes);

            JSONArray stmts = new JSONArray();
            for (Statement st : s.statements) {
                JSONObject o = new JSONObject();
                o.put("mla", st.mla);
                o.put("party", st.party);
                o.put("topic", st.topic);
                o.put("text", st.text);
                o.put("url", st.url);
                o.put("date", st.date);
                stmts.put(o);
            }
            root.put("statements", stmts);
        } catch (Exception e) {
            return root;
        }
        return root;
    }

    public static Snapshot snapshotFromJson(String json) {
        Snapshot s = new Snapshot();
        try {
            JSONObject root = new JSONObject(json);
            s.constituencyName = root.optString("constituencyName");

            JSONArray mlas = root.optJSONArray("mlas");
            if (mlas != null) {
                for (int i = 0; i < mlas.length(); i++) {
                    JSONObject o = mlas.optJSONObject(i);
                    if (o == null) continue;
                    Mla m = new Mla();
                    m.personId = o.optString("personId");
                    m.affiliationId = o.optString("affiliationId");
                    m.name = o.optString("name");
                    m.party = o.optString("party");
                    m.profileUrl = o.optString("profileUrl");
                    m.photoUrl = o.optString("photoUrl");
                    m.email = o.optString("email");
                    m.phone = o.optString("phone");
                    s.mlas.add(m);
                }
            }

            JSONArray votes = root.optJSONArray("votes");
            if (votes != null) {
                for (int i = 0; i < votes.length(); i++) {
                    JSONObject o = votes.optJSONObject(i);
                    if (o == null) continue;
                    Vote v = new Vote();
                    v.documentId = o.optString("documentId");
                    v.subject = o.optString("subject");
                    v.date = o.optString("date");
                    v.type = o.optString("type");
                    v.outcome = o.optString("outcome");
                    v.totalAyes = o.optString("totalAyes");
                    v.totalNoes = o.optString("totalNoes");
                    v.totalAbstentions = o.optString("totalAbstentions");
                    v.description = o.optString("description");
                    JSONArray mv = o.optJSONArray("myVotes");
                    if (mv != null) {
                        for (int j = 0; j < mv.length(); j++) {
                            JSONObject mo = mv.optJSONObject(j);
                            if (mo == null) continue;
                            MyVote x = new MyVote();
                            x.name = mo.optString("name");
                            x.party = mo.optString("party");
                            x.vote = mo.optString("vote");
                            v.myVotes.add(x);
                        }
                    }
                    s.votes.add(v);
                }
            }

            JSONArray stmts = root.optJSONArray("statements");
            if (stmts != null) {
                for (int i = 0; i < stmts.length(); i++) {
                    JSONObject o = stmts.optJSONObject(i);
                    if (o == null) continue;
                    Statement st = new Statement();
                    st.mla = o.optString("mla");
                    st.party = o.optString("party");
                    st.topic = o.optString("topic");
                    st.text = o.optString("text");
                    st.url = o.optString("url");
                    st.date = o.optString("date");
                    s.statements.add(st);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return s;
    }
}
