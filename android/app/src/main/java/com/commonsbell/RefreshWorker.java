package com.commonsbell;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Background job (runs every ~3 hours) that refreshes the cached snapshot. */
public class RefreshWorker extends Worker {

    public RefreshWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        try {
            SharedPreferences p = getApplicationContext()
                    .getSharedPreferences("commonsbell", Context.MODE_PRIVATE);
            int id = p.getInt("constituency_id", DataFetcher.DEFAULT_CONSTITUENCY_ID);
            DataFetcher.Snapshot s = new DataFetcher().fetch(id);
            Cache.save(getApplicationContext(), s);
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
