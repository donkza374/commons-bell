package com.nipoliticstracker;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists the latest snapshot so the app opens instantly (even offline). */
public class Cache {
    private static final String PREFS = "nipolitics";
    private static final String KEY_SNAPSHOT = "snapshot";
    private static final String KEY_LAST_REFRESH = "last_refresh";

    public static void save(Context c, DataFetcher.Snapshot s) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit()
                .putString(KEY_SNAPSHOT, DataFetcher.snapshotToJson(s).toString())
                .putLong(KEY_LAST_REFRESH, System.currentTimeMillis())
                .apply();
    }

    public static DataFetcher.Snapshot load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = p.getString(KEY_SNAPSHOT, null);
        return json == null ? null : DataFetcher.snapshotFromJson(json);
    }

    public static long lastRefresh(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_REFRESH, 0L);
    }
}
