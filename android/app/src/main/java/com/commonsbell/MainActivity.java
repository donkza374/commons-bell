package com.commonsbell;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String PREFS = "commonsbell";
    private static final String KEY_CONSTITUENCY = "constituency_id";

    private TextView statusView;
    private ProgressBar progress;
    private Button updateBtn;
    private Spinner spinner;
    private SwipeRefreshLayout swipe;
    private LinearLayout mlasContainer;
    private LinearLayout votesContainer;
    private LinearLayout statementsContainer;

    private final List<DataFetcher.Constituency> constituencies = new ArrayList<>();
    private boolean populatingSpinner = false;
    private int currentConstituencyId = DataFetcher.DEFAULT_CONSTITUENCY_ID;

    // palette (matches res/values/colors.xml)
    private static final int C_ACCENT = Color.parseColor("#134E4A");
    private static final int C_INK = Color.parseColor("#1A2233");
    private static final int C_MUTED = Color.parseColor("#6B7280");
    private static final int C_LINE = Color.parseColor("#E5E7EB");
    private static final int C_ERROR = Color.parseColor("#B91C1C");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        updateBtn = findViewById(R.id.update_btn);
        spinner = findViewById(R.id.constituency_spinner);
        swipe = findViewById(R.id.swipe);
        mlasContainer = findViewById(R.id.mlas_container);
        votesContainer = findViewById(R.id.votes_container);
        statementsContainer = findViewById(R.id.statements_container);

        currentConstituencyId = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_CONSTITUENCY, DataFetcher.DEFAULT_CONSTITUENCY_ID);

        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(false); }
        });

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (populatingSpinner || pos < 0 || pos >= constituencies.size()) return;
                DataFetcher.Constituency c = constituencies.get(pos);
                int cid = parseIntSafe(c.id, DataFetcher.DEFAULT_CONSTITUENCY_ID);
                if (cid == currentConstituencyId) return;
                currentConstituencyId = cid;
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putInt(KEY_CONSTITUENCY, cid).apply();
                refresh(false);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        swipe.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override public void onRefresh() { refresh(true); }
        });

        scheduleBackgroundRefresh();

        // show cached data immediately (if it matches the selected area), then refresh
        DataFetcher.Snapshot cached = Cache.load(this);
        if (cached != null && !cached.mlas.isEmpty()) {
            render(cached);
            String ago = timeAgo(Cache.lastRefresh(this));
            setStatus("Cached · updated " + ago + " · refreshing…", false);
        }

        loadConstituencies();
    }

    // -------------------------------------------------------------------------
    // Constituency picker
    // -------------------------------------------------------------------------
    private void loadConstituencies() {
        setLoading(true);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<DataFetcher.Constituency> list =
                            new DataFetcher().getConstituencies();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            constituencies.clear();
                            constituencies.addAll(list);
                            populateSpinner();
                            refresh(false);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setLoading(false);
                            setStatus("Couldn't reach the NI Assembly. Check your connection and try again.",
                                    true);
                            // fall back to the single default so the app still works
                            populateSpinner();
                        }
                    });
                }
            }
        }).start();
    }

    private void populateSpinner() {
        populatingSpinner = true;
        List<String> names = new ArrayList<>();
        for (DataFetcher.Constituency c : constituencies) names.add(c.name);
        if (names.isEmpty()) {
            names.add("North Antrim");
            DataFetcher.Constituency d = new DataFetcher.Constituency();
            d.id = String.valueOf(DataFetcher.DEFAULT_CONSTITUENCY_ID);
            d.name = "North Antrim";
            constituencies.add(d);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        int idx = 0;
        for (int i = 0; i < constituencies.size(); i++) {
            if (parseIntSafe(constituencies.get(i).id, -1) == currentConstituencyId) {
                idx = i;
                break;
            }
        }
        spinner.setSelection(idx, false);
        populatingSpinner = false;
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------
    private void refresh(final boolean fromPull) {
        setLoading(true);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final DataFetcher.Snapshot s =
                            new DataFetcher().fetch(currentConstituencyId);
                    Cache.save(MainActivity.this, s);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            render(s);
                            setStatus("Updated just now", false);
                            setLoading(false);
                            if (fromPull) swipe.setRefreshing(false);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setStatus("Couldn't reach the NI Assembly. Check your connection and try again.",
                                    true);
                            setLoading(false);
                            if (fromPull) swipe.setRefreshing(false);
                        }
                    });
                }
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        updateBtn.setEnabled(!loading);
        if (loading) {
            updateBtn.setText("Updating…");
            setStatus("Refreshing…", false);
        } else {
            updateBtn.setText(R.string.update);
        }
    }

    private void setStatus(String msg, boolean error) {
        statusView.setText(msg);
        statusView.setTextColor(error ? C_ERROR : C_ACCENT);
        statusView.setBackgroundColor(error ? Color.parseColor("#FDECEC") : Color.parseColor("#E6F0EF"));
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
        renderMlas(s.mlas);
        renderVotes(s.votes);
        renderStatements(s.statements);
    }

    private void renderMlas(List<DataFetcher.Mla> mlas) {
        mlasContainer.removeAllViews();
        if (mlas == null || mlas.isEmpty()) {
            mlasContainer.addView(tv("No MLAs found for this area.", C_MUTED, 14f, Typeface.NORMAL));
            return;
        }
        for (final DataFetcher.Mla m : mlas) {
            LinearLayout card = card();
            card.addView(tv(m.name, C_INK, 15f, Typeface.BOLD));
            TextView party = tv(m.party, partyColor(m.party), 13f, Typeface.BOLD);
            card.addView(party);
            if (m.profileUrl != null && !m.profileUrl.isEmpty()) {
                card.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { open(m.profileUrl); }
                });
            }
            mlasContainer.addView(card);
        }
    }

    private void renderVotes(List<DataFetcher.Vote> votes) {
        votesContainer.removeAllViews();
        if (votes == null || votes.isEmpty()) {
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

                row.addView(chip(voteLabel(mv.vote), voteColor(mv.vote)));
                card.addView(row);
            }
            votesContainer.addView(card);
        }
    }

    private void renderStatements(List<DataFetcher.Statement> stmts) {
        statementsContainer.removeAllViews();
        if (stmts == null || stmts.isEmpty()) {
            statementsContainer.addView(tv("No recent statements from your MLAs.", C_MUTED, 14f, Typeface.NORMAL));
            return;
        }
        for (final DataFetcher.Statement s : stmts) {
            LinearLayout card = card();
            TextView head = tv(s.mla, C_INK, 14f, Typeface.BOLD);
            card.addView(head);
            TextView party = tv(s.party, partyColor(s.party), 12f, Typeface.BOLD);
            card.addView(party);
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

    private TextView chip(String label, int color) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        int padH = dp(8), padV = dp(2);
        t.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        t.setBackground(bg);
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

    private static final String[] AYE_CODES = {
            "AYE", "AYE (PROXY)", "SIN", "SUPPORT",
            "TA", "FOR", "YES", "CONTENT"
    };
    private static final String[] NO_CODES = {
            "NO", "NO (PROXY)", "AGAINST", "NOES", "NOT CONTENT", "NIL"
    };

    private int voteColor(String v) {
        if (v == null) return C_MUTED;
        String u = v.toUpperCase();
        for (String c : AYE_CODES) if (u.contains(c)) return Color.parseColor("#15803D");
        for (String c : NO_CODES) if (u.contains(c)) return Color.parseColor("#B91C1C");
        if (u.contains("ABSTAIN")) return Color.parseColor("#B45309");
        if (u.contains("ABSENT")) return C_MUTED;
        return C_MUTED;
    }

    private int partyColor(String party) {
        if (party == null) return C_MUTED;
        String p = party.toLowerCase();
        if (p.contains("sinn")) return Color.parseColor("#008000");          // Sinn Féin
        if (p.contains("dup")) return Color.parseColor("#D46A4A");           // DUP
        if (p.contains("uup") || p.contains("ulster unionist")) return Color.parseColor("#48A5EE");
        if (p.contains("alliance")) return Color.parseColor("#D4A017");
        if (p.contains("tuv")) return Color.parseColor("#1F4E79");
        if (p.contains("sdlp")) return Color.parseColor("#2AA82A");
        if (p.equals("green") || p.contains("green party")) return Color.parseColor("#008000");
        return C_MUTED;
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
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
