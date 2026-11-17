package com.commonsbell;

import android.content.Context;

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
            DataFetcher.Snapshot s = new DataFetcher().fetch(DataFetcher.DEFAULT_CONSTITUENCY_ID);
            Cache.save(getApplicationContext(), s);
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
