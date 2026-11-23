package com.commonsbell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.HashSet;
import java.util.Set;

/**
 * Runs more often than the full refresh to watch for newly-recorded divisions.
 * When a new division appears ("the bell tolls") it posts a local notification.
 * The first run only seeds the known-division set without notifying.
 */
public class DivisionWatchWorker extends Worker {

    static final String PREFS = "commonsbell";
    static final String KEY_KNOWN = "known_divisions";
    static final String CHANNEL_ID = "commons_bell";

    public DivisionWatchWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        try {
            Context ctx = getApplicationContext();
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int cid = prefs.getInt("constituency_id", DataFetcher.DEFAULT_CONSTITUENCY_ID);

            DataFetcher.Snapshot s = new DataFetcher().fetch(cid);
            Cache.save(ctx, s);

            Set<String> known = prefs.getStringSet(KEY_KNOWN, new HashSet<String>());
            boolean firstRun = known.isEmpty();

            Set<String> newIds = new HashSet<>();
            Set<String> all = new HashSet<>(known);
            for (DataFetcher.Vote v : s.votes) {
                String id = v.documentId;
                if (id == null || "null".equals(id) || id.isEmpty()) continue;
                all.add(id);
                if (!known.contains(id)) newIds.add(id);
            }
            prefs.edit().putStringSet(KEY_KNOWN, all).apply();

            if (!firstRun && !newIds.isEmpty()) {
                notify(ctx, newIds.size());
            }
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    private void notify(Context ctx, int count) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "The bell", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("New division votes recorded in the Assembly");
            nm.createNotificationChannel(ch);

            Intent intent = new Intent(ctx, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("\uD83D\uDD14 The bell tolls")
                    .setContentText(count + (count == 1 ? " new division" : " new divisions")
                            + " recorded in the Assembly")
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(1001, n);
        } catch (SecurityException ignored) {
            // notification permission not granted (Android 13+)
        }
    }
}
