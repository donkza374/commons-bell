package com.commonsbell;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Maps a Northern Ireland postcode to a best-effort Assembly constituency.
 * The dataset is approximate (bundled asset); the UI always lets the user
 * confirm/correct the result with the constituency dropdown.
 */
public class PostcodeLookup {

    private static Map<String, String> cache; // district (e.g. "BT53") -> constituency name

    private static Map<String, String> load(Context ctx) {
        if (cache != null) return cache;
        Map<String, String> out = new HashMap<>();
        try {
            InputStream in = ctx.getAssets().open("postcodes.json");
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
            JSONObject districts = new JSONObject(sb.toString()).optJSONObject("districts");
            if (districts != null) {
                Iterator<String> it = districts.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    out.put(k, districts.optString(k));
                }
            }
        } catch (Exception ignored) {
            // empty map; lookup returns null and the user picks manually
        }
        cache = out;
        return out;
    }

    /** Extract the outward code (e.g. "BT53" from "BT53 6QP", "BT5" from "BT5 6QP"). */
    public static String districtOf(String postcode) {
        if (postcode == null) return "";
        String p = postcode.toUpperCase().trim();
        int sp = p.indexOf(' ');
        // outward code = the token before the first space (most accurate)
        String outward = sp > 0 ? p.substring(0, sp) : p.replaceAll("\\s+", "");
        if (!outward.startsWith("BT")) return outward;
        int i = 2;
        while (i < outward.length() && i < 4 && Character.isDigit(outward.charAt(i))) {
            i++;
        }
        return outward.substring(0, i);
    }

    /** Return the constituency name for a postcode, or null if unknown. */
    public static String constituencyFor(Context ctx, String postcode) {
        String district = districtOf(postcode);
        if (district.isEmpty()) return null;
        return load(ctx).get(district);
    }
}
