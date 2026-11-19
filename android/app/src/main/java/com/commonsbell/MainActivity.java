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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private View homePage;
    private LinearLayout homeContainer;
    private View areaPage;
    private TextView navHome;
    private TextView navArea;
    private String currentAreaName = "North Antrim";

    private final List<DataFetcher.Constituency> constituencies = new ArrayList<>();
    private boolean populatingSpinner = false;
    private int currentConstituencyId = DataFetcher.DEFAULT_CONSTITUENCY_ID;

    // palette (matches res/values/colors.xml)
    private static final int C_ACCENT = Color.parseColor("#134E4A");
    private static final int C_INK = Color.parseColor("#1A2233");
    private static final int C_MUTED = Color.parseColor("#6B7280");
    private static final int C_LINE = Color.parseColor("#E5E7EB");

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
        homePage = findViewById(R.id.home_page);
        homeContainer = findViewById(R.id.home_container);
        areaPage = findViewById(R.id.area_page);
        navHome = findViewById(R.id.nav_home);
        navArea = findViewById(R.id.nav_area);

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

        navHome.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHome(); }
        });
        navArea.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showArea(); }
        });
        showArea();
        loadHomeData();
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
        statusView.setTextColor(error ? Color.parseColor("#B91C1C") : C_ACCENT);
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
    // Navigation (Home / My Area)
    // -------------------------------------------------------------------------
    private void showHome() {
        homePage.setVisibility(View.VISIBLE);
        areaPage.setVisibility(View.GONE);
        setNav(true);
    }

    private void showArea() {
        homePage.setVisibility(View.GONE);
        areaPage.setVisibility(View.VISIBLE);
        setNav(false);
    }

    private void setNav(boolean homeActive) {
        navHome.setTextColor(homeActive ? C_ACCENT : C_MUTED);
        navHome.setTypeface(Typeface.DEFAULT, homeActive ? Typeface.BOLD : Typeface.NORMAL);
        navArea.setTextColor(homeActive ? C_MUTED : C_ACCENT);
        navArea.setTypeface(Typeface.DEFAULT, homeActive ? Typeface.NORMAL : Typeface.BOLD);
    }

    // -------------------------------------------------------------------------
    // Home page
    // -------------------------------------------------------------------------
    private void loadHomeData() {
        if (homeContainer.getChildCount() == 0) {
            homeContainer.addView(tv("Loading…", C_MUTED, 14f, Typeface.NORMAL));
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<DataFetcher.DiaryItem> diary = new DataFetcher().getBusinessDiary(7);
                    final List<DataFetcher.Business> bills = new DataFetcher().getBillsInProgress();
                    runOnUiThread(new Runnable() {
                        @Override public void run() { renderHome(diary, bills); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            renderHomeError("Couldn't reach the Assembly. Pull to retry.");
                        }
                    });
                }
            }
        }).start();
    }

    private void renderHome(List<DataFetcher.DiaryItem> diary, List<DataFetcher.Business> bills) {
        homeContainer.removeAllViews();
        homeContainer.addView(buildAreaNavCard());

        homeContainer.addView(sectionHeader("WHAT'S ON TODAY"));
        if (diary == null || diary.isEmpty()) {
            homeContainer.addView(tv("Nothing scheduled in the coming days.", C_MUTED, 14f, Typeface.NORMAL));
        } else {
            int shown = 0;
            for (DataFetcher.DiaryItem d : diary) {
                if (shown++ >= 15) break;
                homeContainer.addView(buildDiaryCard(d));
            }
        }

        homeContainer.addView(sectionHeader("BILLS IN PROGRESS"));
        if (bills == null || bills.isEmpty()) {
            homeContainer.addView(tv("No bills in progress found.", C_MUTED, 14f, Typeface.NORMAL));
        } else {
            int shown = 0;
            for (DataFetcher.Business b : bills) {
                if (shown++ >= 12) break;
                homeContainer.addView(buildBusinessCard(b));
            }
        }
    }

    private void renderHomeError(String msg) {
        homeContainer.removeAllViews();
        homeContainer.addView(buildAreaNavCard());
        homeContainer.addView(tv(msg, C_MUTED, 14f, Typeface.NORMAL));
    }

    private LinearLayout buildAreaNavCard() {
        final LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(tv("YOUR AREA", C_MUTED, 11f, Typeface.BOLD));
        left.addView(tv(currentAreaName, C_INK, 16f, Typeface.BOLD));
        row.addView(left);
        row.addView(tv("→", C_ACCENT, 20f, Typeface.BOLD));
        card.addView(row);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showArea(); }
        });
        return card;
    }

    private TextView sectionHeader(String text) {
        TextView t = tv(text, C_MUTED, 12f, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout buildDiaryCard(DataFetcher.DiaryItem d) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = tv((d.startTime == null || d.startTime.isEmpty()) ? "--:--" : d.startTime,
                C_INK, 15f, Typeface.BOLD);
        time.setPadding(0, 0, dp(12), 0);
        row.addView(time);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(tv(d.organisationName, C_INK, 14f, Typeface.BOLD));
        String sub = d.eventType == null ? "" : d.eventType;
        if (d.locationRoom != null && !d.locationRoom.isEmpty()) sub += "  ·  " + d.locationRoom;
        if (d.weekday != null && !d.weekday.isEmpty()) sub += "  ·  " + d.weekday;
        col.addView(tv(sub, C_MUTED, 12f, Typeface.NORMAL));
        row.addView(col);
        card.addView(row);
        return card;
    }

    private LinearLayout buildBusinessCard(final DataFetcher.Business b) {
        LinearLayout card = card();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(tv(b.title, C_INK, 14f, Typeface.BOLD));
        String sub = (b.type == null || b.type.isEmpty()) ? "" : b.type;
        if (b.date != null && !b.date.isEmpty()) {
            sub = sub.isEmpty() ? formatDate(b.date) : sub + "  ·  " + formatDate(b.date);
        }
        if (!sub.isEmpty()) left.addView(tv(sub, C_MUTED, 12f, Typeface.NORMAL));
        header.addView(left);

        if (b.text != null && !b.text.isEmpty()) {
            final TextView arrow = tv("▸", C_MUTED, 16f, Typeface.BOLD);
            header.addView(arrow);
            card.addView(header);
            final TextView body = tv(truncate(b.text, 400), C_INK, 13f, Typeface.NORMAL);
            body.setVisibility(View.GONE);
            body.setPadding(dp(4), dp(8), dp(4), 0);
            card.addView(body);
            final boolean[] open = {false};
            header.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    open[0] = !open[0];
                    body.setVisibility(open[0] ? View.VISIBLE : View.GONE);
                    arrow.setText(open[0] ? "▾" : "▸");
                }
            });
        } else {
            card.addView(header);
        }
        return card;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------
    private void render(DataFetcher.Snapshot s) {
        if (s.constituencyName != null && !s.constituencyName.isEmpty()) {
            currentAreaName = s.constituencyName;
        }
        Map<String, List<DataFetcher.Statement>> byName = groupStatements(s.statements);
        renderMlas(s.mlas, byName);
        renderVotes(s.votes);
    }

    // -------------------------------------------------------------------------
    // MLAs (with their statements collapsed into each card)
    // -------------------------------------------------------------------------
    private Map<String, List<DataFetcher.Statement>> groupStatements(List<DataFetcher.Statement> stmts) {
        Map<String, List<DataFetcher.Statement>> m = new HashMap<>();
        if (stmts == null) return m;
        for (DataFetcher.Statement s : stmts) {
            String key = s.mla == null ? "" : s.mla;
            List<DataFetcher.Statement> list = m.get(key);
            if (list == null) {
                list = new ArrayList<>();
                m.put(key, list);
            }
            list.add(s);
        }
        for (List<DataFetcher.Statement> list : m.values()) {
            Collections.sort(list, new java.util.Comparator<DataFetcher.Statement>() {
                @Override public int compare(DataFetcher.Statement a, DataFetcher.Statement b) {
                    String da = a.date == null ? "" : a.date;
                    String db = b.date == null ? "" : b.date;
                    return db.compareTo(da); // newest first
                }
            });
        }
        return m;
    }

    private void renderMlas(List<DataFetcher.Mla> mlas,
                            Map<String, List<DataFetcher.Statement>> byName) {
        mlasContainer.removeAllViews();
        if (mlas == null || mlas.isEmpty()) {
            mlasContainer.addView(tv("No MLAs found for this area.", C_MUTED, 14f, Typeface.NORMAL));
            return;
        }
        for (final DataFetcher.Mla m : mlas) {
            List<DataFetcher.Statement> stmts = byName.get(m.name);
            if (stmts == null) stmts = Collections.emptyList();
            mlasContainer.addView(buildMlaCard(m, stmts));
        }
    }

    private LinearLayout buildMlaCard(final DataFetcher.Mla m,
                                      final List<DataFetcher.Statement> stmts) {
        LinearLayout card = card();
        int pc = partyColor(m.party);

        // header row (tap to expand/collapse statements)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(tv(m.name, pc, 16f, Typeface.BOLD));
        left.addView(tv(m.party, pc, 12f, Typeface.BOLD));
        String last = mostRecentDate(stmts);
        if (!last.isEmpty()) {
            left.addView(tv("Last spoke " + formatDate(last), C_MUTED, 12f, Typeface.NORMAL));
        }
        header.addView(left);

        final TextView arrow = tv("▸", C_MUTED, 18f, Typeface.BOLD);
        header.addView(arrow);
        card.addView(header);

        // collapsible body: statements + profile link
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        body.setPadding(dp(4), dp(8), dp(4), dp(2));

        if (m.profileUrl != null && !m.profileUrl.isEmpty()) {
            TextView prof = tv("Official profile ↗", C_ACCENT, 12f, Typeface.BOLD);
            prof.setPadding(0, 0, 0, dp(4));
            prof.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { open(m.profileUrl); }
            });
            body.addView(prof);
        }

        if (!stmts.isEmpty()) {
            for (int i = 0; i < stmts.size(); i++) {
                if (i > 0) body.addView(divider());
                body.addView(buildStatementEntry(stmts.get(i)));
            }
        } else {
            body.addView(tv("No recent statements in Hansard.", C_MUTED, 13f, Typeface.NORMAL));
        }
        card.addView(body);

        final boolean[] expanded = {false};
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                expanded[0] = !expanded[0];
                body.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                arrow.setText(expanded[0] ? "▾" : "▸");
            }
        });

        return card;
    }

    private LinearLayout buildStatementEntry(final DataFetcher.Statement s) {
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setPadding(0, dp(6), 0, dp(6));
        if (s.date != null && !s.date.isEmpty()) {
            e.addView(tv(formatDate(s.date), C_MUTED, 11f, Typeface.NORMAL));
        }
        if (s.topic != null && !s.topic.isEmpty()) {
            e.addView(tv(s.topic, C_ACCENT, 12f, Typeface.BOLD));
        }
        if (s.text != null && !s.text.isEmpty()) {
            e.addView(tv(s.text, C_INK, 13f, Typeface.NORMAL));
        }
        if (s.url != null && !s.url.isEmpty()) {
            TextView link = tv("Official Report ↗", C_ACCENT, 12f, Typeface.BOLD);
            link.setPadding(0, dp(4), 0, 0);
            link.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { open(s.url); }
            });
            e.addView(link);
        }
        return e;
    }

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        v.setBackgroundColor(C_LINE);
        return v;
    }

    private String mostRecentDate(List<DataFetcher.Statement> stmts) {
        String latest = "";
        for (DataFetcher.Statement s : stmts) {
            if (s.date != null && s.date.compareTo(latest) > 0) latest = s.date;
        }
        return latest;
    }

    // -------------------------------------------------------------------------
    // Votes (party-coloured names + collapsible "more info")
    // -------------------------------------------------------------------------
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

                TextView who = tv(mv.name, partyColor(mv.party), 13f, Typeface.NORMAL);
                who.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(who);

                row.addView(chip(voteLabel(mv.vote), voteColor(mv.vote)));
                card.addView(row);
            }

            if (hasVoteInfo(v)) {
                final LinearLayout info = buildVoteInfo(v);
                info.setVisibility(View.GONE);
                final TextView toggle = tv("▸ More info", C_ACCENT, 12f, Typeface.BOLD);
                toggle.setPadding(0, dp(6), 0, 0);
                card.addView(toggle);
                card.addView(info);

                final boolean[] open = {false};
                toggle.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vv) {
                        open[0] = !open[0];
                        info.setVisibility(open[0] ? View.VISIBLE : View.GONE);
                        toggle.setText(open[0] ? "▾ Less info" : "▸ More info");
                    }
                });
            }
            votesContainer.addView(card);
        }
    }

    private boolean hasVoteInfo(DataFetcher.Vote v) {
        return (v.description != null && !v.description.isEmpty())
                || (v.outcome != null && !v.outcome.isEmpty())
                || (v.totalAyes != null && !v.totalAyes.isEmpty())
                || (v.totalNoes != null && !v.totalNoes.isEmpty())
                || (v.totalAbstentions != null && !v.totalAbstentions.isEmpty());
    }

    private LinearLayout buildVoteInfo(DataFetcher.Vote v) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F6F7F9"));
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);

        if (v.description != null && !v.description.isEmpty()) {
            b.addView(tv(truncate(v.description, 420), C_INK, 13f, Typeface.NORMAL));
        }
        if (v.outcome != null && !v.outcome.isEmpty()) {
            TextView o = tv(v.outcome, C_ACCENT, 13f, Typeface.BOLD);
            o.setPadding(0, dp(6), 0, 0);
            b.addView(o);
        }
        StringBuilder sb = new StringBuilder();
        if (v.totalAyes != null && !v.totalAyes.isEmpty()) sb.append("Ayes ").append(v.totalAyes);
        if (v.totalNoes != null && !v.totalNoes.isEmpty()) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append("Noes ").append(v.totalNoes);
        }
        if (v.totalAbstentions != null && !v.totalAbstentions.isEmpty()) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append("Abstained ").append(v.totalAbstentions);
        }
        if (sb.length() > 0) {
            b.addView(tv(sb.toString(), C_MUTED, 12f, Typeface.NORMAL));
        }
        return b;
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
            "AYE", "AYE (PROXY)", "SIN", "SUPPORT", "TA", "FOR", "YES", "CONTENT"
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

    private static String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        String[] p = iso.split("-");
        if (p.length != 3) return iso;
        String[] months = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
        try {
            int mm = Integer.parseInt(p[1]);
            int dd = Integer.parseInt(p[2]);
            String mon = (mm >= 1 && mm <= 12) ? months[mm - 1] : p[1];
            return dd + " " + mon + " " + p[0];
        } catch (NumberFormatException e) {
            return iso;
        }
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() <= max) return s;
        return s.substring(0, max).trim() + "…";
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
