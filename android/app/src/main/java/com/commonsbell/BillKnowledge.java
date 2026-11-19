package com.commonsbell;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Curated "bill fine print" — a bundled plain-English explainer per bill
 * (what it's about + supporters' benefits + critics' negatives), since the
 * Assembly's open data has no such editorial content.
 */
public class BillKnowledge {

    public static class BillInfo {
        public String summary;
        public List<String> benefits;
        public List<String> negatives;
    }

    private static List<Entry> cache;

    private static class Entry {
        List<String> keywords;
        BillInfo info;
    }

    private static List<Entry> load(Context ctx) {
        if (cache != null) return cache;
        List<Entry> out = new ArrayList<>();
        try {
            InputStream in = ctx.getAssets().open("bill_knowledge.json");
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
            JSONArray arr = new JSONObject(sb.toString()).optJSONArray("bills");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Entry e = new Entry();
                    e.keywords = new ArrayList<>();
                    JSONArray kw = o.optJSONArray("keywords");
                    if (kw != null) {
                        for (int j = 0; j < kw.length(); j++) {
                            String k = kw.optString(j);
                            if (k != null && !k.isEmpty()) e.keywords.add(k.toLowerCase());
                        }
                    }
                    BillInfo bi = new BillInfo();
                    bi.summary = o.optString("summary");
                    bi.benefits = new ArrayList<>();
                    bi.negatives = new ArrayList<>();
                    JSONArray b = o.optJSONArray("benefits");
                    if (b != null) for (int j = 0; j < b.length(); j++) bi.benefits.add(b.optString(j));
                    JSONArray ng = o.optJSONArray("negatives");
                    if (ng != null) for (int j = 0; j < ng.length(); j++) bi.negatives.add(ng.optString(j));
                    e.info = bi;
                    out.add(e);
                }
            }
        } catch (Exception ignored) {
            // returns an empty list; matching falls back to the raw motion text
        }
        cache = out;
        return out;
    }

    /** Match a division/bill subject to a curated entry, or null if none. */
    public static BillInfo lookup(Context ctx, String subject) {
        if (subject == null) return null;
        String s = subject.toLowerCase();
        for (Entry e : load(ctx)) {
            for (String k : e.keywords) {
                if (s.contains(k)) return e.info;
            }
        }
        return null;
    }
}
