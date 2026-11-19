package com.commonsbell;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Curated member background (member-since, current role, previous role and
 * pre-politics career) keyed by PersonId. The Assembly's open data has roles
 * but no plain-language biography, so this is editorial content bundled as an
 * asset. Falls back gracefully for MLAs not covered yet.
 */
public class MemberBio {

    public static class Info {
        public String memberSince;
        public String roleCurrent;
        public String previousRole;
        public String career;
    }

    private static Map<String, Info> cache;

    public static Map<String, Info> load(Context ctx) {
        if (cache != null) return cache;
        Map<String, Info> out = new HashMap<>();
        try {
            InputStream in = ctx.getAssets().open("member_biography.json");
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
            JSONObject members = new JSONObject(sb.toString()).optJSONObject("members");
            if (members != null) {
                Iterator<String> it = members.keys();
                while (it.hasNext()) {
                    String pid = it.next();
                    JSONObject o = members.optJSONObject(pid);
                    if (o == null) continue;
                    Info info = new Info();
                    info.memberSince = o.optString("memberSince");
                    info.roleCurrent = o.optString("roleCurrent");
                    info.previousRole = o.optString("previousRole");
                    info.career = o.optString("career");
                    out.put(pid, info);
                }
            }
        } catch (Exception ignored) {
            // return an empty map; UI falls back to only live data
        }
        cache = out;
        return out;
    }
}
