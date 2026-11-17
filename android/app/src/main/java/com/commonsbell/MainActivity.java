package com.commonsbell;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private TextView statusView;
    private TextView areaName;
    private Button updateBtn;
    private LinearLayout mlasContainer;
    private LinearLayout votesContainer;
    private LinearLayout statementsContainer;

    // palette (matches res/values/colors.xml)
    private static final int C_ACCENT = Color.parseColor("#134E4A");
    private static final int C_INK = Color.parseColor("#1A2233");
    private static final int C_MUTED = Color.parseColor("#6B7280");
    private static final int C_LINE = Color.parseColor("#E5E7EB");
    private static final int C_AYE = Color.parseColor("#15803D");
    private static final int C_NO = Color.parseColor("#B91C1C");
    private static final int C_ABSTAINED = Color.parseColor("#B45309");
    private static final int C_ABSENT = Color.parseColor("#6B7280");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        areaName = findViewById(R.id.area_name);
        updateBtn = findViewById(R.id.update_btn);
        mlasContainer = findViewById(R.id.mlas_container);
        votesContainer = findViewById(R.id.votes_container);
        statementsContainer = findViewById(R.id.statements_container);

        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(true); }
        });

        scheduleBackgroundRefresh();

        // show cached data immediately, then refresh in the background
        DataFetcher.Snapshot cached = Cache.load(this);
        if (cached != null) {
            render(cached);
            String ago = timeAgo(Cache.lastRefresh(this));
            setStatus("Cached · updated " + ago + " · refreshing…");
        }
        refresh(false);
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------
    private void refresh(final boolean fromButton) {
        setBusy(true, fromButton);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final DataFetcher.Snapshot s =
                            new DataFetcher().fetch(DataFetcher.DEFAULT_CONSTITUENCY_ID);
                    Cache.save(MainActivity.this, s);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            render(s);
                            setStatus("Updated just now");
                            setBusy(false, fromButton);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setStatus("Update failed: " + e.getMessage());
                            setBusy(false, fromButton);
                        }
                    });
                }
            }
        }).start();
    }

    private void setBusy(boolean busy, boolean fromButton) {
        updateBtn.setEnabled(!busy);
        if (busy) updateBtn.setText("Updating…");
        else updateBtn.setText(R.string.update);
    }

    private void setStatus(String msg) {
        statusView.setText(msg);
    }

    // -------------------------------------------------------------------------
    // Schedule background refresh (every 3 hours)
    // -------------------------------------------------------------------------
    private void scheduleBackgroundRefresh() {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                RefreshWorker.class, 3, TimeUnit.HOURS).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "refresh", ExistingPeriodicWorkPolicy.KEEP, req);
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------
    private void render(DataFetcher.Snapshot s) {
        areaName.setText(s.constituencyName);
        renderMlas(s.mlas);
        renderVotes(s.votes);
        renderStatements(s.statements);
    }

    private void renderMlas(List<DataFetcher.Mla> mlas) {
        mlasContainer.removeAllViews();
        for (final DataFetcher.Mla m : mlas) {
            LinearLayout card = card();
            TextView name = tv(m.name, C_INK, 15f, Typeface.BOLD);
            card.addView(name);
            TextView party = tv(m.party, C_MUTED, 13f, Typeface.NORMAL);
            card.addView(party);
            if (m.profileUrl != null && !m.profileUrl.isEmpty()) {
                card.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        open(m.profileUrl);
                    }
                });
            }
            mlasContainer.addView(card);
        }
    }

    private void renderVotes(List<DataFetcher.Vote> votes) {
        votesContainer.removeAllViews();
        if (votes.isEmpty()) {
            votesContainer.addView(tv("No recent divisions found.", C_MUTED, 14f, Typeface.NORMAL));
            return;
        }
        for (DataFetcher.Vote v : votes) {
            LinearLayout card = card();
            card.addView(tv(v.subject, C_INK, 14f, Typeface.BOLD));
            card.addView(tv(v.date + (v.type != null && !v.type.isEmpty()
                    ? "  ·  " + v.type : ""), C_MUTED, 12f, Typeface.NORMAL));

            for (DataFetcher.MyVote mv : v.myVotes) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(4), 0, dp(4));

                TextView who = tv(mv.name, C_INK, 13f, Typeface.NORMAL);
                who.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(who);

                TextView vote = tv(voteLabel(mv.vote), voteColor(mv.vote), 12f, Typeface.BOLD);
                row.addView(vote);
                card.addView(row);
            }
            votesContainer.addView(card);
        }
    }

    private void renderStatements(List<DataFetcher.Statement> stmts) {
        statementsContainer.removeAllViews();
        if (stmts.isEmpty()) {
            statementsContainer.addView(tv("No recent statements from your MLAs.", C_MUTED, 14f, Typeface.NORMAL));
            return;
        }
        for (final DataFetcher.Statement s : stmts) {
            LinearLayout card = card();
            card.addView(tv(s.mla + "  ·  " + s.party, C_INK, 14f, Typeface.BOLD));
            if (s.topic != null && !s.topic.isEmpty()) {
                card.addView(tv(s.topic, C_ACCENT, 12f, Typeface.BOLD));
            }
            card.addView(tv(s.text, C_INK, 13f, Typeface.NORMAL));
            if (s.url != null && !s.url.isEmpty()) {
                TextView link = tv("Official Report ↗", C_ACCENT, 12f, Typeface.BOLD);
                link.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { open(s.url); }
                });
                card.addView(link);
            }
            statementsContainer.addView(card);
        }
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView tv(String text, int color, float sp, int style) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private LinearLayout card() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), C_LINE);
        ll.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        ll.setLayoutParams(lp);
        return ll;
    }

    private static String voteLabel(String v) {
        if (v == null) return "—";
        String u = v.toUpperCase();
        if ("AYE".equals(u)) return "AYE";
        if ("NO".equals(u)) return "NO";
        if ("ABSTAINED".equals(u)) return "ABSTAINED";
        if ("ABSENT".equals(u)) return "ABSENT";
        return "—";
    }

    private int voteColor(String v) {
        if (v == null) return C_ABSENT;
        String u = v.toUpperCase();
        if ("AYE".equals(u)) return C_AYE;
        if ("NO".equals(u)) return C_NO;
        if ("ABSTAINED".equals(u)) return C_ABSTAINED;
        return C_ABSENT;
    }

    private static String timeAgo(long epochMillis) {
        if (epochMillis <= 0) return "—";
        long s = (System.currentTimeMillis() - epochMillis) / 1000;
        if (s < 60) return "just now";
        long m = s / 60;
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " hr ago";
        return (h / 24) + " days ago";
    }
}
